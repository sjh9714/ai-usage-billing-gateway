package io.github.sungjh.aiusagebillinggateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.sungjh.aiusagebillinggateway.billing.MonthlyInvoiceScheduler;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties = "billing.invoice-scheduler.enabled=true")
class MonthlyInvoiceSchedulerIT extends IntegrationTestSupport {

    @Autowired
    MonthlyInvoiceScheduler scheduler;

    @Test
    void schedulerGeneratesPreviousMonthInvoicesIdempotently() throws Exception {
        String token = signup("scheduler-owner@example.com");
        UUID organizationId = createOrganization(token, "Scheduler Billing Org");
        String rawKey = createApiKey(token, organizationId, "primary");
        jdbcTemplate.update(
                "update plans set included_quantity = 1, overage_unit_amount_minor = 25, overage_allowed = true where code = 'FREE'");

        YearMonth previousMonth = YearMonth.now(ZoneOffset.UTC).minusMonths(1);
        for (int index = 1; index <= 4; index++) {
            mockMvc.perform(post("/api/usage/events")
                            .header("X-API-Key", rawKey)
                            .header("Idempotency-Key", "scheduler-bill-" + index)
                            .contentType(APPLICATION_JSON)
                            .content(
                                    """
                                    {"metric":"REQUEST","quantity":1,"occurredAt":"%s"}
                                    """
                                            .formatted(previousMonth
                                                    .atDay(15)
                                                    .atStartOfDay()
                                                    .toInstant(ZoneOffset.UTC))))
                    .andExpect(status().isCreated());
        }

        scheduler.generatePreviousMonthInvoices();
        scheduler.generatePreviousMonthInvoices();

        Integer invoiceCount = jdbcTemplate.queryForObject(
                "select count(*) from invoices where organization_id = ? and billing_period = ?",
                Integer.class,
                organizationId,
                previousMonth.toString());
        Long totalAmount = jdbcTemplate.queryForObject(
                "select total_amount_minor from invoices where organization_id = ? and billing_period = ?",
                Long.class,
                organizationId,
                previousMonth.toString());

        assertThat(invoiceCount).isEqualTo(1);
        assertThat(totalAmount).isEqualTo(75);
    }
}
