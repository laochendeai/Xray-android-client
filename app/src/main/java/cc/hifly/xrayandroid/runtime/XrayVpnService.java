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
import android.system.Os;
import android.system.OsConstants;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import cc.hifly.xrayandroid.MainActivity;
import cc.hifly.xrayandroid.R;
import cc.hifly.xrayandroid.data.AppStateStore;
import cc.hifly.xrayandroid.model.AppState;
import cc.hifly.xrayandroid.model.NodeRecord;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class XrayVpnService extends VpnService {
    public static final String ACTION_START = "cc.hifly.xrayandroid.runtime.START";
    public static final String ACTION_STOP = "cc.hifly.xrayandroid.runtime.STOP";
    public static final String EXTRA_NODE_ID = "node_id";

    private static final String NOTIFICATION_CHANNEL_ID = "xray_runtime";
    private static final int NOTIFICATION_ID = 1001;
    private static final int VPN_MTU = 1500;
    private static final String TAG = "XrayVpnService";
    private static final int PROXY_PROBE_CONNECT_TIMEOUT_MS = 2500;
    private static final int PROXY_PROBE_READ_TIMEOUT_MS = 2500;
    private static final int PROXY_PROBE_ATTEMPTS = 3;
    private static final long PROXY_PROBE_RETRY_DELAY_MS = 500L;
    private static final String[] PROXY_PROBE_URLS = new String[] {
            "https://cp.cloudflare.com/generate_204",
            "https://www.gstatic.com/generate_204"
    };

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private Process xrayProcess;
    private ParcelFileDescriptor tunInterfaceHandle;
    private volatile boolean stopping;
    private File currentLogFile;

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
                getString(R.string.status_detail_vpn_preflight, node.displayName)
        );

        try {
            File runtimeDir = new File(getFilesDir(), "runtime");
            if (!runtimeDir.exists() && !runtimeDir.mkdirs()) {
                throw new IllegalStateException("runtime dir create failed");
            }

            File executable = resolveBundledCore();
            executable.setReadable(true, false);
            executable.setExecutable(true, false);

            verifyNodeBeforeVpn(node, runtimeDir, executable);
            currentLogFile = null;

            RuntimePreferences.markStarting(
                    this,
                    node.id,
                    node.displayName,
                    getString(R.string.status_value_vpn_starting),
                    getString(R.string.status_detail_vpn_selected, node.displayName)
            );

            ParcelFileDescriptor tunInterface = buildTunInterface(node.displayName);
            if (tunInterface == null) {
                throw new IllegalStateException(getString(R.string.error_runtime_vpn_establish_failed));
            }
            tunInterfaceHandle = tunInterface;

            File configFile = new File(runtimeDir, "xray-config.json");
            writeConfig(configFile, new XrayConfigBuilder().build(node, VPN_MTU));

            File logFile = new File(runtimeDir, "xray-runtime.log");
            currentLogFile = logFile;
            xrayProcess = startRuntimeProcess(executable, runtimeDir, configFile, tunInterface);
            pumpLog(xrayProcess.getInputStream(), logFile);
            verifyProxyEgress();
            if (!xrayProcess.isAlive()) {
                throw new IllegalStateException(getString(R.string.error_runtime_probe_process_died));
            }

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
            monitorProcess(xrayProcess);
        } catch (Exception exception) {
            String failureDetail = getString(R.string.error_runtime_start_failed)
                    + ": " + enrichRuntimeFailureDetail(exception.getMessage());
            stopRuntimeInternal();
            RuntimePreferences.markStopped(
                    this,
                    getString(R.string.status_value_vpn_failed),
                    failureDetail
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
        currentLogFile = null;
        if (tunInterfaceHandle != null) {
            try {
                tunInterfaceHandle.close();
            } catch (Exception ignored) {
                // already closed
            }
            tunInterfaceHandle = null;
        }
        stopping = false;
    }

    private void monitorProcess(Process process) {
        Thread thread = new Thread(() -> {
            try {
                int exitCode = process.waitFor();
                if (!stopping && process == xrayProcess) {
                    String failureDetail = getString(R.string.error_runtime_start_failed)
                            + ": " + buildExitDetail(exitCode);
                    stopRuntimeInternal();
                    RuntimePreferences.markStopped(
                            XrayVpnService.this,
                            getString(R.string.status_value_vpn_failed),
                            failureDetail
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
                    BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8));
                    BufferedWriter writer = new BufferedWriter(
                            new OutputStreamWriter(new FileOutputStream(logFile, true), StandardCharsets.UTF_8)
                    )
            ) {
                String line;
                while ((line = reader.readLine()) != null) {
                    writer.write(line);
                    writer.newLine();
                    writer.flush();
                    Log.i(TAG, line);
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

    private void verifyNodeBeforeVpn(NodeRecord node, File runtimeDir, File executable) throws Exception {
        File configFile = new File(runtimeDir, "xray-preflight-config.json");
        writeConfig(configFile, new XrayConfigBuilder().buildPreflight(node));

        File logFile = new File(runtimeDir, "xray-preflight.log");
        currentLogFile = logFile;

        Process preflightProcess = null;
        try {
            preflightProcess = startRuntimeProcess(executable, runtimeDir, configFile, null);
            pumpLog(preflightProcess.getInputStream(), logFile);
            verifyProxyEgress();
            if (!preflightProcess.isAlive()) {
                throw new IllegalStateException(getString(R.string.error_runtime_probe_process_died));
            }
        } finally {
            stopProcess(preflightProcess);
        }
    }

    private File resolveBundledCore() {
        File nativeLib = new File(getApplicationInfo().nativeLibraryDir, "libxray.so");
        if (nativeLib.isFile()) {
            return nativeLib;
        }
        throw new IllegalStateException(getString(R.string.error_runtime_unsupported_abi));
    }

    private void writeConfig(File configFile, String content) throws Exception {
        try (BufferedWriter writer = new BufferedWriter(
                new OutputStreamWriter(new FileOutputStream(configFile, false), StandardCharsets.UTF_8)
        )) {
            writer.write(content);
        }
    }

    private Process startRuntimeProcess(
            File executable,
            File runtimeDir,
            File configFile,
            @Nullable ParcelFileDescriptor tunInterface
    ) throws Exception {
        ProcessBuilder processBuilder = new ProcessBuilder(
                executable.getAbsolutePath(),
                "run",
                "-c",
                configFile.getAbsolutePath()
        );
        processBuilder.directory(runtimeDir);
        processBuilder.redirectErrorStream(true);
        if (tunInterface == null) {
            return processBuilder.start();
        }
        processBuilder.environment().put("XRAY_TUN_FD", "0");
        return startProcessWithTunOnStdin(processBuilder, tunInterface);
    }

    private Process startProcessWithTunOnStdin(ProcessBuilder processBuilder, ParcelFileDescriptor tunInterface) throws Exception {
        FileDescriptor stdinBackup = Os.dup(FileDescriptor.in);
        try {
            Os.dup2(tunInterface.getFileDescriptor(), 0);
            return processBuilder.start();
        } finally {
            try {
                Os.dup2(stdinBackup, 0);
            } finally {
                Os.close(stdinBackup);
            }
        }
    }

    private void verifyProxyEgress() throws Exception {
        List<String> failures = new ArrayList<>();
        for (int attempt = 0; attempt < PROXY_PROBE_ATTEMPTS; attempt++) {
            for (String urlValue : PROXY_PROBE_URLS) {
                try {
                    probeUrlThroughLocalProxy(urlValue);
                    return;
                } catch (Exception exception) {
                    failures.add(compactProbeFailure(urlValue, exception));
                }
            }
            if (attempt < PROXY_PROBE_ATTEMPTS - 1) {
                try {
                    Thread.sleep(PROXY_PROBE_RETRY_DELAY_MS);
                } catch (InterruptedException interruptedException) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(getString(R.string.error_runtime_probe_failed));
                }
            }
        }
        throw new IllegalStateException(
                getString(R.string.error_runtime_probe_failed) + ": " + summarizeProbeFailures(failures)
        );
    }

    private void probeUrlThroughLocalProxy(String urlValue) throws Exception {
        Proxy proxy = new Proxy(
                Proxy.Type.HTTP,
                new InetSocketAddress("127.0.0.1", XrayConfigBuilder.PROBE_HTTP_INBOUND_PORT)
        );
        HttpURLConnection connection = (HttpURLConnection) new URL(urlValue).openConnection(proxy);
        connection.setConnectTimeout(PROXY_PROBE_CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(PROXY_PROBE_READ_TIMEOUT_MS);
        connection.setInstanceFollowRedirects(false);
        connection.setUseCaches(false);
        connection.setRequestProperty("User-Agent", "XrayAndroidClient/2026.04");
        try {
            int statusCode = connection.getResponseCode();
            if (statusCode >= 200 && statusCode < 400) {
                return;
            }
            throw new IllegalStateException(new URL(urlValue).getHost() + " -> HTTP " + statusCode);
        } finally {
            connection.disconnect();
        }
    }

    private String compactProbeFailure(String urlValue, Exception exception) {
        try {
            return new URL(urlValue).getHost() + " -> " + safeExceptionMessage(exception);
        } catch (Exception ignored) {
            return safeExceptionMessage(exception);
        }
    }

    private String summarizeProbeFailures(List<String> failures) {
        if (failures.isEmpty()) {
            return getString(R.string.error_runtime_probe_failed);
        }
        StringBuilder builder = new StringBuilder();
        int startIndex = Math.max(0, failures.size() - 3);
        for (int index = startIndex; index < failures.size(); index++) {
            if (builder.length() > 0) {
                builder.append(" | ");
            }
            builder.append(failures.get(index));
        }
        return builder.toString();
    }

    private String safeExceptionMessage(Exception exception) {
        if (exception == null || exception.getMessage() == null || exception.getMessage().trim().isEmpty()) {
            return exception == null ? "unknown error" : exception.getClass().getSimpleName();
        }
        return exception.getMessage().trim();
    }

    private void stopProcess(@Nullable Process process) {
        if (process == null) {
            return;
        }
        process.destroy();
        try {
            if (!process.waitFor(1, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor(1, TimeUnit.SECONDS);
            }
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
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

    private String buildExitDetail(int exitCode) {
        String tail = readRuntimeLogTail();
        if (!tail.isEmpty()) {
            return "exit " + exitCode + " · " + tail;
        }
        return "exit " + exitCode;
    }

    private String enrichRuntimeFailureDetail(String detail) {
        String tail = readRuntimeLogTail();
        if (tail.isEmpty()) {
            return detail;
        }
        if (detail == null || detail.trim().isEmpty()) {
            return tail;
        }
        return detail + " · " + tail;
    }

    private String readRuntimeLogTail() {
        File logFile = currentLogFile;
        if (logFile == null || !logFile.isFile()) {
            return "";
        }
        String lastLine = "";
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(logFile), StandardCharsets.UTF_8)
        )) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.trim().isEmpty()) {
                    lastLine = line.trim();
                }
            }
        } catch (Exception ignored) {
            return "";
        }
        return lastLine;
    }
}
