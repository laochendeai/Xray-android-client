package cc.hifly.xrayandroid.runtime;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import cc.hifly.xrayandroid.model.NodeRecord;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class XrayConfigBuilder {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    static final String PROBE_HTTP_INBOUND_TAG = "probe-http-in";
    static final int PROBE_HTTP_INBOUND_PORT = 10809;
    private static final String TUN_INBOUND_TAG = "tun-in";

    public String build(NodeRecord node, int mtu) {
        return build(node, mtu, true);
    }

    public String buildPreflight(NodeRecord node) {
        return build(node, 0, false);
    }

    private String build(NodeRecord node, int mtu, boolean includeTun) {
        ShareLinkRequest request = parseShareLink(node.rawUri);

        Map<String, Object> config = new LinkedHashMap<>();
        config.put("log", mapOf("loglevel", "warning"));
        config.put("dns", mapOf(
                "servers", List.of("1.1.1.1", "8.8.8.8", "9.9.9.9"),
                "queryStrategy", "UseIP"
        ));
        List<Object> inbounds = new ArrayList<>();
        if (includeTun) {
            inbounds.add(mapOf(
                    "tag", TUN_INBOUND_TAG,
                    "port", 0,
                    "protocol", "tun",
                    "settings", mapOf("name", "xray0", "MTU", mtu),
                    "sniffing", mapOf(
                            "enabled", true,
                            "destOverride", List.of("http", "tls", "quic")
                    )
            ));
        }
        inbounds.add(mapOf(
                "tag", PROBE_HTTP_INBOUND_TAG,
                "listen", "127.0.0.1",
                "port", PROBE_HTTP_INBOUND_PORT,
                "protocol", "http",
                "settings", new LinkedHashMap<>()
        ));
        config.put("inbounds", inbounds);

        List<Object> outbounds = new ArrayList<>();
        outbounds.add(buildOutbound(request));
        outbounds.add(mapOf("tag", "direct", "protocol", "freedom", "settings", new LinkedHashMap<>()));
        outbounds.add(mapOf("tag", "block", "protocol", "blackhole", "settings", new LinkedHashMap<>()));
        outbounds.add(mapOf("tag", "dns-out", "protocol", "dns", "settings", mapOf("nonIPQuery", "skip")));
        config.put("outbounds", outbounds);

        List<Object> rules = new ArrayList<>();
        if (includeTun) {
            rules.add(mapOf(
                    "type", "field",
                    "inboundTag", List.of(TUN_INBOUND_TAG),
                    "port", "53",
                    "outboundTag", "dns-out"
            ));
        }
        rules.add(mapOf(
                "type", "field",
                "domain", List.of("full:localhost", "domain:local"),
                "outboundTag", "direct"
        ));
        rules.add(mapOf(
                "type", "field",
                "ip", List.of(
                        "127.0.0.0/8",
                        "10.0.0.0/8",
                        "172.16.0.0/12",
                        "192.168.0.0/16",
                        "169.254.0.0/16",
                        "::1/128",
                        "fc00::/7",
                        "fe80::/10"
                ),
                "outboundTag", "direct"
        ));
        rules.add(mapOf(
                "type", "field",
                "network", "tcp,udp",
                "outboundTag", "proxy"
        ));
        config.put("routing", mapOf(
                "domainStrategy", "IPIfNonMatch",
                "rules", rules
        ));
        return GSON.toJson(config);
    }

    private Map<String, Object> buildOutbound(ShareLinkRequest request) {
        Map<String, Object> outbound = new LinkedHashMap<>();
        outbound.put("tag", "proxy");
        switch (request.protocol) {
            case "vless":
                outbound.put("protocol", "vless");
                outbound.put("settings", mapOf(
                        "vnext", List.of(mapOf(
                                "address", request.address,
                                "port", request.port,
                                "users", List.of(mapOf(
                                        "id", request.uuid,
                                        "encryption", firstNonEmpty(request.security, "none"),
                                        "flow", value(request.flow)
                                ))
                        ))
                ));
                break;
            case "vmess":
                outbound.put("protocol", "vmess");
                outbound.put("settings", mapOf(
                        "vnext", List.of(mapOf(
                                "address", request.address,
                                "port", request.port,
                                "users", List.of(mapOf(
                                        "id", request.uuid,
                                        "alterId", parseInt(firstNonEmpty(request.alterId, "0"), 0),
                                        "security", firstNonEmpty(request.security, "auto")
                                ))
                        ))
                ));
                break;
            case "trojan":
                outbound.put("protocol", "trojan");
                outbound.put("settings", mapOf(
                        "servers", List.of(mapOf(
                                "address", request.address,
                                "port", request.port,
                                "password", request.password
                        ))
                ));
                break;
            case "shadowsocks":
                outbound.put("protocol", "shadowsocks");
                outbound.put("settings", mapOf(
                        "servers", List.of(mapOf(
                                "address", request.address,
                                "port", request.port,
                                "method", firstNonEmpty(request.security, "aes-256-gcm"),
                                "password", request.password
                        ))
                ));
                break;
            case "anytls":
                outbound.put("protocol", "anytls");
                outbound.put("settings", mapOf(
                        "address", request.address,
                        "port", request.port,
                        "password", request.password
                ));
                break;
            default:
                throw new IllegalArgumentException("unsupported protocol: " + request.protocol);
        }

        Map<String, Object> stream = buildStreamSettings(request);
        if (!stream.isEmpty()) {
            outbound.put("streamSettings", stream);
        }
        return outbound;
    }

    private Map<String, Object> buildStreamSettings(ShareLinkRequest request) {
        Map<String, Object> stream = new LinkedHashMap<>();
        String network = firstNonEmpty(request.type, "tcp");
        stream.put("network", network);

        switch (network) {
            case "ws":
                Map<String, Object> ws = new LinkedHashMap<>();
                if (!isBlank(request.path)) {
                    ws.put("path", request.path);
                }
                if (!isBlank(request.host)) {
                    ws.put("headers", mapOf("Host", request.host));
                }
                stream.put("wsSettings", ws);
                break;
            case "grpc":
                Map<String, Object> grpc = new LinkedHashMap<>();
                if (!isBlank(request.path)) {
                    grpc.put("serviceName", request.path);
                }
                stream.put("grpcSettings", grpc);
                break;
            case "http":
            case "h2":
                Map<String, Object> http = new LinkedHashMap<>();
                if (!isBlank(request.path)) {
                    http.put("path", request.path);
                }
                if (!isBlank(request.host)) {
                    http.put("host", List.of(request.host));
                }
                stream.put("httpSettings", http);
                break;
            case "httpupgrade":
                Map<String, Object> hu = new LinkedHashMap<>();
                if (!isBlank(request.path)) {
                    hu.put("path", request.path);
                }
                if (!isBlank(request.host)) {
                    hu.put("host", request.host);
                }
                stream.put("httpupgradeSettings", hu);
                break;
            case "splithttp":
                Map<String, Object> sh = new LinkedHashMap<>();
                if (!isBlank(request.path)) {
                    sh.put("path", request.path);
                }
                if (!isBlank(request.host)) {
                    sh.put("host", request.host);
                }
                stream.put("splithttpSettings", sh);
                break;
            case "kcp":
            case "mkcp":
                if (!isBlank(request.path)) {
                    stream.put("kcpSettings", mapOf("seed", request.path));
                }
                break;
            case "quic":
                Map<String, Object> quic = new LinkedHashMap<>();
                if (!isBlank(request.path)) {
                    quic.put("key", request.path);
                }
                if (!isBlank(request.host)) {
                    quic.put("security", request.host);
                }
                stream.put("quicSettings", quic);
                break;
            default:
                break;
        }

        String security = normalizeTransportSecurityValue(request.tls, "");
        if ("reality".equals(security)) {
            stream.put("security", "reality");
            Map<String, Object> reality = new LinkedHashMap<>();
            String serverName = firstNonEmpty(request.sni, request.host);
            if (!isBlank(serverName)) {
                reality.put("serverName", serverName);
            }
            if (!isBlank(request.fingerprint)) {
                reality.put("fingerprint", request.fingerprint);
            }
            if (!isBlank(request.publicKey)) {
                reality.put("publicKey", request.publicKey);
            }
            if (!isBlank(request.shortId)) {
                reality.put("shortId", request.shortId);
            }
            if (!isBlank(request.spiderX)) {
                reality.put("spiderX", request.spiderX);
            }
            stream.put("realitySettings", reality);
        } else if ("tls".equals(security) || "anytls".equals(request.protocol) || "trojan".equals(request.protocol)) {
            stream.put("security", "tls");
            Map<String, Object> tls = new LinkedHashMap<>();
            String serverName = firstNonEmpty(request.sni, request.host, looksLikeIp(request.address) ? "" : request.address);
            if (!isBlank(serverName)) {
                tls.put("serverName", serverName);
            }
            if (!isBlank(request.alpn)) {
                tls.put("alpn", splitCsv(request.alpn));
            }
            if (!isBlank(request.fingerprint)) {
                tls.put("fingerprint", request.fingerprint);
            }
            if (request.allowInsecure) {
                tls.put("allowInsecure", true);
            }
            stream.put("tlsSettings", tls);
        }
        return stream;
    }

    private ShareLinkRequest parseShareLink(String rawUri) {
        String trimmed = trimBom(rawUri);
        int schemeIndex = trimmed.indexOf("://");
        if (schemeIndex <= 0) {
            throw new IllegalArgumentException("unsupported share link: missing scheme");
        }
        String scheme = trimmed.substring(0, schemeIndex).toLowerCase(Locale.ROOT);
        switch (scheme) {
            case "vless":
                return parseVlessUri(trimmed);
            case "vmess":
                return parseVmessUri(trimmed);
            case "trojan":
                return parseTrojanUri(trimmed);
            case "ss":
                return parseShadowsocksUri(trimmed);
            case "anytls":
                return parseAnyTlsUri(trimmed);
            default:
                throw new IllegalArgumentException("unsupported protocol: " + scheme);
        }
    }

    private ShareLinkRequest parseVlessUri(String uriText) {
        URI uri = URI.create(uriText);
        if (uri.getHost() == null || uri.getPort() <= 0) {
            throw new IllegalArgumentException("invalid VLESS URI");
        }
        Map<String, String> params = parseQuery(uri.getRawQuery());
        ShareLinkRequest request = new ShareLinkRequest();
        request.protocol = "vless";
        request.uuid = decodeComponent(uri.getRawUserInfo());
        request.address = uri.getHost();
        request.port = uri.getPort();
        request.type = firstNonEmpty(params.get("type"), "tcp");
        request.security = firstNonEmpty(params.get("encryption"), "none");
        request.tls = normalizeTransportSecurityValue(params.get("security"), params.get("tls"));
        request.allowInsecure = parseBoolish(params.get("skip-cert-verify")) || parseBoolish(params.get("allowInsecure"));
        request.flow = params.get("flow");
        request.sni = params.get("sni");
        request.alpn = params.get("alpn");
        request.fingerprint = params.get("fp");
        request.host = params.get("host");
        request.path = params.get("path");
        request.publicKey = firstNonEmpty(params.get("pbk"), params.get("pb"));
        request.shortId = params.get("sid");
        request.spiderX = params.get("spx");
        return request;
    }

    private ShareLinkRequest parseTrojanUri(String uriText) {
        URI uri = URI.create(uriText);
        if (uri.getHost() == null || uri.getPort() <= 0) {
            throw new IllegalArgumentException("invalid Trojan URI");
        }
        Map<String, String> params = parseQuery(uri.getRawQuery());
        ShareLinkRequest request = new ShareLinkRequest();
        request.protocol = "trojan";
        request.password = decodeComponent(uri.getRawUserInfo());
        request.address = uri.getHost();
        request.port = uri.getPort();
        request.type = firstNonEmpty(params.get("type"), "tcp");
        request.tls = firstNonEmpty(normalizeTransportSecurityValue(params.get("security"), params.get("tls")), "tls");
        request.allowInsecure = parseBoolish(params.get("skip-cert-verify")) || parseBoolish(params.get("allowInsecure"));
        request.sni = params.get("sni");
        request.alpn = params.get("alpn");
        request.fingerprint = params.get("fp");
        request.host = params.get("host");
        request.path = params.get("path");
        request.publicKey = firstNonEmpty(params.get("pbk"), params.get("pb"));
        request.shortId = params.get("sid");
        return request;
    }

    private ShareLinkRequest parseAnyTlsUri(String uriText) {
        String body = uriText.substring("anytls://".length());
        int fragmentIndex = body.indexOf('#');
        if (fragmentIndex >= 0) {
            body = body.substring(0, fragmentIndex);
        }
        int queryIndex = body.indexOf('?');
        String query = queryIndex >= 0 ? body.substring(queryIndex + 1) : "";
        String hostPart = queryIndex >= 0 ? body.substring(0, queryIndex) : body;

        String password = "";
        int atIndex = hostPart.indexOf('@');
        if (atIndex >= 0) {
            password = decodeComponent(hostPart.substring(0, atIndex));
            hostPart = hostPart.substring(atIndex + 1);
        }
        hostPart = hostPart.replaceAll("/+$", "");
        HostPort target = splitHostPort(hostPart, 443);

        Map<String, String> params = parseQuery(query);
        ShareLinkRequest request = new ShareLinkRequest();
        request.protocol = "anytls";
        request.password = password;
        request.address = target.host;
        request.port = target.port;
        request.type = "tcp";
        request.tls = "tls";
        request.allowInsecure = parseBoolish(params.get("insecure"))
                || parseBoolish(params.get("allow_insecure"))
                || parseBoolish(params.get("allowInsecure"));
        request.sni = params.get("sni");
        request.alpn = params.get("alpn");
        return request;
    }

    private ShareLinkRequest parseVmessUri(String uriText) {
        String encoded = uriText.substring("vmess://".length()).trim();
        String decoded = decodeBase64Text(encoded);
        if (decoded == null || decoded.isEmpty()) {
            throw new IllegalArgumentException("invalid VMess URI");
        }
        Map<?, ?> json = GSON.fromJson(decoded, Map.class);
        ShareLinkRequest request = new ShareLinkRequest();
        request.protocol = "vmess";
        request.uuid = readString(json, "id");
        request.address = readString(json, "add");
        request.port = parseInt(readString(json, "port"), 0);
        request.security = firstNonEmpty(readString(json, "scy"), "auto");
        request.type = firstNonEmpty(readString(json, "net"), "tcp");
        request.host = readString(json, "host");
        request.path = readString(json, "path");
        request.tls = normalizeTransportSecurityValue("", readString(json, "tls"));
        request.sni = readString(json, "sni");
        request.alpn = readString(json, "alpn");
        request.fingerprint = readString(json, "fp");
        request.alterId = firstNonEmpty(readString(json, "aid"), "0");
        return request;
    }

    private ShareLinkRequest parseShadowsocksUri(String uriText) {
        String body = uriText.substring("ss://".length());
        int fragmentIndex = body.indexOf('#');
        if (fragmentIndex >= 0) {
            body = body.substring(0, fragmentIndex);
        }

        String query = "";
        int queryIndex = body.indexOf('?');
        if (queryIndex >= 0) {
            query = body.substring(queryIndex + 1);
            body = body.substring(0, queryIndex);
        }

        String userInfo;
        String serverPart;
        int atIndex = body.lastIndexOf('@');
        if (atIndex >= 0) {
            userInfo = body.substring(0, atIndex);
            serverPart = body.substring(atIndex + 1).replaceAll("/+$", "");
        } else {
            String decoded = decodeBase64Text(body);
            if (decoded == null) {
                throw new IllegalArgumentException("invalid SS URI");
            }
            int decodedAt = decoded.lastIndexOf('@');
            if (decodedAt < 0) {
                throw new IllegalArgumentException("invalid SS URI");
            }
            userInfo = decoded.substring(0, decodedAt);
            serverPart = decoded.substring(decodedAt + 1);
        }

        String[] credentials = parseShadowsocksUserInfo(userInfo);
        HostPort target = splitHostPort(serverPart, 0);
        ShareLinkRequest request = new ShareLinkRequest();
        request.protocol = "shadowsocks";
        request.security = credentials[0];
        request.password = credentials[1];
        request.address = target.host;
        request.port = target.port;

        Map<String, String> params = parseQuery(query);
        if (!isBlank(params.get("plugin"))) {
            applyShadowsocksPlugin(request, params.get("plugin"));
        }
        return request;
    }

    private void applyShadowsocksPlugin(ShareLinkRequest request, String pluginValue) {
        String[] parts = pluginValue.split(";");
        if (parts.length == 0) {
            return;
        }
        String pluginName = parts[0].trim().toLowerCase(Locale.ROOT);
        if (!pluginName.isEmpty() && !"v2ray-plugin".equals(pluginName)) {
            throw new IllegalArgumentException("unsupported SS plugin: " + pluginName);
        }

        for (int index = 1; index < parts.length; index++) {
            String part = parts[index].trim();
            String lower = part.toLowerCase(Locale.ROOT);
            if (lower.isEmpty()) {
                continue;
            }
            if ("tls".equals(lower)) {
                request.tls = "tls";
                continue;
            }
            if (lower.startsWith("obfs=")) {
                String obfs = lower.substring("obfs=".length()).trim();
                if (!"websocket".equals(obfs) && !"ws".equals(obfs)) {
                    throw new IllegalArgumentException("unsupported SS plugin obfs mode: " + obfs);
                }
                request.type = "ws";
                continue;
            }
            if (lower.startsWith("obfs-host=")) {
                request.host = part.substring("obfs-host=".length());
                continue;
            }
            if (lower.startsWith("host=")) {
                request.host = part.substring("host=".length());
                continue;
            }
            if (lower.startsWith("path=")) {
                request.path = part.substring("path=".length());
            }
        }
        if ("ws".equals(request.type) && isBlank(request.path)) {
            request.path = "/";
        }
    }

    private String[] parseShadowsocksUserInfo(String userInfo) {
        String plain = userInfo;
        if (!userInfo.contains(":")) {
            String decoded = decodeBase64Text(userInfo);
            if (decoded != null) {
                plain = decoded;
            }
        } else {
            plain = decodeComponent(userInfo);
        }
        String[] parts = plain.split(":", 2);
        if (parts.length != 2) {
            throw new IllegalArgumentException("invalid SS URI user info");
        }
        return parts;
    }

    private HostPort splitHostPort(String text, int defaultPort) {
        String normalized = text.trim();
        if (normalized.startsWith("[") && normalized.contains("]:")) {
            int end = normalized.indexOf("]:");
            return new HostPort(normalized.substring(1, end), parseInt(normalized.substring(end + 2), defaultPort));
        }
        int lastColon = normalized.lastIndexOf(':');
        if (lastColon <= 0) {
            if (defaultPort > 0) {
                return new HostPort(normalized, defaultPort);
            }
            throw new IllegalArgumentException("missing host port");
        }
        return new HostPort(
                normalized.substring(0, lastColon),
                parseInt(normalized.substring(lastColon + 1), defaultPort)
        );
    }

    private Map<String, String> parseQuery(String rawQuery) {
        Map<String, String> query = new LinkedHashMap<>();
        if (isBlank(rawQuery)) {
            return query;
        }
        for (String pair : rawQuery.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int separator = pair.indexOf('=');
            if (separator < 0) {
                query.put(decodeComponent(pair), "");
            } else {
                query.put(
                        decodeComponent(pair.substring(0, separator)),
                        decodeComponent(pair.substring(separator + 1))
                );
            }
        }
        return query;
    }

    private String decodeComponent(String value) {
        if (value == null) {
            return "";
        }
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private String decodeBase64Text(String raw) {
        String compact = raw == null ? "" : raw.replaceAll("\\s+", "");
        if (compact.isEmpty()) {
            return null;
        }
        String[] candidates = new String[]{compact, compact + "=", compact + "==", compact + "==="};
        for (String candidate : candidates) {
            try {
                return new String(Base64.getDecoder().decode(candidate), StandardCharsets.UTF_8);
            } catch (IllegalArgumentException ignored) {
                // keep trying
            }
            try {
                return new String(Base64.getUrlDecoder().decode(candidate), StandardCharsets.UTF_8);
            } catch (IllegalArgumentException ignored) {
                // keep trying
            }
        }
        return null;
    }

    private String normalizeTransportSecurityValue(String securityValue, String tlsValue) {
        String security = value(securityValue).trim().toLowerCase(Locale.ROOT);
        if ("tls".equals(security) || "reality".equals(security) || "none".equals(security)) {
            return security;
        }
        String tls = value(tlsValue).trim().toLowerCase(Locale.ROOT);
        if ("tls".equals(tls) || "true".equals(tls) || "1".equals(tls)) {
            return "tls";
        }
        if ("reality".equals(tls)) {
            return "reality";
        }
        if ("none".equals(tls) || "false".equals(tls) || "0".equals(tls)) {
            return "none";
        }
        return "";
    }

    private List<String> splitCsv(String value) {
        List<String> result = new ArrayList<>();
        for (String item : value.split(",")) {
            String trimmed = item.trim();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }
        return result;
    }

    private String readString(Map<?, ?> map, String key) {
        Object value = map.get(key);
        if (value == null) {
            return "";
        }
        if (value instanceof Number) {
            return String.valueOf(((Number) value).intValue());
        }
        return String.valueOf(value);
    }

    private boolean parseBoolish(String value) {
        if (value == null) {
            return false;
        }
        switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "1":
            case "true":
            case "yes":
            case "on":
                return true;
            default:
                return false;
        }
    }

    private int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value);
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private boolean looksLikeIp(String host) {
        return host.contains(":") || host.matches("\\d+\\.\\d+\\.\\d+\\.\\d+");
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private String firstNonEmpty(String... values) {
        for (String value : values) {
            if (!isBlank(value)) {
                return value.trim();
            }
        }
        return "";
    }

    private String value(String value) {
        return value == null ? "" : value;
    }

    private String trimBom(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\uFEFF", "").trim();
    }

    private Map<String, Object> mapOf(Object... entries) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int index = 0; index < entries.length; index += 2) {
            map.put(String.valueOf(entries[index]), entries[index + 1]);
        }
        return map;
    }

    private static final class HostPort {
        private final String host;
        private final int port;

        private HostPort(String host, int port) {
            this.host = host;
            this.port = port;
        }
    }

    private static final class ShareLinkRequest {
        private String protocol;
        private String uuid;
        private String password;
        private String address;
        private int port;
        private String type;
        private String security;
        private String tls;
        private boolean allowInsecure;
        private String flow;
        private String sni;
        private String alpn;
        private String fingerprint;
        private String host;
        private String path;
        private String publicKey;
        private String shortId;
        private String spiderX;
        private String alterId;
    }
}
