package ai.spicyapi;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;

// Before the split these private static helpers all lived on SpicyClient. They are shared by the
// records, the enums and the verifier, so they belong in one package-private utility rather than
// being copied into each file - copies of a validation rule always drift apart eventually.
final class Internal {

    private Internal() {
    }

    static String requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " is required");
        }
        return value.trim();
    }

    static String requireHeaderValue(String value, String label) {
        String text = requireText(value, label);
        // CR or LF in a header is the way into response splitting; better to reject it locally.
        if (text.indexOf('\r') >= 0 || text.indexOf('\n') >= 0) {
            throw new IllegalArgumentException(label + " must not contain line breaks");
        }
        return text;
    }

    static Duration requirePositive(Duration value, String label) {
        Objects.requireNonNull(value, label);
        if (value.isNegative() || value.isZero()) {
            throw new IllegalArgumentException(label + " must be positive");
        }
        return value;
    }

    static String normalizeBaseUrl(String value) {
        String text = requireText(value, "baseUrl");
        URI uri;
        try {
            uri = new URI(text);
        } catch (URISyntaxException malformed) {
            throw new IllegalArgumentException("baseUrl must be an absolute URL", malformed);
        }
        if (uri.getScheme() == null || uri.getHost() == null) {
            throw new IllegalArgumentException("baseUrl must be an absolute URL");
        }
        boolean https = "https".equalsIgnoreCase(uri.getScheme());
        boolean loopbackHttp = "http".equalsIgnoreCase(uri.getScheme()) && isLoopback(uri.getHost());
        if (!https && !loopbackHttp) {
            throw new IllegalArgumentException(
                    "baseUrl must use HTTPS; plain HTTP is accepted only for loopback addresses");
        }
        if (uri.getQuery() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException("baseUrl must not carry a query or a fragment");
        }
        if (uri.getUserInfo() != null) {
            throw new IllegalArgumentException("baseUrl must not carry credentials");
        }
        String normalized = uri.toString();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private static boolean isLoopback(String host) {
        String normalized = host.toLowerCase(Locale.ROOT).replace("[", "").replace("]", "");
        return normalized.equals("localhost") || normalized.equals("::1") || normalized.startsWith("127.");
    }

    static String encodePathSegment(String value) {
        // URLEncoder encodes for forms and turns a space into '+'; inside a path segment '+' is a
        // literal plus sign, so it has to be converted back.
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    static void appendParameter(StringBuilder query, String name, String value) {
        if (value == null) {
            return;
        }
        if (query.length() > 0) {
            query.append('&');
        }
        query.append(name).append('=').append(URLEncoder.encode(value, StandardCharsets.UTF_8));
    }

    static <K, V> Map<K, V> unmodifiableCopy(Map<K, V> map) {
        // Not Map.copyOf: it rejects null values, and null is perfectly legal in JSON.
        return Collections.unmodifiableMap(new LinkedHashMap<>(map));
    }

    static <T> List<T> emptyIfNull(List<T> values) {
        return values == null ? List.of() : List.copyOf(values);
    }

    static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (GeneralSecurityException unavailable) {
            throw new IllegalStateException(
                    "SHA-256 is required of every Java platform but is unavailable here", unavailable);
        }
    }

    static long jitter(long millis) {
        return Math.round(millis * (0.8d + ThreadLocalRandom.current().nextDouble() * 0.4d));
    }

    static void sleep(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new SpicyTransportException("interrupted while waiting to retry", interrupted);
        }
    }
}
