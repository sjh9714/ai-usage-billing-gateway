package io.github.sungjh.aiusagebillinggateway.repository;

import io.github.sungjh.aiusagebillinggateway.domain.UsageEvent;
import io.github.sungjh.aiusagebillinggateway.domain.UsageMetric;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UsageEventRepository extends JpaRepository<UsageEvent, UUID> {

    Optional<UsageEvent> findByOrganizationIdAndIdempotencyKey(UUID organizationId, String idempotencyKey);

    @Modifying
    @Query(value = """
            insert into usage_events (
                id,
                organization_id,
                api_key_id,
                idempotency_key,
                request_hash,
                metric,
                quantity,
                occurred_at,
                metadata,
                created_at
            ) values (
                :id,
                :organizationId,
                :apiKeyId,
                :idempotencyKey,
                :requestHash,
                :metric,
                :quantity,
                :occurredAt,
                cast(:metadata as jsonb),
                now()
            )
            on conflict (organization_id, idempotency_key) do nothing
            """, nativeQuery = true)
    int insertIfAbsent(
            @Param("id") UUID id,
            @Param("organizationId") UUID organizationId,
            @Param("apiKeyId") UUID apiKeyId,
            @Param("idempotencyKey") String idempotencyKey,
            @Param("requestHash") String requestHash,
            @Param("metric") String metric,
            @Param("quantity") long quantity,
            @Param("occurredAt") Instant occurredAt,
            @Param("metadata") String metadata);

    @Query("""
            select coalesce(sum(u.quantity), 0)
              from UsageEvent u
             where u.organizationId = :organizationId
               and u.metric = :metric
               and u.occurredAt >= :from
               and u.occurredAt < :to
            """)
    long sumQuantity(
            @Param("organizationId") UUID organizationId,
            @Param("metric") UsageMetric metric,
            @Param("from") Instant from,
            @Param("to") Instant to);
}
