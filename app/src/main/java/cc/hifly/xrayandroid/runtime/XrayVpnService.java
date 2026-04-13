package cc.hifly.xrayandroid.runtime;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import cc.hifly.xrayandroid.MainActivity;
import cc.hifly.xrayandroid.R;
import cc.hifly.xrayandroid.data.AppStateStore;
import cc.hifly.xrayandroid.model.AppState;
import cc.hifly.xrayandroid.model.NodeRecord;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class XrayVpnService extends VpnService {
    public static final String ACTION_START = "cc.hifly.xrayandroid.runtime.START";
    public static final String ACTION_STOP = "cc.hifly.xrayandroid.runtime.STOP";
    public static final String EXTRA_NODE_ID = "node_id";

    private static final String NOTIFICATION_CHANNEL_ID = "xray_runtime";
    private static final int NOTIFICATION_ID = 1001;
    private static final int VPN_MTU = 1500;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private Process xrayProcess;
    private int tunFd = -1;
    private volatile boolean stopping;

    public static void start(Context context, String nodeId) {
        Intent intent = new Intent(context, XrayVpnService.class);
        intent.setAction(ACTION_START);
        intent.putExtra(EXTRA_NODE_ID, nodeId);
        ContextCompat.startForegroundService(context, intent);
    }

    public static void stop(Context context) {
        Intent intent = new Intent(context, XrayVpnService.class);
        intent.setAction(ACTION_STOP);
        context.startService(intent);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        ensureNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_STOP : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            executor.execute(() -> stopRuntime(
                    getString(R.string.status_value_vpn_stopped),
                    getString(R.string.status_detail_vpn_stopped)
            ));
            return Service.START_NOT_STICKY;
        }

        String nodeId = intent == null ? "" : intent.getStringExtra(EXTRA_NODE_ID);
        startForeground(NOTIFICATION_ID, buildNotification(getString(R.string.notification_text_starting)));
        executor.execute(() -> startRuntime(nodeId));
        return Service.START_STICKY;
    }

    @Override
    public void onRevoke() {
        super.onRevoke();
        executor.execute(() -> stopRuntime(
                getString(R.string.status_value_vpn_stopped),
                getString(R.string.status_detail_vpn_stopped)
        ));
    }

    @Override
    public void onDestroy() {
        executor.execute(this::stopRuntimeInternal);
        executor.shutdownNow();
        super.onDestroy();
    }

    @Nullable
    @Override
    public android.os.IBinder onBind(Intent intent) {
        return super.onBind(intent);
    }

    private void startRuntime(String nodeId) {
        stopRuntimeInternal();

        NodeRecord node = findNode(nodeId);
        if (node == null) {
            RuntimePreferences.markStopped(
                    this,
                    getString(R.string.status_value_vpn_failed),
                    getString(R.string.error_runtime_node_missing)
            );
            stopSelf();
            return;
        }

        RuntimePreferences.markStarting(
                this,
                node.id,
                node.displayName,
                getString(R.string.status_value_vpn_starting),
                getString(R.string.status_detail_vpn_selected, node.displayName)
        );

        try {
            ParcelFileDescriptor tunInterface = buildTunInterface(node.displayName);
            if (tunInterface == null) {
                throw new IllegalStateException(getString(R.string.error_runtime_vpn_establish_failed));
            }
            tunFd = tunInterface.detachFd();

            File runtimeDir = new File(getFilesDir(), "runtime");
            if (!runtimeDir.exists() && !runtimeDir.mkdirs()) {
                throw new IllegalStateException("runtime dir create failed");
            }

            File configFile = new File(runtimeDir, "xray-config.json");
            try (BufferedWriter writer = new BufferedWriter(
                    new OutputStreamWriter(new FileOutputStream(configFile, false), StandardCharsets.UTF_8)
            )) {
                writer.write(new XrayConfigBuilder().build(node, VPN_MTU));
            }

            File executable = resolveBundledCore();
            executable.setReadable(true, false);
            executable.setExecutable(true, false);

            File logFile = new File(runtimeDir, "xray-runtime.log");
            ProcessBuilder processBuilder = new ProcessBuilder(
                    executable.getAbsolutePath(),
                    "run",
                    "-c",
                    configFile.getAbsolutePath()
            );
            processBuilder.directory(runtimeDir);
            processBuilder.redirectErrorStream(true);
            processBuilder.environment().put("XRAY_TUN_FD", String.valueOf(tunFd));

            xrayProcess = processBuilder.start();
            pumpLog(xrayProcess.getInputStream(), logFile);
            monitorProcess(xrayProcess);

            RuntimePreferences.markRunning(
                    this,
                    node.id,
                    node.displayName,
                    getString(R.string.status_value_vpn_running),
                    getString(R.string.status_detail_vpn_running, node.displayName)
            );
            startForeground(
                    NOTIFICATION_ID,
                    buildNotification(getString(R.string.notification_text_running, node.displayName))
            );
        } catch (Exception exception) {
            stopRuntimeInternal();
            RuntimePreferences.markStopped(
                    this,
                    getString(R.string.status_value_vpn_failed),
                    getString(R.string.error_runtime_start_failed) + ": " + exception.getMessage()
            );
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
        }
    }

    private void stopRuntime(String title, String detail) {
        stopRuntimeInternal();
        RuntimePreferences.markStopped(this, title, detail);
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private void stopRuntimeInternal() {
        stopping = true;
        if (xrayProcess != null) {
            xrayProcess.destroy();
            xrayProcess = null;
        }
        if (tunFd >= 0) {
            try {
                ParcelFileDescriptor.adoptFd(tunFd).close();
            } catch (Exception ignored) {
                // already closed
            }
            tunFd = -1;
        }
        stopping = false;
    }

    private void monitorProcess(Process process) {
        Thread thread = new Thread(() -> {
            try {
                int exitCode = process.waitFor();
                if (!stopping && process == xrayProcess) {
                    stopRuntimeInternal();
                    RuntimePreferences.markStopped(
                            XrayVpnService.this,
                            getString(R.string.status_value_vpn_failed),
                            getString(R.string.error_runtime_start_failed) + ": exit " + exitCode
                    );
                    stopForeground(STOP_FOREGROUND_REMOVE);
                    stopSelf();
                }
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }, "xray-runtime-monitor");
        thread.setDaemon(true);
        thread.start();
    }

    private void pumpLog(InputStream inputStream, File logFile) {
        Thread thread = new Thread(() -> {
            try (
                    InputStreamReader reader = new InputStreamReader(inputStream, StandardCharsets.UTF_8);
                    BufferedWriter writer = new BufferedWriter(
                            new OutputStreamWriter(new FileOutputStream(logFile, true), StandardCharsets.UTF_8)
                    )
            ) {
                char[] buffer = new char[2048];
                int read;
                while ((read = reader.read(buffer)) >= 0) {
                    writer.write(buffer, 0, read);
                    writer.flush();
                }
            } catch (Exception ignored) {
                // runtime log is best effort only
            }
        }, "xray-runtime-log");
        thread.setDaemon(true);
        thread.start();
    }

    private NodeRecord findNode(String nodeId) {
        AppStateStore store = new AppStateStore(new File(getFilesDir(), "app-state.json"));
        AppState state = store.load();
        for (NodeRecord node : state.nodes) {
            if (node.id != null && node.id.equals(nodeId)) {
                return node;
            }
        }
        return null;
    }

    private ParcelFileDescriptor buildTunInterface(String sessionName) throws Exception {
        Builder builder = new Builder();
        builder.setSession(sessionName);
        builder.setMtu(VPN_MTU);
        builder.addAddress("172.19.0.1", 30);
        builder.addRoute("0.0.0.0", 0);
        builder.addDnsServer("1.1.1.1");
        builder.addDnsServer("8.8.8.8");
        builder.addAddress("fd00:1:fd00::1", 126);
        builder.addRoute("::", 0);
        try {
            builder.addDisallowedApplication(getPackageName());
        } catch (Exception ignored) {
            // keep the VPN startup path alive even if the self-bypass hint is rejected
        }
        return builder.establish();
    }

    private File resolveBundledCore() {
        File nativeLib = new File(getApplicationInfo().nativeLibraryDir, "libxray.so");
        if (nativeLib.isFile()) {
            return nativeLib;
        }
        throw new IllegalStateException(getString(R.string.error_runtime_unsupported_abi));
    }

    private Notification buildNotification(String contentText) {
        Intent openIntent = new Intent(this, MainActivity.class);
        openIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent openPendingIntent = PendingIntent.getActivity(
                this,
                1,
                openIntent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );

        Intent stopIntent = new Intent(this, XrayVpnService.class);
        stopIntent.setAction(ACTION_STOP);
        PendingIntent stopPendingIntent = PendingIntent.getService(
                this,
                2,
                stopIntent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );

        return new NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle(getString(R.string.notification_title))
                .setContentText(contentText)
                .setContentIntent(openPendingIntent)
                .setOngoing(true)
                .addAction(0, getString(R.string.notification_action_stop), stopPendingIntent)
                .build();
    }

    private void ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
        );
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.createNotificationChannel(channel);
        }
    }
}
