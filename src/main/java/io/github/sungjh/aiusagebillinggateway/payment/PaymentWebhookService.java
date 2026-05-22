package io.github.sungjh.aiusagebillinggateway.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.sungjh.aiusagebillinggateway.audit.AuditService;
import io.github.sungjh.aiusagebillinggateway.common.Hashing;
import io.github.sungjh.aiusagebillinggateway.domain.Invoice;
import io.github.sungjh.aiusagebillinggateway.domain.Payment;
import io.github.sungjh.aiusagebillinggateway.domain.PaymentStatus;
import io.github.sungjh.aiusagebillinggateway.domain.PaymentWebhookEvent;
import io.github.sungjh.aiusagebillinggateway.ledger.LedgerService;
import io.github.sungjh.aiusagebillinggateway.observability.MetricsService;
import io.github.sungjh.aiusagebillinggateway.repository.InvoiceRepository;
import io.github.sungjh.aiusagebillinggateway.repository.PaymentRepository;
import io.github.sungjh.aiusagebillinggateway.repository.PaymentWebhookEventRepository;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PaymentWebhookService {

    private final PaymentWebhookEventRepository webhookEventRepository;
    private final PaymentRepository paymentRepository;
    private final InvoiceRepository invoiceRepository;
    private final LedgerService ledgerService;
    private final AuditService auditService;
    private final MetricsService metricsService;
    private final ObjectMapper objectMapper;
    private final String webhookSecret;

    public PaymentWebhookService(
            PaymentWebhookEventRepository webhookEventRepository,
            PaymentRepository paymentRepository,
            InvoiceRepository invoiceRepository,
            LedgerService ledgerService,
            AuditService auditService,
            MetricsService metricsService,
            ObjectMapper objectMapper,
            @Value("${payment.webhook-secret}") String webhookSecret) {
        this.webhookEventRepository = webhookEventRepository;
        this.paymentRepository = paymentRepository;
        this.invoiceRepository = invoiceRepository;
        this.ledgerService = ledgerService;
        this.auditService = auditService;
        this.metricsService = metricsService;
        this.objectMapper = objectMapper;
        this.webhookSecret = webhookSecret;
    }

    @Transactional
    public PaymentWebhookResponse process(String signature, String body) {
        verifySignature(signature, body);
        metricsService.webhookReceived();
        PaymentWebhookRequest request = parse(body);
        String payloadHash = Hashing.sha256Hex(body);
        return webhookEventRepository.findByProviderEventId(request.providerEventId())
                .map(existing -> duplicateOrConflict(existing, payloadHash))
                .orElseGet(() -> processNewWithRaceFallback(request, payloadHash));
    }

    private PaymentWebhookResponse processNewWithRaceFallback(
            PaymentWebhookRequest request,
            String payloadHash) {
        int inserted = webhookEventRepository.insertIfAbsent(
                UUID.randomUUID(),
                request.providerEventId(),
                request.type(),
                payloadHash,
                Instant.now());
        if (inserted == 0) {
            return webhookEventRepository.findByProviderEventId(request.providerEventId())
                    .map(existing -> duplicateOrConflict(existing, payloadHash))
                    .orElseThrow(() -> new ResponseStatusException(
                            HttpStatus.CONFLICT,
                            "Provider event id is already being processed"));
        }
        return processNew(request);
    }

    private PaymentWebhookResponse processNew(PaymentWebhookRequest request) {
        if (!request.type().equals("payment.succeeded")
                && !request.type().equals("payment.failed")
                && !request.type().equals("payment.refunded")) {
            return new PaymentWebhookResponse(request.providerEventId(), false, "ignored");
        }
        Invoice invoice = invoiceRepository.findByIdForUpdate(request.invoiceId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Invoice not found"));
        PaymentStatus paymentStatus = switch (request.type()) {
            case "payment.succeeded" -> PaymentStatus.SUCCEEDED;
            case "payment.failed" -> PaymentStatus.FAILED;
            default -> PaymentStatus.REFUNDED;
        };
        Payment originalCapturedPayment = validatePaymentPayload(request, invoice, paymentStatus);
        Payment payment = paymentRepository.save(new Payment(
                invoice.getOrganizationId(),
                invoice.getId(),
                request.providerEventId(),
                paymentStatus,
                request.amountMinor(),
                request.currency()));
        if (paymentStatus == PaymentStatus.SUCCEEDED) {
            invoice.markPaid();
            ledgerService.recordPaymentSucceeded(invoice, payment);
        } else if (paymentStatus == PaymentStatus.FAILED) {
            invoice.markPaymentFailed();
        } else if (paymentStatus == PaymentStatus.REFUNDED) {
            ledgerService.recordPaymentRefunded(invoice, payment, originalCapturedPayment);
        }
        auditService.record(
                invoice.getOrganizationId(),
                null,
                "PAYMENT_WEBHOOK_PROCESSED",
                "PaymentWebhookEvent",
                null,
                Map.of("providerEventId", request.providerEventId(), "type", request.type()));
        return new PaymentWebhookResponse(request.providerEventId(), false, "processed");
    }

    private Payment validatePaymentPayload(
            PaymentWebhookRequest request,
            Invoice invoice,
            PaymentStatus paymentStatus) {
        if (paymentStatus == PaymentStatus.SUCCEEDED) {
            requireInvoiceAmountAndCurrency(request, invoice);
            return null;
        } else if (paymentStatus == PaymentStatus.REFUNDED) {
            return requireRefundWithinCapturedBalance(request, invoice);
        }
        return null;
    }

    private void requireInvoiceAmountAndCurrency(PaymentWebhookRequest request, Invoice invoice) {
        if (request.amountMinor() != invoice.getTotalAmountMinor()
                || !invoice.getCurrency().equals(request.currency())) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Payment amount or currency does not match invoice");
        }
        if (paymentRepository.existsByInvoiceIdAndStatus(invoice.getId(), PaymentStatus.SUCCEEDED)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Invoice already has a captured payment");
        }
    }

    private Payment requireRefundWithinCapturedBalance(PaymentWebhookRequest request, Invoice invoice) {
        if (request.amountMinor() <= 0 || !invoice.getCurrency().equals(request.currency())) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Refund amount or currency is invalid for invoice");
        }
        long capturedAmount = paymentRepository.sumAmountMinor(
                invoice.getId(),
                PaymentStatus.SUCCEEDED,
                request.currency());
        long refundedAmount = paymentRepository.sumAmountMinor(
                invoice.getId(),
                PaymentStatus.REFUNDED,
                request.currency());
        if (capturedAmount <= 0 || refundedAmount + request.amountMinor() > capturedAmount) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Refund exceeds captured payment amount");
        }
        return paymentRepository.findFirstByInvoiceIdAndStatusAndCurrencyOrderByCreatedAtAsc(
                        invoice.getId(),
                        PaymentStatus.SUCCEEDED,
                        request.currency())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "Refund requires a captured payment"));
    }

    private PaymentWebhookResponse duplicateOrConflict(PaymentWebhookEvent existing, String payloadHash) {
        if (!existing.getPayloadHash().equals(payloadHash)) {
            metricsService.webhookConflict();
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Provider event id was already used with a different payload");
        }
        metricsService.webhookDuplicate();
        return new PaymentWebhookResponse(null, true, "duplicate");
    }

    private void verifySignature(String signature, String body) {
        if (signature == null || signature.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Webhook signature required");
        }
        String expected = Hashing.hmacSha256Hex(webhookSecret, body);
        if (!Hashing.constantTimeEquals(expected, signature)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid webhook signature");
        }
    }

    private PaymentWebhookRequest parse(String body) {
        try {
            return objectMapper.readValue(body, PaymentWebhookRequest.class);
        } catch (Exception exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid webhook payload", exception);
        }
    }
}
