package cc.hifly.xrayandroid.runtime;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import org.junit.Test;

import cc.hifly.xrayandroid.model.NodeRecord;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class XrayConfigBuilderTest {
    private static final Gson GSON = new Gson();

    @Test
    public void buildsVlessTunConfig() {
        NodeRecord node = new NodeRecord();
        node.rawUri = "vless://6202b230-417c-4d8e-b624-0f71afa9c75d@185.243.112.61:2053?type=ws&security=tls&host=edge.example.com&path=%2Fproxy&sni=edge.example.com#demo";

        String config = new XrayConfigBuilder().build(node, 1500);
        JsonObject json = GSON.fromJson(config, JsonObject.class);
        JsonObject inbound = json.getAsJsonArray("inbounds").get(0).getAsJsonObject();
        JsonObject outbound = json.getAsJsonArray("outbounds").get(0).getAsJsonObject();

        assertEquals("tun", inbound.get("protocol").getAsString());
        assertEquals("vless", outbound.get("protocol").getAsString());
        assertEquals("ws", outbound.getAsJsonObject("streamSettings").get("network").getAsString());
        assertEquals("tls", outbound.getAsJsonObject("streamSettings").get("security").getAsString());
    }

    @Test
    public void buildsAnyTlsOutbound() {
        NodeRecord node = new NodeRecord();
        node.rawUri = "anytls://dongtaiwang.com@45.221.98.14:59901?alpn=h2&allowInsecure=1&sni=45.221.98.14#US_21";

        String config = new XrayConfigBuilder().build(node, 1500);
        JsonObject outbound = GSON.fromJson(config, JsonObject.class)
                .getAsJsonArray("outbounds")
                .get(0)
                .getAsJsonObject();

        assertEquals("anytls", outbound.get("protocol").getAsString());
        assertTrue(outbound.getAsJsonObject("streamSettings")
                .getAsJsonObject("tlsSettings")
                .get("allowInsecure")
                .getAsBoolean());
    }

    @Test
    public void buildsShadowsocksPluginWsOutbound() {
        NodeRecord node = new NodeRecord();
        node.rawUri = "ss://YWVzLTI1Ni1nY206cGFzc3dvcmQ=@example.com:443?plugin=v2ray-plugin%3Btls%3Bobfs%3Dwebsocket%3Bobfs-host%3Dcdn.example.com%3Bpath%3D%2Fws#ss";

        String config = new XrayConfigBuilder().build(node, 1500);
        JsonObject outbound = GSON.fromJson(config, JsonObject.class)
                .getAsJsonArray("outbounds")
                .get(0)
                .getAsJsonObject();

        assertEquals("shadowsocks", outbound.get("protocol").getAsString());
        assertEquals("ws", outbound.getAsJsonObject("streamSettings").get("network").getAsString());
        assertEquals("tls", outbound.getAsJsonObject("streamSettings").get("security").getAsString());
    }
}
