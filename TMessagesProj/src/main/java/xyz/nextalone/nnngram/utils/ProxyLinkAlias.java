package xyz.nextalone.nnngram.utils;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Extracts the user-facing alias (remark) from a proxy share link for display
 * in the proxy list. Pure string handling: no I/O, no logging, and the link
 * itself is never returned on failure (a missing or invalid alias yields
 * {@code null} so callers can fall back to the endpoint).
 *
 * <ul>
 *   <li>VLESS / Shadowsocks: the {@code #fragment} of the link; literal
 *       {@code +} is preserved and only the fragment is decoded once.</li>
 *   <li>Shadowsocks legacy: the fragment inside the base64 payload.</li>
 *   <li>VMess: the {@code ps} field of the base64 JSON payload.</li>
 * </ul>
 */
public final class ProxyLinkAlias {

    private ProxyLinkAlias() {
    }

    public static String from(String link) {
        if (link == null) {
            return null;
        }
        if (startsWith(link, "vless://")) {
            return fragment(link);
        }
        if (startsWith(link, "ss://")) {
            return ssAlias(link);
        }
        if (startsWith(link, "vmess://")) {
            return vmessAlias(link);
        }
        return null;
    }

    /** VMess alias: the {@code ps} string field of the base64 JSON payload. */
    private static String vmessAlias(String link) {
        final String decoded = base64ToString(link.substring("vmess://".length()));
        if (decoded == null) {
            return null;
        }
        try {
            final com.google.gson.JsonObject json =
                    com.google.gson.JsonParser.parseString(decoded).getAsJsonObject();
            final com.google.gson.JsonElement ps = json.get("ps");
            if (ps != null && ps.isJsonPrimitive() && ps.getAsJsonPrimitive().isString()) {
                final String name = ps.getAsString();
                return name.trim().isEmpty() ? null : name;
            }
        } catch (RuntimeException ignored) {
            // Not a VMess JSON payload: fall back to the endpoint.
        }
        return null;
    }

    /**
     * SS alias: the outer {@code #fragment}; for legacy links (whole body is
     * base64 of {@code method:password@host:port#alias}) the fragment inside
     * the decoded payload, mirroring the accept order of the Go parser
     * (which splits the query off before decoding the payload).
     */
    private static String ssAlias(String link) {
        final String outer = fragment(link);
        if (outer != null) {
            return outer;
        }
        final int hash = link.indexOf('#');
        int end = link.indexOf('?');
        if (hash >= 0 && hash < end) {
            end = hash;
        }
        final String body = link.substring("ss://".length(), end < 0 ? link.length() : end);
        if (body.indexOf('@') >= 0) {
            // SIP002 user info present: the outer fragment was the only alias.
            return null;
        }
        // Legacy: the body up to any query is base64 of "method:password@host:port#alias".
        final String decoded = base64ToString(body);
        if (decoded == null) {
            return null;
        }
        return fragment(decoded);
    }

    /** Tolerant base64 (std/url, padded or raw), or null on any failure. */
    private static String base64ToString(String value) {
        if (value.isEmpty()) {
            return null;
        }
        final Base64.Decoder[] decoders = {
                Base64.getDecoder(), Base64.getUrlDecoder(),
        };
        for (Base64.Decoder decoder : decoders) {
            try {
                final byte[] raw = decoder.decode(value);
                if (raw.length == 0) {
                    return null;
                }
                return new String(raw, StandardCharsets.UTF_8);
            } catch (IllegalArgumentException ignored) {
            }
        }
        return null;
    }

    /** The link fragment, percent-decoded once with {@code +} kept literal. */
    private static String fragment(String link) {
        final int hash = link.indexOf('#');
        if (hash < 0) {
            return null;
        }
        final String decoded = percentDecode(link.substring(hash + 1));
        if (decoded == null || decoded.trim().isEmpty()) {
            return null;
        }
        return decoded;
    }

    private static boolean startsWith(String link, String prefix) {
        return link.regionMatches(true, 0, prefix, 0, prefix.length());
    }

    /**
     * Strict percent-decoding via {@link java.net.URLDecoder}: any bad or
     * truncated escape fails the whole value (null) instead of leaking the
     * raw link. {@code +} is kept literal (fragment semantics, not query form
     * encoding), so it is escaped before decoding.
     */
    private static String percentDecode(String value) {
        if (value.indexOf('%') < 0) {
            return value;
        }
        try {
            return java.net.URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8.name());
        } catch (java.io.UnsupportedEncodingException | IllegalArgumentException e) {
            return null;
        }
    }
}
