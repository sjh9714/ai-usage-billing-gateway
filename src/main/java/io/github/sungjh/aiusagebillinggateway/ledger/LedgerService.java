package io.github.sungjh.aiusagebillinggateway.ledger;

import io.github.sungjh.aiusagebillinggateway.audit.AuditService;
import io.github.sungjh.aiusagebillinggateway.domain.Invoice;
import io.github.sungjh.aiusagebillinggateway.domain.LedgerDirection;
import io.github.sungjh.aiusagebillinggateway.domain.LedgerEntry;
import io.github.sungjh.aiusagebillinggateway.domain.Payment;
import io.github.sungjh.aiusagebillinggateway.observability.MetricsService;
import io.github.sungjh.aiusagebillinggateway.repository.LedgerEntryRepository;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

@Service
public class LedgerService {

    private final LedgerEntryRepository ledgerEntryRepository;
    private final AuditService auditService;
    private final MetricsService metricsService;

    public LedgerService(
            LedgerEntryRepository ledgerEntryRepository,
            AuditService auditService,
            MetricsService metricsService) {
        this.ledgerEntryRepository = ledgerEntryRepository;
        this.auditService = auditService;
        this.metricsService = metricsService;
    }

    public void recordInvoiceIssued(Invoice invoice) {
        if (invoice.getTotalAmountMinor() <= 0) {
            return;
        }
        String group = "invoice:" + invoice.getId() + ":issued";
        appendBalanced(List.of(
                new LedgerEntry(
                        invoice.getOrganizationId(),
                        invoice.getId(),
                        null,
                        group,
                        "INVOICE_ISSUED",
                        "ACCOUNTS_RECEIVABLE",
                        LedgerDirection.DEBIT,
                        invoice.getTotalAmountMinor(),
                        invoice.getCurrency(),
                        group + ":receivable"),
                new LedgerEntry(
                        invoice.getOrganizationId(),
                        invoice.getId(),
                        null,
                        group,
                        "INVOICE_ISSUED",
                        "REVENUE",
                        LedgerDirection.CREDIT,
                        invoice.getTotalAmountMinor(),
                        invoice.getCurrency(),
                        group + ":revenue")));
        auditService.record(
                invoice.getOrganizationId(),
                null,
                "LEDGER_ENTRY_GROUP_CREATED",
                "Invoice",
                invoice.getId(),
                Map.of("transactionGroupId", group));
    }

    public void recordPaymentSucceeded(Invoice invoice, Payment payment) {
        if (payment.getAmountMinor() <= 0) {
            return;
        }
        String group = "payment:" + payment.getId() + ":succeeded";
        appendBalanced(List.of(
                new LedgerEntry(
                        invoice.getOrganizationId(),
                        invoice.getId(),
                        payment.getId(),
                        group,
                        "PAYMENT_SUCCEEDED",
                        "CASH",
                        LedgerDirection.DEBIT,
                        payment.getAmountMinor(),
                        payment.getCurrency(),
                        group + ":cash"),
                new LedgerEntry(
                        invoice.getOrganizationId(),
                        invoice.getId(),
                        payment.getId(),
                        group,
                        "PAYMENT_SUCCEEDED",
                        "ACCOUNTS_RECEIVABLE",
                        LedgerDirection.CREDIT,
                        payment.getAmountMinor(),
                        payment.getCurrency(),
                        group + ":receivable")));
        auditService.record(
                invoice.getOrganizationId(),
                null,
                "LEDGER_ENTRY_GROUP_CREATED",
                "Payment",
                payment.getId(),
                Map.of("transactionGroupId", group));
    }

    public void recordPaymentRefunded(Invoice invoice, Payment payment, Payment originalPayment) {
        if (payment.getAmountMinor() <= 0) {
            return;
        }
        String group = "payment:" + payment.getId() + ":refunded";
        String originalGroup = "payment:" + originalPayment.getId() + ":succeeded";
        appendBalanced(List.of(
                new LedgerEntry(
                        invoice.getOrganizationId(),
                        invoice.getId(),
                        payment.getId(),
                        group,
                        originalGroup,
                        "PAYMENT_REFUNDED",
                        "ACCOUNTS_RECEIVABLE",
                        LedgerDirection.DEBIT,
                        payment.getAmountMinor(),
                        payment.getCurrency(),
                        group + ":receivable"),
                new LedgerEntry(
                        invoice.getOrganizationId(),
                        invoice.getId(),
                        payment.getId(),
                        group,
                        originalGroup,
                        "PAYMENT_REFUNDED",
                        "CASH",
                        LedgerDirection.CREDIT,
                        payment.getAmountMinor(),
                        payment.getCurrency(),
                        group + ":cash")));
        auditService.record(
                invoice.getOrganizationId(),
                null,
                "LEDGER_ENTRY_GROUP_CREATED",
                "Payment",
                payment.getId(),
                Map.of("transactionGroupId", group));
    }

    private void appendBalanced(List<LedgerEntry> entries) {
        long debit = entries.stream()
                .filter(entry -> entry.getDirection() == LedgerDirection.DEBIT)
                .mapToLong(LedgerEntry::getAmountMinor)
                .sum();
        long credit = entries.stream()
                .filter(entry -> entry.getDirection() == LedgerDirection.CREDIT)
                .mapToLong(LedgerEntry::getAmountMinor)
                .sum();
        if (debit != credit) {
            throw new IllegalStateException("Unbalanced ledger entries");
        }
        Set<String> currencies = entries.stream()
                .map(LedgerEntry::getCurrency)
                .collect(Collectors.toSet());
        if (currencies.size() != 1) {
            throw new IllegalStateException("Ledger entry group must use a single currency");
        }
        boolean hasNonPositiveAmount = entries.stream()
                .anyMatch(entry -> entry.getAmountMinor() <= 0);
        if (hasNonPositiveAmount) {
            throw new IllegalStateException("Ledger entry amounts must be positive");
        }
        for (LedgerEntry entry : entries) {
            ledgerEntryRepository.save(entry);
            metricsService.ledgerEntryCreated();
        }
        metricsService.ledgerGroupCreated();
    }
}
