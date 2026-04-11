package cc.hifly.xrayandroid.parser;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import cc.hifly.xrayandroid.model.NodeRecord;
import cc.hifly.xrayandroid.model.SubscriptionRecord;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public class NodeUriParser {
    private static final Gson GSON = new Gson();

    public ParseBatch parse(String content, SubscriptionRecord subscription, long importedAt) {
        ParseBatch batch = new ParseBatch();
        String normalized = unwrapSubscriptionEnvelope(content);
        if (normalized.trim().isEmpty()) {
            return batch;
        }

        String[] lines = normalized.split("\\r?\\n");
        for (String rawLine : lines) {
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }

            NodeRecord node = parseLine(line, subscription, importedAt);
            if (node == null) {
                batch.skippedLineCount += 1;
                if (batch.skippedSamples.size() < 3) {
                    batch.skippedSamples.add(line);
                }
                continue;
            }

            batch.nodes.add(node);
        }

        return batch;
    }

    private NodeRecord parseLine(String line, SubscriptionRecord subscription, long importedAt) {
        String scheme = extractScheme(line);
        if (scheme == null) {
            return null;
        }

        switch (scheme) {
            case "vless":
            case "trojan":
            case "anytls":
                return parseStandardUri(line, scheme, subscription, importedAt);
            case "vmess":
                return parseVmessUri(line, subscription, importedAt);
            case "ss":
                return parseShadowsocksUri(line, subscription, importedAt);
            default:
                return null;
        }
    }

    private NodeRecord parseStandardUri(
            String rawUri,
            String protocol,
            SubscriptionRecord subscription,
            long importedAt
    ) {
        try {
            URI uri = new URI(rawUri);
            if (uri.getHost() == null || uri.getPort() <= 0) {
                return null;
            }

            Map<String, String> query = parseQuery(uri.getRawQuery());
            String displayName = decodeComponent(uri.getRawFragment());
            if (displayName.isEmpty()) {
                displayName = protocol.toUpperCase(Locale.ROOT) + " " + uri.getHost();
            }

            Map<String, String> extras = new LinkedHashMap<>(query);
            String transport = firstNonBlank(query.get("type"), query.get("net"), "tcp");
            String security = firstNonBlank(query.get("security"), query.get("tls"), "none");

            return buildNodeRecord(
                    protocol,
                    displayName,
                    uri.getHost(),
                    uri.getPort(),
                    decodeComponent(uri.getRawUserInfo()),
                    transport,
                    security,
                    rawUri,
                    subscription,
                    importedAt,
                    extras
            );
        } catch (Exception ignored) {
            return null;
        }
    }

    private NodeRecord parseVmessUri(String rawUri, SubscriptionRecord subscription, long importedAt) {
        try {
            String encoded = rawUri.substring("vmess://".length()).trim();
            String decoded = tryDecodeBase64Text(encoded);
            if (decoded == null || decoded.trim().isEmpty()) {
                return null;
            }

            JsonObject json = GSON.fromJson(decoded, JsonObject.class);
            String server = getJsonString(json, "add");
            int port = parsePort(getJsonString(json, "port"));
            if (server.isEmpty() || port <= 0) {
                return null;
            }

            Map<String, String> extras = new LinkedHashMap<>();
            putIfPresent(extras, "host", getJsonString(json, "host"));
            putIfPresent(extras, "path", getJsonString(json, "path"));
            putIfPresent(extras, "sni", getJsonString(json, "sni"));
            putIfPresent(extras, "aid", getJsonString(json, "aid"));

            String displayName = firstNonBlank(
                    getJsonString(json, "ps"),
                    "VMESS " + server
            );
            String transport = firstNonBlank(getJsonString(json, "net"), "tcp");
            String security = firstNonBlank(getJsonString(json, "tls"), "none");

            return buildNodeRecord(
                    "vmess",
                    displayName,
                    server,
                    port,
                    getJsonString(json, "id"),
                    transport,
                    security,
                    rawUri,
                    subscription,
                    importedAt,
                    extras
            );
        } catch (Exception ignored) {
            return null;
        }
    }

    private NodeRecord parseShadowsocksUri(String rawUri, SubscriptionRecord subscription, long importedAt) {
        try {
            String withoutScheme = rawUri.substring("ss://".length());
            String fragment = "";
            int fragmentIndex = withoutScheme.indexOf('#');
            if (fragmentIndex >= 0) {
                fragment = decodeComponent(withoutScheme.substring(fragmentIndex + 1));
                withoutScheme = withoutScheme.substring(0, fragmentIndex);
            }

            String query = "";
            int queryIndex = withoutScheme.indexOf('?');
            if (queryIndex >= 0) {
                query = withoutScheme.substring(queryIndex + 1);
                withoutScheme = withoutScheme.substring(0, queryIndex);
            }

            String credentialsPart;
            String serverPart;
            int atIndex = withoutScheme.lastIndexOf('@');
            if (atIndex >= 0) {
                credentialsPart = withoutScheme.substring(0, atIndex);
                serverPart = withoutScheme.substring(atIndex + 1);
            } else {
                String decoded = tryDecodeBase64Text(withoutScheme);
                if (decoded == null) {
                    return null;
                }
                int decodedAtIndex = decoded.lastIndexOf('@');
                if (decodedAtIndex < 0) {
                    return null;
                }
                credentialsPart = decoded.substring(0, decodedAtIndex);
                serverPart = decoded.substring(decodedAtIndex + 1);
            }

            if (!credentialsPart.contains(":")) {
                String decodedCreds = tryDecodeBase64Text(credentialsPart);
                if (decodedCreds == null || !decodedCreds.contains(":")) {
                    return null;
                }
                credentialsPart = decodedCreds;
            }

            int colonIndex = credentialsPart.indexOf(':');
            int portSeparator = serverPart.lastIndexOf(':');
            if (colonIndex < 0 || portSeparator < 0) {
                return null;
            }

            String method = decodeComponent(credentialsPart.substring(0, colonIndex));
            String password = decodeComponent(credentialsPart.substring(colonIndex + 1));
            String server = serverPart.substring(0, portSeparator);
            int port = parsePort(serverPart.substring(portSeparator + 1));
            if (server.isEmpty() || port <= 0) {
                return null;
            }

            Map<String, String> extras = parseQuery(query);
            String displayName = firstNonBlank(fragment, "SS " + server);

            return buildNodeRecord(
                    "ss",
                    displayName,
                    server,
                    port,
                    password,
                    "tcp",
                    method,
                    rawUri,
                    subscription,
                    importedAt,
                    extras
            );
        } catch (Exception ignored) {
            return null;
        }
    }

    private NodeRecord buildNodeRecord(
            String protocol,
            String displayName,
            String server,
            int port,
            String credential,
            String transport,
            String security,
            String rawUri,
            SubscriptionRecord subscription,
            long importedAt,
            Map<String, String> extras
    ) {
        NodeRecord node = new NodeRecord();
        node.id = UUID.randomUUID().toString();
        node.protocol = protocol;
        node.displayName = firstNonBlank(displayName, protocol.toUpperCase(Locale.ROOT) + " " + server);
        node.server = server;
        node.port = port;
        node.credential = credential;
        node.transport = transport;
        node.security = security;
        node.rawUri = rawUri;
        node.sourceSubscriptionId = subscription.id;
        node.sourceSubscriptionName = subscription.name;
        node.sourceType = subscription.sourceType;
        node.importedAt = importedAt;
        node.lastImportedAt = importedAt;
        node.extras.putAll(extras);
        return node;
    }

    private String unwrapSubscriptionEnvelope(String content) {
        String trimmed = content == null ? "" : content.trim();
        if (trimmed.isEmpty()) {
            return "";
        }

        if (containsSupportedScheme(trimmed)) {
            return trimmed;
        }

        String decoded = tryDecodeBase64Text(trimmed);
        if (decoded != null && containsSupportedScheme(decoded)) {
            return decoded;
        }

        return trimmed;
    }

    private boolean containsSupportedScheme(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        return lower.contains("vless://")
                || lower.contains("vmess://")
                || lower.contains("trojan://")
                || lower.contains("ss://")
                || lower.contains("anytls://");
    }

    private String extractScheme(String line) {
        int index = line.indexOf("://");
        if (index <= 0) {
            return null;
        }
        return line.substring(0, index).toLowerCase(Locale.ROOT);
    }

    private Map<String, String> parseQuery(String rawQuery) {
        Map<String, String> query = new LinkedHashMap<>();
        if (rawQuery == null || rawQuery.trim().isEmpty()) {
            return query;
        }

        String[] pairs = rawQuery.split("&");
        for (String pair : pairs) {
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
        if (value == null || value.isEmpty()) {
            return "";
        }
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private String tryDecodeBase64Text(String raw) {
        String compact = raw.replaceAll("\\s+", "");
        if (compact.isEmpty()) {
            return null;
        }

        String[] candidates = new String[] {
                compact,
                padBase64(compact),
                padBase64(compact.replace('-', '+').replace('_', '/'))
        };

        for (String candidate : candidates) {
            if (candidate == null || candidate.isEmpty()) {
                continue;
            }

            try {
                byte[] decoded = Base64.getDecoder().decode(candidate);
                return new String(decoded, StandardCharsets.UTF_8);
            } catch (IllegalArgumentException ignored) {
                // try next candidate
            }
        }

        return null;
    }

    private String padBase64(String value) {
        int padding = value.length() % 4;
        if (padding == 0) {
            return value;
        }
        if (padding == 1) {
            return null;
        }
        if (padding == 2) {
            return value + "==";
        }
        return value + "=";
    }

    private String getJsonString(JsonObject object, String key) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) {
            return "";
        }
        return object.get(key).getAsString().trim();
    }

    private int parsePort(String value) {
        try {
            return Integer.parseInt(value.trim());
        } catch (Exception ignored) {
            return -1;
        }
    }

    private void putIfPresent(Map<String, String> target, String key, String value) {
        if (value != null && !value.trim().isEmpty()) {
            target.put(key, value.trim());
        }
    }

    private String firstNonBlank(String first, String second) {
        if (first != null && !first.trim().isEmpty()) {
            return first.trim();
        }
        if (second != null && !second.trim().isEmpty()) {
            return second.trim();
        }
        return "";
    }

    private String firstNonBlank(String first, String second, String third) {
        return firstNonBlank(first, firstNonBlank(second, third));
    }
}
