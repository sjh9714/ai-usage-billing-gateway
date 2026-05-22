package io.github.sungjh.aiusagebillinggateway.billing;

import io.github.sungjh.aiusagebillinggateway.domain.Subscription;
import io.github.sungjh.aiusagebillinggateway.domain.SubscriptionStatus;
import io.github.sungjh.aiusagebillinggateway.repository.SubscriptionRepository;
import java.util.ArrayList;
import java.util.List;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component
@ConditionalOnProperty(name = "billing.invoice-scheduler.enabled", havingValue = "true")
public class MonthlyInvoiceScheduler {

    private static final Logger log = LoggerFactory.getLogger(MonthlyInvoiceScheduler.class);

    private final BillingService billingService;
    private final SubscriptionRepository subscriptionRepository;

    public MonthlyInvoiceScheduler(
            BillingService billingService,
            SubscriptionRepository subscriptionRepository) {
        this.billingService = billingService;
        this.subscriptionRepository = subscriptionRepository;
    }

    @Scheduled(cron = "${billing.invoice-scheduler.cron:0 10 0 1 * *}", zone = "UTC")
    public void generatePreviousMonthInvoices() {
        generateInvoicesForPeriod(YearMonth.now(ZoneOffset.UTC).minusMonths(1));
    }

    public void generateInvoicesForPeriod(YearMonth period) {
        List<UUID> organizationIds = subscriptionRepository.findAll().stream()
                .filter(subscription -> subscription.getStatus() == SubscriptionStatus.ACTIVE)
                .map(Subscription::getOrganizationId)
                .distinct()
                .toList();
        List<UUID> failedOrganizationIds = new ArrayList<>();

        for (UUID organizationId : organizationIds) {
            try {
                billingService.generateForScheduler(organizationId, period);
            } catch (RuntimeException exception) {
                failedOrganizationIds.add(organizationId);
                log.warn(
                        "Monthly invoice generation failed: organizationId={}, period={}",
                        organizationId,
                        period,
                        exception);
            }
        }

        if (!failedOrganizationIds.isEmpty()) {
            throw new MonthlyInvoiceSchedulerException(period, failedOrganizationIds);
        }
    }

    public static class MonthlyInvoiceSchedulerException extends RuntimeException {

        private final YearMonth period;
        private final List<UUID> failedOrganizationIds;

        MonthlyInvoiceSchedulerException(YearMonth period, List<UUID> failedOrganizationIds) {
            super("Monthly invoice generation failed for %d organization(s) in period %s"
                    .formatted(failedOrganizationIds.size(), period));
            this.period = period;
            this.failedOrganizationIds = List.copyOf(failedOrganizationIds);
        }

        public YearMonth getPeriod() {
            return period;
        }

        public List<UUID> getFailedOrganizationIds() {
            return failedOrganizationIds;
        }
    }
}
