package cc.hifly.xrayandroid.data;

import cc.hifly.xrayandroid.model.AppState;
import cc.hifly.xrayandroid.model.NodeRecord;
import cc.hifly.xrayandroid.model.SubscriptionRecord;
import cc.hifly.xrayandroid.model.SubscriptionSourceType;
import cc.hifly.xrayandroid.parser.NodeUriParser;
import cc.hifly.xrayandroid.parser.ParseBatch;

import java.net.URI;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public class ImportCoordinator {
    private final AppStateStore store;
    private final NodeUriParser parser;

    public ImportCoordinator(AppStateStore store, NodeUriParser parser) {
        this.store = store;
        this.parser = parser;
    }

    public synchronized AppState loadSnapshot() {
        return store.load();
    }

    public synchronized ImportResult importContent(
            String requestedName,
            SubscriptionSourceType sourceType,
            String sourceValue,
            String content
    ) throws Exception {
        long now = System.currentTimeMillis();
        AppState state = store.load();

        SubscriptionRecord subscription = buildSubscriptionRecord(
                requestedName,
                sourceType,
                sourceValue,
                now
        );

        ParseBatch batch = parser.parse(content, subscription, now);
        if (batch.nodes.isEmpty()) {
            throw new IllegalArgumentException("未识别到受支持的节点 URI");
        }
        Map<String, NodeRecord> existingByUri = new HashMap<>();
        for (NodeRecord node : state.nodes) {
            existingByUri.put(node.rawUri, node);
        }

        int added = 0;
        int updated = 0;
        for (NodeRecord parsedNode : batch.nodes) {
            NodeRecord existingNode = existingByUri.get(parsedNode.rawUri);
            if (existingNode == null) {
                state.nodes.add(0, parsedNode);
                existingByUri.put(parsedNode.rawUri, parsedNode);
                added += 1;
            } else {
                existingNode.displayName = parsedNode.displayName;
                existingNode.protocol = parsedNode.protocol;
                existingNode.server = parsedNode.server;
                existingNode.port = parsedNode.port;
                existingNode.credential = parsedNode.credential;
                existingNode.transport = parsedNode.transport;
                existingNode.security = parsedNode.security;
                existingNode.sourceSubscriptionId = parsedNode.sourceSubscriptionId;
                existingNode.sourceSubscriptionName = parsedNode.sourceSubscriptionName;
                existingNode.sourceType = parsedNode.sourceType;
                existingNode.lastImportedAt = now;
                existingNode.extras.clear();
                existingNode.extras.putAll(parsedNode.extras);
                updated += 1;
            }
        }

        subscription.nodeCount = added + updated;
        state.subscriptions.add(0, subscription);
        store.save(state);

        return new ImportResult(
                subscription,
                added,
                updated,
                batch.skippedLineCount,
                batch.nodes.size()
        );
    }

    private SubscriptionRecord buildSubscriptionRecord(
            String requestedName,
            SubscriptionSourceType sourceType,
            String sourceValue,
            long now
    ) {
        SubscriptionRecord subscription = new SubscriptionRecord();
        subscription.id = UUID.randomUUID().toString();
        subscription.name = resolveSubscriptionName(requestedName, sourceType, sourceValue, now);
        subscription.sourceType = sourceType.value();
        subscription.sourceValue = sourceValue == null ? "" : sourceValue.trim();
        subscription.importedAt = now;
        subscription.lastUpdatedAt = now;
        subscription.nodeCount = 0;
        return subscription;
    }

    private String resolveSubscriptionName(
            String requestedName,
            SubscriptionSourceType sourceType,
            String sourceValue,
            long now
    ) {
        if (requestedName != null && !requestedName.trim().isEmpty()) {
            return requestedName.trim();
        }

        switch (sourceType) {
            case URL:
                try {
                    URI uri = new URI(sourceValue);
                    if (uri.getHost() != null && !uri.getHost().trim().isEmpty()) {
                        return uri.getHost().trim();
                    }
                } catch (Exception ignored) {
                    // fallback below
                }
                return "URL 导入";
            case FILE:
                if (sourceValue != null && !sourceValue.trim().isEmpty()) {
                    return sourceValue.trim();
                }
                return "文件导入";
            case MANUAL:
            default:
                return "手工导入 " + new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
                        .format(new Date(now));
        }
    }
}
