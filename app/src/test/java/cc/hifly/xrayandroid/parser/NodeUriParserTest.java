package cc.hifly.xrayandroid.parser;

import org.junit.Test;

import cc.hifly.xrayandroid.model.SubscriptionRecord;
import cc.hifly.xrayandroid.model.SubscriptionSourceType;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class NodeUriParserTest {
    private final NodeUriParser parser = new NodeUriParser();

    @Test
    public void parsesAnytlsAndVlessAndSkipsNoise() {
        SubscriptionRecord subscription = buildSubscription("test-import");
        String content = ""
                + "anytls://dongtaiwang.com@45.221.98.14:59901?security=none&type=tcp#US_17\n"
                + "\n"
                + "not-a-node-line\n"
                + "vless://123e4567-e89b-12d3-a456-426614174000@example.com:443?security=tls&type=ws#Edge\n";

        ParseBatch batch = parser.parse(content, subscription, 100L);

        assertEquals(2, batch.nodes.size());
        assertEquals("anytls", batch.nodes.get(0).protocol);
        assertEquals("US_17", batch.nodes.get(0).displayName);
        assertEquals("vless", batch.nodes.get(1).protocol);
        assertEquals(1, batch.skippedLineCount);
    }

    @Test
    public void parsesVmessBase64Payload() {
        SubscriptionRecord subscription = buildSubscription("vmess-import");
        String content = "vmess://eyJhZGQiOiJ2bWVzcy5leGFtcGxlLmNvbSIsInBvcnQiOiI0NDMiLCJpZCI6IjEyM2U0NTY3LWU4OWItMTJkMy1hNDU2LTQyNjYxNDE3NDAwMCIsInBzIjoiVm1lc3MgTm9kZSIsIm5ldCI6IndzIiwidGxzIjoidGxzIn0=";

        ParseBatch batch = parser.parse(content, subscription, 200L);

        assertEquals(1, batch.nodes.size());
        assertEquals("vmess", batch.nodes.get(0).protocol);
        assertEquals("Vmess Node", batch.nodes.get(0).displayName);
        assertEquals("ws", batch.nodes.get(0).transport);
        assertEquals("tls", batch.nodes.get(0).security);
    }

    @Test
    public void decodesBase64SubscriptionEnvelope() {
        SubscriptionRecord subscription = buildSubscription("base64-subscription");
        String wrapped = "dmxlc3M6Ly8xMjNlNDU2Ny1lODliLTEyZDMtYTQ1Ni00MjY2MTQxNzQwMDBAZXhhbXBsZS5jb206NDQzP3NlY3VyaXR5PXRscyNOb2RlMQ0KdHJvamFuOi8vc2VjcmV0QGV4YW1wbGUubmV0OjQ0Mz9zZWN1cml0eT10bHMjTm9kZTI=";

        ParseBatch batch = parser.parse(wrapped, subscription, 300L);

        assertEquals(2, batch.nodes.size());
        assertTrue(batch.skippedSamples.isEmpty());
        assertEquals("Node1", batch.nodes.get(0).displayName);
        assertEquals("Node2", batch.nodes.get(1).displayName);
    }

    private SubscriptionRecord buildSubscription(String name) {
        SubscriptionRecord record = new SubscriptionRecord();
        record.id = "sub-" + name;
        record.name = name;
        record.sourceType = SubscriptionSourceType.MANUAL.value();
        record.sourceValue = "manual";
        return record;
    }
}
