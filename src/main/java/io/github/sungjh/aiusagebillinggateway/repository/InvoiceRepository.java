package io.github.sungjh.aiusagebillinggateway.repository;

import io.github.sungjh.aiusagebillinggateway.domain.Invoice;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface InvoiceRepository extends JpaRepository<Invoice, UUID> {

    Optional<Invoice> findByOrganizationIdAndBillingPeriod(UUID organizationId, String billingPeriod);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from Invoice i where i.id = :invoiceId")
    Optional<Invoice> findByIdForUpdate(@Param("invoiceId") UUID invoiceId);
}
