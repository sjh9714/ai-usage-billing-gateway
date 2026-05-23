package io.github.sungjh.aiusagebillinggateway.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AuditMetadataSanitizerTest {

    private final AuditMetadataSanitizer sanitizer = new AuditMetadataSanitizer();

    @Test
    void redactsAllConfiguredSensitiveKeys() {
        Map<String, Object> metadata = new LinkedHashMap<>();
        for (String key : List.of(
                "apiKey",
                "api-key",
                "api_key",
                "authorization",
                "TOKEN",
                "secret",
                "password",
                "signature",
                "webhookSignature",
                "cookie",
                "set-cookie")) {
            metadata.put(key, "sensitive");
        }
        metadata.put("safe", "visible");

        Map<String, Object> sanitized = sanitizer.sanitize(metadata);

        metadata.keySet().stream()
                .filter(key -> !key.equals("safe"))
                .forEach(key -> assertThat(sanitized.get(key)).isEqualTo("[REDACTED]"));
        assertThat(sanitized.get("safe")).isEqualTo("visible");
    }

    @Test
    void redactsSensitiveKeysCaseInsensitivelyInNestedMetadata() {
        Map<String, Object> metadata = Map.of(
                "apiKey", "raw-api-key",
                "Authorization", "Bearer jwt",
                "nested", Map.of(
                        "api_key", "nested-api-key",
                        "safe", "visible",
                        "items", List.of(
                                Map.of("token", "nested-token", "count", 3),
                                Map.of("webhookSignature", "signature-value"))),
                "headers", List.of(
                        Map.of("set-cookie", "session=abc"),
                        Map.of("COOKIE", "tracking=abc")));

        Map<String, Object> sanitized = sanitizer.sanitize(metadata);

        assertThat(sanitized)
                .containsEntry("apiKey", "[REDACTED]")
                .containsEntry("Authorization", "[REDACTED]");
        Object nestedObject = sanitized.get("nested");
        assertThat(nestedObject).isInstanceOf(Map.class);
        Map<?, ?> nested = (Map<?, ?>) nestedObject;
        assertThat(nested.get("api_key")).isEqualTo("[REDACTED]");
        assertThat(nested.get("safe")).isEqualTo("visible");
        List<?> items = (List<?>) nested.get("items");
        assertThat(((Map<?, ?>) items.getFirst()).get("token"))
                .isEqualTo("[REDACTED]");
        assertThat(((Map<?, ?>) items.get(1)).get("webhookSignature"))
                .isEqualTo("[REDACTED]");
        List<?> headers = (List<?>) sanitized.get("headers");
        assertThat(((Map<?, ?>) headers.getFirst()).get("set-cookie"))
                .isEqualTo("[REDACTED]");
        assertThat(((Map<?, ?>) headers.get(1)).get("COOKIE"))
                .isEqualTo("[REDACTED]");
    }

    @Test
    void preservesNonSensitiveMetadataAndNullInputBecomesEmptyMap() {
        Map<String, Object> metadata = Map.of(
                "providerEventId", "evt-1",
                "attempts", 2,
                "tags", List.of("billing", "webhook"));

        assertThat(sanitizer.sanitize(metadata))
                .containsEntry("providerEventId", "evt-1")
                .containsEntry("attempts", 2)
                .containsEntry("tags", List.of("billing", "webhook"));
        assertThat(sanitizer.sanitize(null)).isEmpty();
    }
}
