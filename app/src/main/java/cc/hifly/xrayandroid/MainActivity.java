package cc.hifly.xrayandroid;

import android.content.Context;
import android.database.Cursor;
import android.os.Bundle;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AlertDialog;

import cc.hifly.xrayandroid.data.AppStateStore;
import cc.hifly.xrayandroid.data.ImportCoordinator;
import cc.hifly.xrayandroid.data.ImportResult;
import cc.hifly.xrayandroid.model.AppState;
import cc.hifly.xrayandroid.model.NodeRecord;
import cc.hifly.xrayandroid.model.SubscriptionRecord;
import cc.hifly.xrayandroid.model.SubscriptionSourceType;
import cc.hifly.xrayandroid.parser.NodeUriParser;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

public class MainActivity extends AppCompatActivity {
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();

    private TextView statusValueText;
    private TextView statusDetailText;
    private TextView subscriptionsSummaryText;
    private TextView nodesSummaryText;
    private LinearLayout subscriptionListContainer;
    private LinearLayout nodeListContainer;

    private ImportCoordinator importCoordinator;
    private ActivityResultLauncher<String> filePickerLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        importCoordinator = new ImportCoordinator(
                new AppStateStore(new File(getFilesDir(), "app-state.json")),
                new NodeUriParser()
        );

        statusValueText = findViewById(R.id.statusValueText);
        statusDetailText = findViewById(R.id.statusDetailText);
        subscriptionsSummaryText = findViewById(R.id.subscriptionsSummaryText);
        nodesSummaryText = findViewById(R.id.nodesSummaryText);
        subscriptionListContainer = findViewById(R.id.subscriptionListContainer);
        nodeListContainer = findViewById(R.id.nodeListContainer);

        filePickerLauncher = registerForActivityResult(
                new ActivityResultContracts.GetContent(),
                this::handleFileSelected
        );

        bindActionButtons();
        setStatus(
                getString(R.string.status_value_ready),
                getString(R.string.status_detail_initial)
        );
        refreshState();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        ioExecutor.shutdownNow();
    }

    private void bindActionButtons() {
        wireButton(R.id.buttonImport, view -> showUrlImportDialog());
        wireButton(R.id.buttonPool, view -> showManualImportDialog());
        wireButton(R.id.buttonGateway, view -> filePickerLauncher.launch("*/*"));
    }

    private void wireButton(int buttonId, View.OnClickListener listener) {
        Button button = findViewById(buttonId);
        button.setOnClickListener(listener);
    }

    private void refreshState() {
        runBackground(
                () -> importCoordinator.loadSnapshot(),
                this::renderState,
                getString(R.string.error_load_state)
        );
    }

    private void showUrlImportDialog() {
        LinearLayout form = createDialogForm();
        EditText nameInput = createEditText(getString(R.string.hint_optional_name), false);
        EditText urlInput = createEditText(getString(R.string.hint_subscription_url), false);
        urlInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        form.addView(nameInput);
        form.addView(urlInput);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.dialog_title_import_url)
                .setView(form)
                .setPositiveButton(R.string.action_confirm, null)
                .setNegativeButton(R.string.action_cancel, null)
                .create();

        dialog.setOnShowListener(view -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    String url = urlInput.getText().toString().trim();
                    if (url.isEmpty()) {
                        urlInput.setError(getString(R.string.error_url_required));
                        return;
                    }

                    dialog.dismiss();
                    setStatus(
                            getString(R.string.status_value_importing),
                            getString(R.string.status_detail_fetching_url, url)
                    );
                    runBackground(
                            () -> {
                                String content = fetchRemoteContent(url);
                                return importCoordinator.importContent(
                                        nameInput.getText().toString().trim(),
                                        SubscriptionSourceType.URL,
                                        url,
                                        content
                                );
                            },
                            this::handleImportSuccess,
                            getString(R.string.error_import_failed)
                    );
                }));

        dialog.show();
    }

    private void showManualImportDialog() {
        LinearLayout form = createDialogForm();
        EditText nameInput = createEditText(getString(R.string.hint_optional_name), false);
        EditText contentInput = createEditText(getString(R.string.hint_manual_content), true);
        form.addView(nameInput);
        form.addView(contentInput);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.dialog_title_import_manual)
                .setView(form)
                .setPositiveButton(R.string.action_confirm, null)
                .setNegativeButton(R.string.action_cancel, null)
                .create();

        dialog.setOnShowListener(view -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    String content = contentInput.getText().toString().trim();
                    if (content.isEmpty()) {
                        contentInput.setError(getString(R.string.error_content_required));
                        return;
                    }

                    dialog.dismiss();
                    setStatus(
                            getString(R.string.status_value_importing),
                            getString(R.string.status_detail_parsing_manual)
                    );
                    runBackground(
                            () -> importCoordinator.importContent(
                                    nameInput.getText().toString().trim(),
                                    SubscriptionSourceType.MANUAL,
                                    "manual",
                                    content
                            ),
                            this::handleImportSuccess,
                            getString(R.string.error_import_failed)
                    );
                }));

        dialog.show();
    }

    private void handleFileSelected(Uri uri) {
        if (uri == null) {
            return;
        }

        String displayName = getDisplayName(uri);
        setStatus(
                getString(R.string.status_value_importing),
                getString(R.string.status_detail_reading_file, displayName)
        );
        runBackground(
                () -> importCoordinator.importContent(
                        displayName,
                        SubscriptionSourceType.FILE,
                        displayName,
                        readUriContent(uri)
                ),
                this::handleImportSuccess,
                getString(R.string.error_import_failed)
        );
    }

    private void handleImportSuccess(ImportResult result) {
        setStatus(
                getString(R.string.status_value_import_success, result.parsedNodeCount),
                getString(
                        R.string.status_detail_import_summary,
                        result.subscription.name,
                        result.addedNodeCount,
                        result.updatedNodeCount,
                        result.skippedLineCount
                )
        );
        Toast.makeText(
                this,
                getString(R.string.toast_import_success, result.parsedNodeCount),
                Toast.LENGTH_SHORT
        ).show();
        refreshState();
    }

    private void renderState(AppState state) {
        List<SubscriptionRecord> subscriptions = new ArrayList<>(state.subscriptions);
        subscriptions.sort((left, right) -> Long.compare(right.lastUpdatedAt, left.lastUpdatedAt));

        List<NodeRecord> nodes = new ArrayList<>(state.nodes);
        nodes.sort(Comparator.comparingLong(node -> -node.lastImportedAt));

        subscriptionsSummaryText.setText(
                getString(R.string.summary_subscriptions, subscriptions.size())
        );
        nodesSummaryText.setText(getString(R.string.summary_nodes, nodes.size()));

        renderSubscriptions(subscriptions);
        renderNodes(nodes);
    }

    private void renderSubscriptions(List<SubscriptionRecord> subscriptions) {
        subscriptionListContainer.removeAllViews();
        if (subscriptions.isEmpty()) {
            subscriptionListContainer.addView(createEmptyStateText(R.string.empty_subscriptions));
            return;
        }

        LayoutInflater inflater = LayoutInflater.from(this);
        for (SubscriptionRecord subscription : subscriptions) {
            View itemView = inflater.inflate(R.layout.item_subscription, subscriptionListContainer, false);
            TextView title = itemView.findViewById(R.id.subscriptionTitleText);
            TextView subtitle = itemView.findViewById(R.id.subscriptionSubtitleText);
            TextView meta = itemView.findViewById(R.id.subscriptionMetaText);

            title.setText(subscription.name);
            subtitle.setText(getString(
                    R.string.subscription_subtitle,
                    mapSourceTypeLabel(subscription.sourceType),
                    safeValue(subscription.sourceValue)
            ));
            meta.setText(getString(
                    R.string.subscription_meta,
                    subscription.nodeCount,
                    formatTimestamp(subscription.lastUpdatedAt)
            ));
            subscriptionListContainer.addView(itemView);
        }
    }

    private void renderNodes(List<NodeRecord> nodes) {
        nodeListContainer.removeAllViews();
        if (nodes.isEmpty()) {
            nodeListContainer.addView(createEmptyStateText(R.string.empty_nodes));
            return;
        }

        LayoutInflater inflater = LayoutInflater.from(this);
        for (NodeRecord node : nodes) {
            View itemView = inflater.inflate(R.layout.item_node, nodeListContainer, false);
            TextView title = itemView.findViewById(R.id.nodeTitleText);
            TextView subtitle = itemView.findViewById(R.id.nodeSubtitleText);
            TextView meta = itemView.findViewById(R.id.nodeMetaText);

            title.setText(node.displayName);
            subtitle.setText(getString(
                    R.string.node_subtitle,
                    node.protocol.toUpperCase(Locale.ROOT),
                    node.server,
                    node.port
            ));
            meta.setText(getString(
                    R.string.node_meta,
                    safeValue(node.sourceSubscriptionName),
                    safeValue(node.transport),
                    safeValue(node.security)
            ));
            nodeListContainer.addView(itemView);
        }
    }

    private TextView createEmptyStateText(int stringResId) {
        TextView textView = new TextView(this);
        textView.setText(stringResId);
        textView.setTextColor(getColor(R.color.brand_muted));
        textView.setTextSize(14f);
        return textView;
    }

    private LinearLayout createDialogForm() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int padding = dp(8);
        layout.setPadding(padding, padding, padding, 0);
        return layout;
    }

    private EditText createEditText(String hint, boolean multiline) {
        EditText editText = new EditText(this);
        editText.setHint(hint);
        editText.setTextColor(getColor(R.color.brand_ink));
        editText.setHintTextColor(getColor(R.color.brand_muted));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        params.bottomMargin = dp(12);
        editText.setLayoutParams(params);
        if (multiline) {
            editText.setMinLines(6);
            editText.setGravity(android.view.Gravity.TOP | android.view.Gravity.START);
            editText.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        }
        return editText;
    }

    private String fetchRemoteContent(String urlValue) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(urlValue).openConnection();
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(15000);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("User-Agent", "XrayAndroidClient/2026.04");
        int statusCode = connection.getResponseCode();
        if (statusCode < 200 || statusCode >= 300) {
            throw new IllegalStateException(getString(R.string.error_http_status, statusCode));
        }
        try (InputStream inputStream = connection.getInputStream()) {
            return readStream(inputStream);
        } finally {
            connection.disconnect();
        }
    }

    private String readUriContent(Uri uri) throws Exception {
        try (InputStream inputStream = getContentResolver().openInputStream(uri)) {
            if (inputStream == null) {
                throw new IllegalStateException(getString(R.string.error_file_open_failed));
            }
            return readStream(inputStream);
        }
    }

    private String readStream(InputStream inputStream) throws Exception {
        StringBuilder builder = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(inputStream, StandardCharsets.UTF_8)
        )) {
            String line;
            while ((line = reader.readLine()) != null) {
                builder.append(line).append('\n');
            }
        }
        return builder.toString();
    }

    private String getDisplayName(Uri uri) {
        Cursor cursor = getContentResolver().query(uri, null, null, null, null);
        if (cursor != null) {
            try {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0 && cursor.moveToFirst()) {
                    return safeValue(cursor.getString(index));
                }
            } finally {
                cursor.close();
            }
        }
        return getString(R.string.default_file_name);
    }

    private void setStatus(String title, String detail) {
        statusValueText.setText(title);
        statusDetailText.setText(detail);
    }

    private String formatTimestamp(long timestamp) {
        if (timestamp <= 0) {
            return getString(R.string.label_unknown);
        }
        return new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                .format(timestamp);
    }

    private String mapSourceTypeLabel(String sourceType) {
        if (SubscriptionSourceType.URL.value().equals(sourceType)) {
            return getString(R.string.label_source_url);
        }
        if (SubscriptionSourceType.FILE.value().equals(sourceType)) {
            return getString(R.string.label_source_file);
        }
        return getString(R.string.label_source_manual);
    }

    private String safeValue(String value) {
        if (value == null || value.trim().isEmpty()) {
            return getString(R.string.label_unknown);
        }
        return value.trim();
    }

    private int dp(int value) {
        return Math.round(getResources().getDisplayMetrics().density * value);
    }

    private <T> void runBackground(
            Callable<T> action,
            Consumer<T> onSuccess,
            String errorPrefix
    ) {
        ioExecutor.execute(() -> {
            try {
                T result = action.call();
                runOnUiThread(() -> onSuccess.accept(result));
            } catch (Exception exception) {
                runOnUiThread(() -> {
                    String message = exception.getMessage();
                    if (message == null || message.trim().isEmpty()) {
                        message = exception.getClass().getSimpleName();
                    }
                    setStatus(
                            getString(R.string.status_value_import_failed),
                            errorPrefix + ": " + message
                    );
                    Toast.makeText(this, errorPrefix + ": " + message, Toast.LENGTH_LONG).show();
                });
            }
        });
    }
}
