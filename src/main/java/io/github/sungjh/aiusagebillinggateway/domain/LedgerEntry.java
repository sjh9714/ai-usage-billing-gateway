package io.github.sungjh.aiusagebillinggateway.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "ledger_entries")
public class LedgerEntry {

    @Id
    private UUID id;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "invoice_id")
    private UUID invoiceId;

    @Column(name = "payment_id")
    private UUID paymentId;

    @Column(name = "transaction_group_id", nullable = false)
    private String transactionGroupId;

    @Column(name = "original_transaction_group_id")
    private String originalTransactionGroupId;

    @Column(nullable = false)
    private String type;

    @Column(nullable = false)
    private String account;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private LedgerDirection direction;

    @Column(name = "amount_minor", nullable = false)
    private long amountMinor;

    @Column(nullable = false)
    private String currency;

    @Column(name = "idempotency_key", nullable = false, unique = true)
    private String idempotencyKey;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected LedgerEntry() {
    }

    public LedgerEntry(
            UUID organizationId,
            UUID invoiceId,
            UUID paymentId,
            String transactionGroupId,
            String type,
            String account,
            LedgerDirection direction,
            long amountMinor,
            String currency,
            String idempotencyKey) {
        this(
                organizationId,
                invoiceId,
                paymentId,
                transactionGroupId,
                null,
                type,
                account,
                direction,
                amountMinor,
                currency,
                idempotencyKey);
    }

    public LedgerEntry(
            UUID organizationId,
            UUID invoiceId,
            UUID paymentId,
            String transactionGroupId,
            String originalTransactionGroupId,
            String type,
            String account,
            LedgerDirection direction,
            long amountMinor,
            String currency,
            String idempotencyKey) {
        this.id = UUID.randomUUID();
        this.organizationId = organizationId;
        this.invoiceId = invoiceId;
        this.paymentId = paymentId;
        this.transactionGroupId = transactionGroupId;
        this.originalTransactionGroupId = originalTransactionGroupId;
        this.type = type;
        this.account = account;
        this.direction = direction;
        this.amountMinor = amountMinor;
        this.currency = currency;
        this.idempotencyKey = idempotencyKey;
        this.createdAt = Instant.now();
    }

    public LedgerDirection getDirection() {
        return direction;
    }

    public long getAmountMinor() {
        return amountMinor;
    }

    public String getCurrency() {
        return currency;
    }

    public String getOriginalTransactionGroupId() {
        return originalTransactionGroupId;
    }
}
