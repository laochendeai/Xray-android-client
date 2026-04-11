package cc.hifly.xrayandroid.model;

import java.util.LinkedHashMap;
import java.util.Map;

public class NodeRecord {
    public String id;
    public String protocol;
    public String displayName;
    public String server;
    public int port;
    public String credential;
    public String transport;
    public String security;
    public String rawUri;
    public String sourceSubscriptionId;
    public String sourceSubscriptionName;
    public String sourceType;
    public long importedAt;
    public long lastImportedAt;
    public Map<String, String> extras = new LinkedHashMap<>();
}
