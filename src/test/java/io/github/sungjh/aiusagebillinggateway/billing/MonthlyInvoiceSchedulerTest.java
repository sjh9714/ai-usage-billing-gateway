package io.github.sungjh.aiusagebillinggateway.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import io.github.sungjh.aiusagebillinggateway.domain.Subscription;
import io.github.sungjh.aiusagebillinggateway.domain.SubscriptionStatus;
import io.github.sungjh.aiusagebillinggateway.repository.SubscriptionRepository;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;

class MonthlyInvoiceSchedulerTest {

    private final BillingService billingService = mock(BillingService.class);
    private final SubscriptionRepository subscriptionRepository = mock(SubscriptionRepository.class);
    private final MonthlyInvoiceScheduler scheduler =
            new MonthlyInvoiceScheduler(billingService, subscriptionRepository);

    @Test
    void generatesOnlyDistinctActiveSubscriptionOrganizations() {
        UUID activeOrganizationId = UUID.randomUUID();
        UUID canceledOrganizationId = UUID.randomUUID();
        YearMonth period = YearMonth.of(2026, 4);
        Subscription activePrimary = new Subscription(activeOrganizationId, UUID.randomUUID());
        Subscription activeDuplicate = new Subscription(activeOrganizationId, UUID.randomUUID());
        Subscription canceled = new Subscription(canceledOrganizationId, UUID.randomUUID());
        ReflectionTestUtils.setField(canceled, "status", SubscriptionStatus.CANCELED);
        when(subscriptionRepository.findAll())
                .thenReturn(List.of(activePrimary, activeDuplicate, canceled));

        scheduler.generateInvoicesForPeriod(period);

        verify(billingService).generateForScheduler(activeOrganizationId, period);
        verify(billingService, never())
                .generateForScheduler(canceledOrganizationId, period);
        verifyNoMoreInteractions(billingService);
    }

    @Test
    void continuesRemainingOrganizationsThenThrowsSummaryWhenOneOrganizationFails() {
        UUID failedOrganizationId = UUID.randomUUID();
        UUID successfulOrganizationId = UUID.randomUUID();
        YearMonth period = YearMonth.of(2026, 4);
        when(subscriptionRepository.findAll()).thenReturn(List.of(
                new Subscription(failedOrganizationId, UUID.randomUUID()),
                new Subscription(successfulOrganizationId, UUID.randomUUID())));
        doThrow(new IllegalStateException("invoice failed"))
                .when(billingService)
                .generateForScheduler(failedOrganizationId, period);

        assertThatThrownBy(() -> scheduler.generateInvoicesForPeriod(period))
                .isInstanceOf(MonthlyInvoiceScheduler.MonthlyInvoiceSchedulerException.class)
                .satisfies(exception -> {
                    MonthlyInvoiceScheduler.MonthlyInvoiceSchedulerException schedulerException =
                            (MonthlyInvoiceScheduler.MonthlyInvoiceSchedulerException) exception;
                    assertThat(schedulerException.getPeriod()).isEqualTo(period);
                    assertThat(schedulerException.getFailedOrganizationIds())
                            .containsExactly(failedOrganizationId);
                });

        InOrder inOrder = inOrder(billingService);
        inOrder.verify(billingService).generateForScheduler(failedOrganizationId, period);
        inOrder.verify(billingService).generateForScheduler(successfulOrganizationId, period);
    }
}
