package io.github.sungjh.aiusagebillinggateway.audit;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class AuditMetadataSanitizer {

    private static final String REDACTED = "[REDACTED]";
    private static final Set<String> SENSITIVE_KEYS = Set.of(
            "apikey",
            "api-key",
            "api_key",
            "authorization",
            "token",
            "secret",
            "password",
            "signature",
            "webhooksignature",
            "cookie",
            "set-cookie");

    public Map<String, Object> sanitize(Map<String, ?> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> sanitized = new LinkedHashMap<>();
        metadata.forEach((key, value) -> sanitized.put(key, sanitizeValue(key, value)));
        return sanitized;
    }

    private Object sanitizeValue(Object key, Object value) {
        if (isSensitiveKey(key)) {
            return REDACTED;
        }
        if (value instanceof Map<?, ?> map) {
            return sanitizeMap(map);
        }
        if (value instanceof List<?> list) {
            return sanitizeList(list);
        }
        return value;
    }

    private Map<Object, Object> sanitizeMap(Map<?, ?> metadata) {
        Map<Object, Object> sanitized = new LinkedHashMap<>();
        metadata.forEach((key, value) -> sanitized.put(key, sanitizeValue(key, value)));
        return sanitized;
    }

    private List<Object> sanitizeList(List<?> metadata) {
        List<Object> sanitized = new ArrayList<>(metadata.size());
        metadata.forEach(value -> sanitized.add(sanitizeValue(null, value)));
        return sanitized;
    }

    private boolean isSensitiveKey(Object key) {
        return key instanceof String stringKey
                && SENSITIVE_KEYS.contains(stringKey.toLowerCase(Locale.ROOT));
    }
}
