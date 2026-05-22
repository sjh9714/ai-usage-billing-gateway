package io.github.sungjh.aiusagebillinggateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class BillingPaymentLedgerAuditIT extends IntegrationTestSupport {

    @Test
    void invoiceGenerationIsIdempotentTenantIsolatedAndCreatesBalancedLedger() throws Exception {
        String token = signup("owner@example.com");
        UUID organizationId = createOrganization(token, "Billing Org");
        String rawKey = createApiKey(token, organizationId, "primary");
        jdbcTemplate.update(
                "update plans set included_quantity = 1, overage_unit_amount_minor = 25, overage_allowed = true where code = 'FREE'");

        for (int index = 1; index <= 4; index++) {
            mockMvc.perform(post("/api/usage/events")
                            .header("X-API-Key", rawKey)
                            .header("Idempotency-Key", "bill-" + index)
                            .contentType(APPLICATION_JSON)
                            .content("""
                                    {"metric":"REQUEST","quantity":1}
                                    """))
                    .andExpect(status().isCreated());
        }

        YearMonth period = currentPeriod();
        JsonNode firstInvoice = json(mockMvc.perform(post(
                                "/api/organizations/{orgId}/invoices/generate?period={period}",
                                organizationId,
                                period)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.totalAmountMinor").value(75))
                .andReturn());

        mockMvc.perform(post(
                        "/api/organizations/{orgId}/invoices/generate?period={period}",
                        organizationId,
                        period)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.duplicate").value(true));

        Integer invoiceCount = jdbcTemplate.queryForObject(
                "select count(*) from invoices where organization_id = ?",
                Integer.class,
                organizationId);
        assertThat(invoiceCount).isEqualTo(1);

        UUID invoiceId = UUID.fromString(firstInvoice.get("id").asText());
        List<Map<String, Object>> ledgerRows = jdbcTemplate.queryForList(
                "select direction, amount_minor from ledger_entries where invoice_id = ?",
                invoiceId);
        long debit = ledgerRows.stream()
                .filter(row -> row.get("direction").equals("DEBIT"))
                .mapToLong(row -> ((Number) row.get("amount_minor")).longValue())
                .sum();
        long credit = ledgerRows.stream()
                .filter(row -> row.get("direction").equals("CREDIT"))
                .mapToLong(row -> ((Number) row.get("amount_minor")).longValue())
                .sum();
        assertThat(debit).isEqualTo(credit).isEqualTo(75);
    }

    @Test
    void webhookSignatureIdempotencyPaymentStatusAndLedgerAreEnforced() throws Exception {
        String token = signup("owner@example.com");
        UUID organizationId = createOrganization(token, "Webhook Org");
        String rawKey = createApiKey(token, organizationId, "primary");
        jdbcTemplate.update(
                "update plans set included_quantity = 0, overage_unit_amount_minor = 100, overage_allowed = true where code = 'FREE'");
        mockMvc.perform(post("/api/usage/events")
                        .header("X-API-Key", rawKey)
                        .header("Idempotency-Key", "webhook-usage")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"metric":"REQUEST","quantity":1}
                                """))
                .andExpect(status().isCreated());

        JsonNode invoice = json(mockMvc.perform(post(
                                "/api/organizations/{orgId}/invoices/generate?period={period}",
                                organizationId,
                                currentPeriod())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated())
                .andReturn());
        UUID invoiceId = UUID.fromString(invoice.get("id").asText());
        String body = """
                {"providerEventId":"evt-paid-1","type":"payment.succeeded","invoiceId":"%s","amountMinor":100,"currency":"USD"}
                """.formatted(invoiceId);

        mockMvc.perform(post("/api/webhooks/payments")
                        .header("X-Webhook-Signature", webhookSignature(body))
                        .contentType(APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.duplicate").value(false));

        mockMvc.perform(post("/api/webhooks/payments")
                        .header("X-Webhook-Signature", webhookSignature(body))
                        .contentType(APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.duplicate").value(true));

        Integer paymentCount = jdbcTemplate.queryForObject(
                "select count(*) from payments where invoice_id = ?",
                Integer.class,
                invoiceId);
        String invoiceStatus = jdbcTemplate.queryForObject(
                "select status from invoices where id = ?",
                String.class,
                invoiceId);
        Integer webhookAuditCount = jdbcTemplate.queryForObject(
                "select count(*) from audit_logs where action = 'PAYMENT_WEBHOOK_PROCESSED'",
                Integer.class);

        assertThat(paymentCount).isEqualTo(1);
        assertThat(invoiceStatus).isEqualTo("PAID");
        assertThat(webhookAuditCount).isEqualTo(1);
    }

    @Test
    void paymentSucceededRejectsAmountOrCurrencyMismatchWithoutMarkingInvoicePaid() throws Exception {
        String token = signup("mismatch-owner@example.com");
        UUID organizationId = createOrganization(token, "Webhook Mismatch Org");
        String rawKey = createApiKey(token, organizationId, "primary");
        jdbcTemplate.update(
                "update plans set included_quantity = 0, overage_unit_amount_minor = 100, overage_allowed = true where code = 'FREE'");
        mockMvc.perform(post("/api/usage/events")
                        .header("X-API-Key", rawKey)
                        .header("Idempotency-Key", "webhook-mismatch-usage")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"metric":"REQUEST","quantity":1}
                                """))
                .andExpect(status().isCreated());

        JsonNode invoice = json(mockMvc.perform(post(
                                "/api/organizations/{orgId}/invoices/generate?period={period}",
                                organizationId,
                                currentPeriod())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated())
                .andReturn());
        UUID invoiceId = UUID.fromString(invoice.get("id").asText());
        String wrongAmountBody = """
                {"providerEventId":"evt-wrong-amount","type":"payment.succeeded","invoiceId":"%s","amountMinor":99,"currency":"USD"}
                """.formatted(invoiceId);
        String wrongCurrencyBody = """
                {"providerEventId":"evt-wrong-currency","type":"payment.succeeded","invoiceId":"%s","amountMinor":100,"currency":"KRW"}
                """.formatted(invoiceId);

        mockMvc.perform(post("/api/webhooks/payments")
                        .header("X-Webhook-Signature", webhookSignature(wrongAmountBody))
                        .contentType(APPLICATION_JSON)
                        .content(wrongAmountBody))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/webhooks/payments")
                        .header("X-Webhook-Signature", webhookSignature(wrongCurrencyBody))
                        .contentType(APPLICATION_JSON)
                        .content(wrongCurrencyBody))
                .andExpect(status().isBadRequest());

        Integer paymentCount = jdbcTemplate.queryForObject(
                "select count(*) from payments where invoice_id = ?",
                Integer.class,
                invoiceId);
        String invoiceStatus = jdbcTemplate.queryForObject(
                "select status from invoices where id = ?",
                String.class,
                invoiceId);

        assertThat(paymentCount).isZero();
        assertThat(invoiceStatus).isEqualTo("ISSUED");
    }

    @Test
    void paymentSucceededRejectsSecondDistinctCaptureForSameInvoice() throws Exception {
        String token = signup("duplicate-capture-owner@example.com");
        UUID organizationId = createOrganization(token, "Duplicate Capture Org");
        String rawKey = createApiKey(token, organizationId, "primary");
        jdbcTemplate.update(
                "update plans set included_quantity = 0, overage_unit_amount_minor = 100, overage_allowed = true where code = 'FREE'");
        mockMvc.perform(post("/api/usage/events")
                        .header("X-API-Key", rawKey)
                        .header("Idempotency-Key", "duplicate-capture-usage")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"metric":"REQUEST","quantity":1}
                                """))
                .andExpect(status().isCreated());

        JsonNode invoice = json(mockMvc.perform(post(
                                "/api/organizations/{orgId}/invoices/generate?period={period}",
                                organizationId,
                                currentPeriod())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated())
                .andReturn());
        UUID invoiceId = UUID.fromString(invoice.get("id").asText());
        String firstCaptureBody = """
                {"providerEventId":"evt-first-capture","type":"payment.succeeded","invoiceId":"%s","amountMinor":100,"currency":"USD"}
                """.formatted(invoiceId);
        String secondCaptureBody = """
                {"providerEventId":"evt-second-capture","type":"payment.succeeded","invoiceId":"%s","amountMinor":100,"currency":"USD"}
                """.formatted(invoiceId);

        mockMvc.perform(post("/api/webhooks/payments")
                        .header("X-Webhook-Signature", webhookSignature(firstCaptureBody))
                        .contentType(APPLICATION_JSON)
                        .content(firstCaptureBody))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/webhooks/payments")
                        .header("X-Webhook-Signature", webhookSignature(secondCaptureBody))
                        .contentType(APPLICATION_JSON)
                        .content(secondCaptureBody))
                .andExpect(status().isBadRequest());

        Integer succeededPaymentCount = jdbcTemplate.queryForObject(
                "select count(*) from payments where invoice_id = ? and status = 'SUCCEEDED'",
                Integer.class,
                invoiceId);
        Long capturedAmount = jdbcTemplate.queryForObject(
                "select coalesce(sum(amount_minor), 0) from payments where invoice_id = ? and status = 'SUCCEEDED'",
                Long.class,
                invoiceId);

        assertThat(succeededPaymentCount).isEqualTo(1);
        assertThat(capturedAmount).isEqualTo(100);
    }

    @Test
    void refundedPaymentCreatesBalancedLedgerReversal() throws Exception {
        String token = signup("owner@example.com");
        UUID organizationId = createOrganization(token, "Refund Org");
        String rawKey = createApiKey(token, organizationId, "primary");
        jdbcTemplate.update(
                "update plans set included_quantity = 0, overage_unit_amount_minor = 100, overage_allowed = true where code = 'FREE'");
        mockMvc.perform(post("/api/usage/events")
                        .header("X-API-Key", rawKey)
                        .header("Idempotency-Key", "refund-usage")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"metric":"REQUEST","quantity":1}
                                """))
                .andExpect(status().isCreated());

        JsonNode invoice = json(mockMvc.perform(post(
                                "/api/organizations/{orgId}/invoices/generate?period={period}",
                                organizationId,
                                currentPeriod())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated())
                .andReturn());
        UUID invoiceId = UUID.fromString(invoice.get("id").asText());
        String paidBody = """
                {"providerEventId":"evt-paid-before-refund","type":"payment.succeeded","invoiceId":"%s","amountMinor":100,"currency":"USD"}
                """.formatted(invoiceId);
        mockMvc.perform(post("/api/webhooks/payments")
                        .header("X-Webhook-Signature", webhookSignature(paidBody))
                        .contentType(APPLICATION_JSON)
                        .content(paidBody))
                .andExpect(status().isOk());

        String refundBody = """
                {"providerEventId":"evt-refund-1","type":"payment.refunded","invoiceId":"%s","amountMinor":100,"currency":"USD"}
                """.formatted(invoiceId);
        mockMvc.perform(post("/api/webhooks/payments")
                        .header("X-Webhook-Signature", webhookSignature(refundBody))
                        .contentType(APPLICATION_JSON)
                        .content(refundBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.duplicate").value(false));

        mockMvc.perform(post("/api/webhooks/payments")
                        .header("X-Webhook-Signature", webhookSignature(refundBody))
                        .contentType(APPLICATION_JSON)
                        .content(refundBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.duplicate").value(true));

        Integer refundPaymentCount = jdbcTemplate.queryForObject(
                "select count(*) from payments where invoice_id = ? and status = 'REFUNDED'",
                Integer.class,
                invoiceId);
        String originalLedgerGroup = jdbcTemplate.queryForObject(
                """
                select transaction_group_id
                  from ledger_entries
                 where invoice_id = ?
                   and type = 'PAYMENT_SUCCEEDED'
                 limit 1
                """,
                String.class,
                invoiceId);
        List<Map<String, Object>> reversalRows = jdbcTemplate.queryForList(
                """
                select account, direction, amount_minor, original_transaction_group_id
                  from ledger_entries
                 where invoice_id = ?
                   and type = 'PAYMENT_REFUNDED'
                """,
                invoiceId);
        long debit = reversalRows.stream()
                .filter(row -> row.get("direction").equals("DEBIT"))
                .mapToLong(row -> ((Number) row.get("amount_minor")).longValue())
                .sum();
        long credit = reversalRows.stream()
                .filter(row -> row.get("direction").equals("CREDIT"))
                .mapToLong(row -> ((Number) row.get("amount_minor")).longValue())
                .sum();

        assertThat(refundPaymentCount).isEqualTo(1);
        assertThat(reversalRows)
                .extracting(row -> row.get("account"))
                .containsExactlyInAnyOrder("ACCOUNTS_RECEIVABLE", "CASH");
        assertThat(reversalRows)
                .extracting(row -> row.get("original_transaction_group_id"))
                .containsOnly(originalLedgerGroup);
        assertThat(debit).isEqualTo(credit).isEqualTo(100);
    }

    @Test
    void partialRefundsCreateSeparateBalancedReversalGroupsWithinCapturedAmount() throws Exception {
        String token = signup("partial-refund-owner@example.com");
        UUID organizationId = createOrganization(token, "Partial Refund Org");
        String rawKey = createApiKey(token, organizationId, "primary");
        jdbcTemplate.update(
                "update plans set included_quantity = 0, overage_unit_amount_minor = 100, overage_allowed = true where code = 'FREE'");
        mockMvc.perform(post("/api/usage/events")
                        .header("X-API-Key", rawKey)
                        .header("Idempotency-Key", "partial-refund-usage")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"metric":"REQUEST","quantity":1}
                                """))
                .andExpect(status().isCreated());

        JsonNode invoice = json(mockMvc.perform(post(
                                "/api/organizations/{orgId}/invoices/generate?period={period}",
                                organizationId,
                                currentPeriod())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated())
                .andReturn());
        UUID invoiceId = UUID.fromString(invoice.get("id").asText());
        String paidBody = """
                {"providerEventId":"evt-paid-before-partial-refund","type":"payment.succeeded","invoiceId":"%s","amountMinor":100,"currency":"USD"}
                """.formatted(invoiceId);
        mockMvc.perform(post("/api/webhooks/payments")
                        .header("X-Webhook-Signature", webhookSignature(paidBody))
                        .contentType(APPLICATION_JSON)
                        .content(paidBody))
                .andExpect(status().isOk());

        String firstRefundBody = """
                {"providerEventId":"evt-partial-refund-40","type":"payment.refunded","invoiceId":"%s","amountMinor":40,"currency":"USD"}
                """.formatted(invoiceId);
        String secondRefundBody = """
                {"providerEventId":"evt-partial-refund-60","type":"payment.refunded","invoiceId":"%s","amountMinor":60,"currency":"USD"}
                """.formatted(invoiceId);
        mockMvc.perform(post("/api/webhooks/payments")
                        .header("X-Webhook-Signature", webhookSignature(firstRefundBody))
                        .contentType(APPLICATION_JSON)
                        .content(firstRefundBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.duplicate").value(false));
        mockMvc.perform(post("/api/webhooks/payments")
                        .header("X-Webhook-Signature", webhookSignature(secondRefundBody))
                        .contentType(APPLICATION_JSON)
                        .content(secondRefundBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.duplicate").value(false));

        String originalLedgerGroup = jdbcTemplate.queryForObject(
                """
                select transaction_group_id
                  from ledger_entries
                 where invoice_id = ?
                   and type = 'PAYMENT_SUCCEEDED'
                 limit 1
                """,
                String.class,
                invoiceId);
        List<Map<String, Object>> reversalRows = jdbcTemplate.queryForList(
                """
                select transaction_group_id, direction, amount_minor, original_transaction_group_id
                  from ledger_entries
                 where invoice_id = ?
                   and type = 'PAYMENT_REFUNDED'
                """,
                invoiceId);
        Long refundedAmount = jdbcTemplate.queryForObject(
                "select coalesce(sum(amount_minor), 0) from payments where invoice_id = ? and status = 'REFUNDED'",
                Long.class,
                invoiceId);
        long debit = reversalRows.stream()
                .filter(row -> row.get("direction").equals("DEBIT"))
                .mapToLong(row -> ((Number) row.get("amount_minor")).longValue())
                .sum();
        long credit = reversalRows.stream()
                .filter(row -> row.get("direction").equals("CREDIT"))
                .mapToLong(row -> ((Number) row.get("amount_minor")).longValue())
                .sum();
        List<Object> reversalGroups = reversalRows.stream()
                .map(row -> row.get("transaction_group_id"))
                .distinct()
                .toList();

        assertThat(refundedAmount).isEqualTo(100);
        assertThat(reversalRows).hasSize(4);
        assertThat(reversalGroups).hasSize(2);
        assertThat(reversalRows)
                .extracting(row -> row.get("original_transaction_group_id"))
                .containsOnly(originalLedgerGroup);
        assertThat(debit).isEqualTo(credit).isEqualTo(100);
    }

    @Test
    void refundWebhookRequiresCapturedPaymentAndRejectsOverRefund() throws Exception {
        String token = signup("refund-guard-owner@example.com");
        UUID organizationId = createOrganization(token, "Refund Guard Org");
        String rawKey = createApiKey(token, organizationId, "primary");
        jdbcTemplate.update(
                "update plans set included_quantity = 0, overage_unit_amount_minor = 100, overage_allowed = true where code = 'FREE'");
        mockMvc.perform(post("/api/usage/events")
                        .header("X-API-Key", rawKey)
                        .header("Idempotency-Key", "refund-guard-usage")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"metric":"REQUEST","quantity":1}
                                """))
                .andExpect(status().isCreated());

        JsonNode invoice = json(mockMvc.perform(post(
                                "/api/organizations/{orgId}/invoices/generate?period={period}",
                                organizationId,
                                currentPeriod())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated())
                .andReturn());
        UUID invoiceId = UUID.fromString(invoice.get("id").asText());
        String prematureRefundBody = """
                {"providerEventId":"evt-refund-before-payment","type":"payment.refunded","invoiceId":"%s","amountMinor":100,"currency":"USD"}
                """.formatted(invoiceId);

        mockMvc.perform(post("/api/webhooks/payments")
                        .header("X-Webhook-Signature", webhookSignature(prematureRefundBody))
                        .contentType(APPLICATION_JSON)
                        .content(prematureRefundBody))
                .andExpect(status().isBadRequest());

        String paidBody = """
                {"providerEventId":"evt-paid-before-over-refund","type":"payment.succeeded","invoiceId":"%s","amountMinor":100,"currency":"USD"}
                """.formatted(invoiceId);
        mockMvc.perform(post("/api/webhooks/payments")
                        .header("X-Webhook-Signature", webhookSignature(paidBody))
                        .contentType(APPLICATION_JSON)
                        .content(paidBody))
                .andExpect(status().isOk());

        String overRefundBody = """
                {"providerEventId":"evt-over-refund","type":"payment.refunded","invoiceId":"%s","amountMinor":101,"currency":"USD"}
                """.formatted(invoiceId);
        mockMvc.perform(post("/api/webhooks/payments")
                        .header("X-Webhook-Signature", webhookSignature(overRefundBody))
                        .contentType(APPLICATION_JSON)
                        .content(overRefundBody))
                .andExpect(status().isBadRequest());

        Integer refundPaymentCount = jdbcTemplate.queryForObject(
                "select count(*) from payments where invoice_id = ? and status = 'REFUNDED'",
                Integer.class,
                invoiceId);

        assertThat(refundPaymentCount).isZero();
    }

    @Test
    void parallelRefundWebhooksCannotExceedCapturedPaymentAmount() throws Exception {
        String token = signup("parallel-refund-owner@example.com");
        UUID organizationId = createOrganization(token, "Parallel Refund Org");
        String rawKey = createApiKey(token, organizationId, "primary");
        jdbcTemplate.update(
                "update plans set included_quantity = 0, overage_unit_amount_minor = 100, overage_allowed = true where code = 'FREE'");
        mockMvc.perform(post("/api/usage/events")
                        .header("X-API-Key", rawKey)
                        .header("Idempotency-Key", "parallel-refund-usage")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"metric":"REQUEST","quantity":1}
                                """))
                .andExpect(status().isCreated());

        JsonNode invoice = json(mockMvc.perform(post(
                                "/api/organizations/{orgId}/invoices/generate?period={period}",
                                organizationId,
                                currentPeriod())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated())
                .andReturn());
        UUID invoiceId = UUID.fromString(invoice.get("id").asText());
        String paidBody = """
                {"providerEventId":"evt-paid-before-parallel-refund","type":"payment.succeeded","invoiceId":"%s","amountMinor":100,"currency":"USD"}
                """.formatted(invoiceId);
        mockMvc.perform(post("/api/webhooks/payments")
                        .header("X-Webhook-Signature", webhookSignature(paidBody))
                        .contentType(APPLICATION_JSON)
                        .content(paidBody))
                .andExpect(status().isOk());

        String firstRefundBody = """
                {"providerEventId":"evt-parallel-refund-1","type":"payment.refunded","invoiceId":"%s","amountMinor":60,"currency":"USD"}
                """.formatted(invoiceId);
        String secondRefundBody = """
                {"providerEventId":"evt-parallel-refund-2","type":"payment.refunded","invoiceId":"%s","amountMinor":60,"currency":"USD"}
                """.formatted(invoiceId);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch readyLatch = new CountDownLatch(2);
        CountDownLatch startLatch = new CountDownLatch(1);
        try {
            List<Future<Integer>> futures = List.of(
                    executor.submit(() -> postRefundAfterStart(firstRefundBody, readyLatch, startLatch)),
                    executor.submit(() -> postRefundAfterStart(secondRefundBody, readyLatch, startLatch)));

            assertThat(readyLatch.await(5, TimeUnit.SECONDS)).isTrue();
            startLatch.countDown();

            List<Integer> statuses = futures.stream()
                    .map(future -> {
                        try {
                            return future.get(10, TimeUnit.SECONDS);
                        } catch (Exception exception) {
                            throw new RuntimeException(exception);
                        }
                    })
                    .toList();

            assertThat(statuses).containsExactlyInAnyOrder(200, 400);
        } finally {
            executor.shutdownNow();
        }

        Integer refundPaymentCount = jdbcTemplate.queryForObject(
                "select count(*) from payments where invoice_id = ? and status = 'REFUNDED'",
                Integer.class,
                invoiceId);
        Long refundedAmount = jdbcTemplate.queryForObject(
                "select coalesce(sum(amount_minor), 0) from payments where invoice_id = ? and status = 'REFUNDED'",
                Long.class,
                invoiceId);

        assertThat(refundPaymentCount).isEqualTo(1);
        assertThat(refundedAmount).isEqualTo(60);
    }

    private int postRefundAfterStart(
            String refundBody,
            CountDownLatch readyLatch,
            CountDownLatch startLatch) throws Exception {
        readyLatch.countDown();
        assertThat(startLatch.await(5, TimeUnit.SECONDS)).isTrue();
        return mockMvc.perform(post("/api/webhooks/payments")
                        .header("X-Webhook-Signature", webhookSignature(refundBody))
                        .contentType(APPLICATION_JSON)
                        .content(refundBody))
                .andReturn()
                .getResponse()
                .getStatus();
    }

    @Test
    void invalidWebhookSignatureIsRejectedAndFailedPaymentMarksInvoice() throws Exception {
        String token = signup("owner@example.com");
        UUID organizationId = createOrganization(token, "Failed Payment Org");
        JsonNode invoice = json(mockMvc.perform(post(
                                "/api/organizations/{orgId}/invoices/generate?period={period}",
                                organizationId,
                                currentPeriod())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated())
                .andReturn());
        UUID invoiceId = UUID.fromString(invoice.get("id").asText());
        String body = """
                {"providerEventId":"evt-failed-1","type":"payment.failed","invoiceId":"%s","amountMinor":0,"currency":"USD"}
                """.formatted(invoiceId);

        mockMvc.perform(post("/api/webhooks/payments")
                        .header("X-Webhook-Signature", "bad-signature")
                        .contentType(APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/webhooks/payments")
                        .header("X-Webhook-Signature", webhookSignature(body))
                        .contentType(APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());

        String invoiceStatus = jdbcTemplate.queryForObject(
                "select status from invoices where id = ?",
                String.class,
                invoiceId);
        assertThat(invoiceStatus).isEqualTo("PAYMENT_FAILED");
    }

    @Test
    void auditLogDoesNotLeakRawApiKey() throws Exception {
        String token = signup("owner@example.com");
        UUID organizationId = createOrganization(token, "Audit Org");
        String rawKey = createApiKey(token, organizationId, "primary");

        String metadata = jdbcTemplate.queryForObject(
                """
                select metadata::text
                  from audit_logs
                 where organization_id = ?
                   and action = 'API_KEY_CREATED'
                 order by created_at desc
                 limit 1
                """,
                String.class,
                organizationId);

        assertThat(metadata).doesNotContain(rawKey);
    }
}
