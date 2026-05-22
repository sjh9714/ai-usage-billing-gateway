package io.github.sungjh.aiusagebillinggateway.repository;

import io.github.sungjh.aiusagebillinggateway.domain.Payment;
import io.github.sungjh.aiusagebillinggateway.domain.PaymentStatus;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    Optional<Payment> findByProviderEventId(String providerEventId);

    boolean existsByInvoiceIdAndStatus(UUID invoiceId, PaymentStatus status);

    Optional<Payment> findFirstByInvoiceIdAndStatusAndCurrencyOrderByCreatedAtAsc(
            UUID invoiceId,
            PaymentStatus status,
            String currency);

    @Query("""
            select coalesce(sum(p.amountMinor), 0)
              from Payment p
             where p.invoiceId = :invoiceId
               and p.status = :status
               and p.currency = :currency
            """)
    long sumAmountMinor(
            @Param("invoiceId") UUID invoiceId,
            @Param("status") PaymentStatus status,
            @Param("currency") String currency);
}
