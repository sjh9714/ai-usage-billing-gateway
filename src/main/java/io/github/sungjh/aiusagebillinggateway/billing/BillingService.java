package io.github.sungjh.aiusagebillinggateway.billing;

import io.github.sungjh.aiusagebillinggateway.audit.AuditService;
import io.github.sungjh.aiusagebillinggateway.domain.Invoice;
import io.github.sungjh.aiusagebillinggateway.domain.InvoiceItem;
import io.github.sungjh.aiusagebillinggateway.domain.Plan;
import io.github.sungjh.aiusagebillinggateway.domain.Subscription;
import io.github.sungjh.aiusagebillinggateway.domain.UsageMetric;
import io.github.sungjh.aiusagebillinggateway.ledger.LedgerService;
import io.github.sungjh.aiusagebillinggateway.observability.MetricsService;
import io.github.sungjh.aiusagebillinggateway.organization.TenantAccessService;
import io.github.sungjh.aiusagebillinggateway.repository.InvoiceItemRepository;
import io.github.sungjh.aiusagebillinggateway.repository.InvoiceRepository;
import io.github.sungjh.aiusagebillinggateway.repository.PlanRepository;
import io.github.sungjh.aiusagebillinggateway.repository.SubscriptionRepository;
import io.github.sungjh.aiusagebillinggateway.repository.UsageEventRepository;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class BillingService {

    private final InvoiceRepository invoiceRepository;
    private final InvoiceItemRepository invoiceItemRepository;
    private final UsageEventRepository usageEventRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final PlanRepository planRepository;
    private final TenantAccessService tenantAccessService;
    private final LedgerService ledgerService;
    private final AuditService auditService;
    private final MetricsService metricsService;

    public BillingService(
            InvoiceRepository invoiceRepository,
            InvoiceItemRepository invoiceItemRepository,
            UsageEventRepository usageEventRepository,
            SubscriptionRepository subscriptionRepository,
            PlanRepository planRepository,
            TenantAccessService tenantAccessService,
            LedgerService ledgerService,
            AuditService auditService,
            MetricsService metricsService) {
        this.invoiceRepository = invoiceRepository;
        this.invoiceItemRepository = invoiceItemRepository;
        this.usageEventRepository = usageEventRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.planRepository = planRepository;
        this.tenantAccessService = tenantAccessService;
        this.ledgerService = ledgerService;
        this.auditService = auditService;
        this.metricsService = metricsService;
    }

    @Transactional
    public InvoiceResponse generate(UUID actorUserId, UUID organizationId, YearMonth period) {
        tenantAccessService.requireAdmin(organizationId, actorUserId);
        return invoiceRepository.findByOrganizationIdAndBillingPeriod(organizationId, period.toString())
                .map(invoice -> InvoiceResponse.from(invoice, true))
                .orElseGet(() -> createInvoice(actorUserId, organizationId, period));
    }

    @Transactional
    public InvoiceResponse generateForScheduler(UUID organizationId, YearMonth period) {
        return invoiceRepository.findByOrganizationIdAndBillingPeriod(organizationId, period.toString())
                .map(invoice -> InvoiceResponse.from(invoice, true))
                .orElseGet(() -> createInvoice(null, organizationId, period));
    }

    private InvoiceResponse createInvoice(UUID actorUserId, UUID organizationId, YearMonth period) {
        try {
            Subscription subscription = subscriptionRepository.findByOrganizationId(organizationId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Subscription not found"));
            Plan plan = planRepository.findById(subscription.getPlanId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Plan not found"));
            long usage = usageEventRepository.sumQuantity(
                    organizationId,
                    UsageMetric.REQUEST,
                    period.atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC),
                    period.plusMonths(1).atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC));
            long overageQuantity = Math.max(0, usage - plan.getIncludedQuantity());
            long overageAmount = overageQuantity * plan.getOverageUnitAmountMinor();
            long total = plan.getBaseAmountMinor() + overageAmount;
            Invoice invoice = invoiceRepository.save(new Invoice(
                    organizationId,
                    period.toString(),
                    total,
                    plan.getCurrency()));
            if (plan.getBaseAmountMinor() > 0) {
                invoiceItemRepository.save(new InvoiceItem(invoice.getId(), "Plan base fee", 1, plan.getBaseAmountMinor()));
            }
            if (overageAmount > 0) {
                invoiceItemRepository.save(new InvoiceItem(
                        invoice.getId(),
                        "REQUEST overage",
                        overageQuantity,
                        plan.getOverageUnitAmountMinor()));
            }
            ledgerService.recordInvoiceIssued(invoice);
            auditService.record(
                    organizationId,
                    actorUserId,
                    "INVOICE_GENERATED",
                    "Invoice",
                    invoice.getId(),
                    Map.of("period", period.toString(), "totalAmountMinor", total));
            metricsService.invoiceGenerated();
            return InvoiceResponse.from(invoice, false);
        } catch (DataIntegrityViolationException exception) {
            metricsService.invoiceFailed();
            return invoiceRepository.findByOrganizationIdAndBillingPeriod(organizationId, period.toString())
                    .map(invoice -> InvoiceResponse.from(invoice, true))
                    .orElseThrow(() -> exception);
        }
    }
}
