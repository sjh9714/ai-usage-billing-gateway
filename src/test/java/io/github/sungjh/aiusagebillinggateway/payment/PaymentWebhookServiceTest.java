package io.github.sungjh.aiusagebillinggateway.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.sungjh.aiusagebillinggateway.audit.AuditService;
import io.github.sungjh.aiusagebillinggateway.common.Hashing;
import io.github.sungjh.aiusagebillinggateway.domain.PaymentWebhookEvent;
import io.github.sungjh.aiusagebillinggateway.ledger.LedgerService;
import io.github.sungjh.aiusagebillinggateway.observability.MetricsService;
import io.github.sungjh.aiusagebillinggateway.repository.InvoiceRepository;
import io.github.sungjh.aiusagebillinggateway.repository.PaymentRepository;
import io.github.sungjh.aiusagebillinggateway.repository.PaymentWebhookEventRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class PaymentWebhookServiceTest {

    @Test
    void duplicateRaceFallsBackToExistingWebhookEvent() {
        PaymentWebhookEventRepository webhookRepository = mock(PaymentWebhookEventRepository.class);
        String body = """
                {"providerEventId":"evt-race","type":"payment.succeeded","invoiceId":"%s","amountMinor":100,"currency":"USD"}
                """.formatted(UUID.randomUUID());
        String payloadHash = Hashing.sha256Hex(body);
        PaymentWebhookEvent existing = new PaymentWebhookEvent(
                "evt-race",
                "payment.succeeded",
                payloadHash);
        when(webhookRepository.findByProviderEventId("evt-race"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(existing));
        when(webhookRepository.insertIfAbsent(
                any(UUID.class),
                eq("evt-race"),
                eq("payment.succeeded"),
                eq(payloadHash),
                any(Instant.class)))
                .thenReturn(0);

        PaymentWebhookService service = new PaymentWebhookService(
                webhookRepository,
                mock(PaymentRepository.class),
                mock(InvoiceRepository.class),
                mock(LedgerService.class),
                mock(AuditService.class),
                mock(MetricsService.class),
                new ObjectMapper(),
                "test-webhook-secret");

        PaymentWebhookResponse response = service.process(
                Hashing.hmacSha256Hex("test-webhook-secret", body),
                body);

        assertThat(response.duplicate()).isTrue();
        assertThat(response.status()).isEqualTo("duplicate");
    }

    @Test
    void webhookPayloadConflictRecordsConflictMetric() {
        PaymentWebhookEventRepository webhookRepository = mock(PaymentWebhookEventRepository.class);
        MetricsService metricsService = mock(MetricsService.class);
        String body = """
                {"providerEventId":"evt-conflict","type":"payment.succeeded","invoiceId":"%s","amountMinor":100,"currency":"USD"}
                """.formatted(UUID.randomUUID());
        PaymentWebhookEvent existing = new PaymentWebhookEvent(
                "evt-conflict",
                "payment.succeeded",
                "different-hash");
        when(webhookRepository.findByProviderEventId("evt-conflict"))
                .thenReturn(Optional.of(existing));

        PaymentWebhookService service = new PaymentWebhookService(
                webhookRepository,
                mock(PaymentRepository.class),
                mock(InvoiceRepository.class),
                mock(LedgerService.class),
                mock(AuditService.class),
                metricsService,
                new ObjectMapper(),
                "test-webhook-secret");

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.process(
                        Hashing.hmacSha256Hex("test-webhook-secret", body),
                        body))
                .isInstanceOf(ResponseStatusException.class);

        verify(metricsService).webhookConflict();
    }
}
