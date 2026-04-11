package cc.hifly.xrayandroid.data;

import org.junit.Test;

import java.io.File;

import cc.hifly.xrayandroid.model.AppState;
import cc.hifly.xrayandroid.model.NodeRecord;
import cc.hifly.xrayandroid.model.SubscriptionRecord;

import static org.junit.Assert.assertEquals;

public class AppStateStoreTest {
    @Test
    public void savesAndLoadsStateFile() throws Exception {
        File tempDir = new File(System.getProperty("java.io.tmpdir"), "xray-android-store-test");
        tempDir.mkdirs();
        File stateFile = new File(tempDir, "app-state.json");
        if (stateFile.exists()) {
            stateFile.delete();
        }

        AppState state = new AppState();
        SubscriptionRecord subscription = new SubscriptionRecord();
        subscription.id = "sub-1";
        subscription.name = "demo";
        subscription.sourceType = "manual";
        subscription.sourceValue = "manual";
        subscription.nodeCount = 1;
        state.subscriptions.add(subscription);

        NodeRecord node = new NodeRecord();
        node.id = "node-1";
        node.displayName = "demo-node";
        node.rawUri = "vless://demo@example.com:443#demo";
        node.protocol = "vless";
        node.server = "example.com";
        node.port = 443;
        state.nodes.add(node);

        AppStateStore store = new AppStateStore(stateFile);
        store.save(state);

        AppState loaded = store.load();
        assertEquals(1, loaded.subscriptions.size());
        assertEquals(1, loaded.nodes.size());
        assertEquals("demo", loaded.subscriptions.get(0).name);
        assertEquals("demo-node", loaded.nodes.get(0).displayName);
    }
}
