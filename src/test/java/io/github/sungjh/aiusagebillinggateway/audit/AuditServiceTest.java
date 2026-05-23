package io.github.sungjh.aiusagebillinggateway.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.sungjh.aiusagebillinggateway.domain.AuditLog;
import io.github.sungjh.aiusagebillinggateway.observability.MetricsService;
import io.github.sungjh.aiusagebillinggateway.repository.AuditLogRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

class AuditServiceTest {

    @Test
    void recordPersistsSanitizedMetadataJson() throws Exception {
        AuditLogRepository auditLogRepository = mock(AuditLogRepository.class);
        MetricsService metricsService = mock(MetricsService.class);
        ObjectMapper objectMapper = new ObjectMapper();
        AuditService auditService = new AuditService(
                auditLogRepository,
                objectMapper,
                metricsService,
                new AuditMetadataSanitizer());

        auditService.record(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "PAYMENT_WEBHOOK_PROCESSED",
                "PaymentWebhookEvent",
                UUID.randomUUID(),
                Map.of(
                        "providerEventId", "evt-1",
                        "token", "raw-token",
                        "nested", List.of(Map.of("Secret", "nested-secret", "safe", "kept"))));

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).save(captor.capture());
        String metadata = (String) ReflectionTestUtils.getField(captor.getValue(), "metadata");
        JsonNode json = objectMapper.readTree(metadata);
        assertThat(json.get("providerEventId").asText()).isEqualTo("evt-1");
        assertThat(json.get("token").asText()).isEqualTo("[REDACTED]");
        assertThat(json.get("nested").get(0).get("Secret").asText()).isEqualTo("[REDACTED]");
        assertThat(json.get("nested").get(0).get("safe").asText()).isEqualTo("kept");
    }
}
