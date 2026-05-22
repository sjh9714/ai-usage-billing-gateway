package io.github.sungjh.aiusagebillinggateway.repository;

import io.github.sungjh.aiusagebillinggateway.domain.UsageMetric;
import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
public class QuotaCounterRepository {

    private final EntityManager entityManager;

    public QuotaCounterRepository(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    public void ensureCounter(UUID organizationId, UsageMetric metric, LocalDate periodStart) {
        entityManager.createNativeQuery("""
                insert into quota_counters (
                    organization_id,
                    metric,
                    period_start,
                    used_quantity,
                    created_at,
                    updated_at
                ) values (
                    :organizationId,
                    :metric,
                    :periodStart,
                    0,
                    now(),
                    now()
                )
                on conflict (organization_id, metric, period_start) do nothing
                """)
                .setParameter("organizationId", organizationId)
                .setParameter("metric", metric.name())
                .setParameter("periodStart", periodStart)
                .executeUpdate();
    }

    public int reserve(
            UUID organizationId,
            UsageMetric metric,
            LocalDate periodStart,
            long quantity,
            long includedQuantity) {
        return entityManager.createNativeQuery("""
                update quota_counters
                   set used_quantity = used_quantity + :quantity,
                       updated_at = now()
                 where organization_id = :organizationId
                   and metric = :metric
                   and period_start = :periodStart
                   and used_quantity + :quantity <= :includedQuantity
                """)
                .setParameter("organizationId", organizationId)
                .setParameter("metric", metric.name())
                .setParameter("periodStart", periodStart)
                .setParameter("quantity", quantity)
                .setParameter("includedQuantity", includedQuantity)
                .executeUpdate();
    }
}
