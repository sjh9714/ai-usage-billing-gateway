package io.github.sungjh.aiusagebillinggateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

class ApiKeyUsageQuotaIT extends IntegrationTestSupport {

    @Test
    void apiKeyRawValueIsShownOnceAndNotStored() throws Exception {
        String token = signup("owner@example.com");
        UUID organizationId = createOrganization(token, "Keys Org");
        String rawKey = createApiKey(token, organizationId, "primary");

        assertThat(rawKey).startsWith("ak_");
        Integer plaintextMatches = jdbcTemplate.queryForObject(
                "select count(*) from api_keys where key_hash = ? or key_prefix = ?",
                Integer.class,
                rawKey,
                rawKey);
        assertThat(plaintextMatches).isZero();

        mockMvc.perform(get("/api/organizations/{orgId}/api-keys", organizationId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].rawApiKey").doesNotExist())
                .andExpect(jsonPath("$[0].keyPrefix").exists());
    }

    @Test
    void validApiKeyCanCallGatewayAndRevokedKeyIsRejected() throws Exception {
        String token = signup("owner@example.com");
        UUID organizationId = createOrganization(token, "Gateway Org");
        String rawKey = createApiKey(token, organizationId, "primary");
        UUID keyId = jdbcTemplate.queryForObject(
                "select id from api_keys where organization_id = ?",
                UUID.class,
                organizationId);

        mockMvc.perform(post("/v1/gateway/mock-completion")
                        .header("X-API-Key", rawKey)
                        .header("Idempotency-Key", "gateway-valid-1")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"prompt":"hello"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.provider").value("mock"));

        mockMvc.perform(delete("/api/organizations/{orgId}/api-keys/{keyId}", organizationId, keyId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());

        mockMvc.perform(post("/v1/gateway/mock-completion")
                        .header("X-API-Key", rawKey)
                        .header("Idempotency-Key", "gateway-revoked-1")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"prompt":"hello again"}
                                """))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void gatewayRetryWithSameIdempotencyKeyDoesNotCreateDuplicateUsage() throws Exception {
        String token = signup("gateway-idempotent@example.com");
        UUID organizationId = createOrganization(token, "Gateway Idempotent Org");
        String rawKey = createApiKey(token, organizationId, "primary");

        String payload = """
                {"prompt":"same prompt"}
                """;

        mockMvc.perform(post("/v1/gateway/mock-completion")
                        .header("X-API-Key", rawKey)
                        .header("Idempotency-Key", "gateway-retry-1")
                        .contentType(APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.duplicate").value(false));

        mockMvc.perform(post("/v1/gateway/mock-completion")
                        .header("X-API-Key", rawKey)
                        .header("Idempotency-Key", "gateway-retry-1")
                        .contentType(APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.duplicate").value(true));

        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from usage_events where organization_id = ?",
                Integer.class,
                organizationId);
        assertThat(count).isEqualTo(1);
        assertThat(requestQuotaCounter(organizationId)).isEqualTo(1);
    }

    @Test
    void gatewayRetryWithSameIdempotencyKeyRejectsPromptMismatch() throws Exception {
        String token = signup("gateway-conflict@example.com");
        UUID organizationId = createOrganization(token, "Gateway Conflict Org");
        String rawKey = createApiKey(token, organizationId, "primary");

        mockMvc.perform(post("/v1/gateway/mock-completion")
                        .header("X-API-Key", rawKey)
                        .header("Idempotency-Key", "gateway-conflict-1")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"prompt":"first prompt"}
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(post("/v1/gateway/mock-completion")
                        .header("X-API-Key", rawKey)
                        .header("Idempotency-Key", "gateway-conflict-1")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"prompt":"different prompt"}
                                """))
                .andExpect(status().isConflict());
    }

    @Test
    void gatewayIdempotentRetryIsResolvedBeforeQuotaCheck() throws Exception {
        String token = signup("gateway-quota-retry@example.com");
        UUID organizationId = createOrganization(token, "Gateway Quota Retry Org");
        String rawKey = createApiKey(token, organizationId, "primary");
        jdbcTemplate.update("update plans set included_quantity = 1 where code = 'FREE'");

        String payload = """
                {"prompt":"last allowed request"}
                """;

        mockMvc.perform(post("/v1/gateway/mock-completion")
                        .header("X-API-Key", rawKey)
                        .header("Idempotency-Key", "gateway-last-quota-1")
                        .contentType(APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.duplicate").value(false));

        mockMvc.perform(post("/v1/gateway/mock-completion")
                        .header("X-API-Key", rawKey)
                        .header("Idempotency-Key", "gateway-last-quota-1")
                        .contentType(APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.duplicate").value(true));
    }

    @Test
    void parallelGatewayRetriesWithSameIdempotencyKeyStayIdempotentAtQuotaBoundary() throws Exception {
        String token = signup("gateway-parallel-retry@example.com");
        UUID organizationId = createOrganization(token, "Gateway Parallel Retry Org");
        String rawKey = createApiKey(token, organizationId, "primary");
        jdbcTemplate.update("update plans set included_quantity = 1 where code = 'FREE'");

        int requestCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(requestCount);
        CountDownLatch readyLatch = new CountDownLatch(requestCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();

        for (int index = 0; index < requestCount; index++) {
            futures.add(executor.submit(() -> {
                readyLatch.countDown();
                startLatch.await();
                return mockMvc.perform(post("/v1/gateway/mock-completion")
                                .header("X-API-Key", rawKey)
                                .header("Idempotency-Key", "gateway-parallel-retry-key")
                                .contentType(APPLICATION_JSON)
                                .content("""
                                        {"prompt":"same prompt at quota edge"}
                                        """))
                        .andReturn()
                        .getResponse()
                        .getStatus();
            }));
        }

        readyLatch.await();
        startLatch.countDown();

        List<Integer> statuses = new ArrayList<>();
        for (Future<Integer> future : futures) {
            statuses.add(future.get());
        }
        executor.shutdown();

        Integer usageCount = jdbcTemplate.queryForObject(
                "select count(*) from usage_events where organization_id = ?",
                Integer.class,
                organizationId);

        assertThat(statuses).allMatch(status -> status == 200);
        assertThat(usageCount).isEqualTo(1);
        assertThat(requestQuotaCounter(organizationId)).isEqualTo(1);
    }

    @Test
    void usageEventIngestionIsIdempotentAndRejectsPayloadMismatch() throws Exception {
        String token = signup("owner@example.com");
        UUID organizationId = createOrganization(token, "Usage Org");
        String rawKey = createApiKey(token, organizationId, "primary");

        String payload = """
                {"metric":"REQUEST","quantity":1,"metadata":{"route":"mock"}}
                """;
        mockMvc.perform(post("/api/usage/events")
                        .header("X-API-Key", rawKey)
                        .header("Idempotency-Key", "usage-1")
                        .contentType(APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.duplicate").value(false));

        mockMvc.perform(post("/api/usage/events")
                        .header("X-API-Key", rawKey)
                        .header("Idempotency-Key", "usage-1")
                        .contentType(APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.duplicate").value(true));

        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from usage_events where organization_id = ?",
                Integer.class,
                organizationId);
        assertThat(count).isEqualTo(1);

        mockMvc.perform(post("/api/usage/events")
                        .header("X-API-Key", rawKey)
                        .header("Idempotency-Key", "usage-1")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"metric":"REQUEST","quantity":2,"metadata":{"route":"mock"}}
                                """))
                .andExpect(status().isConflict());
    }

    @Test
    void usageIdempotencyRejectsSameKeyWithDifferentOccurredAt() throws Exception {
        String token = signup("owner-occurred@example.com");
        UUID organizationId = createOrganization(token, "Usage Occurred Org");
        String rawKey = createApiKey(token, organizationId, "primary");

        mockMvc.perform(post("/api/usage/events")
                        .header("X-API-Key", rawKey)
                        .header("Idempotency-Key", "usage-occurred-at")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {
                                  "metric":"REQUEST",
                                  "quantity":1,
                                  "occurredAt":"2026-05-01T00:00:00Z",
                                  "metadata":{"route":"mock"}
                                }
                                """))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/usage/events")
                        .header("X-API-Key", rawKey)
                        .header("Idempotency-Key", "usage-occurred-at")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {
                                  "metric":"REQUEST",
                                  "quantity":1,
                                  "occurredAt":"2026-05-02T00:00:00Z",
                                  "metadata":{"route":"mock"}
                                }
                                """))
                .andExpect(status().isConflict());
    }

    @Test
    void parallelExplicitUsageRetriesWithSameIdempotencyKeyStayIdempotentAtQuotaBoundary() throws Exception {
        String token = signup("usage-parallel-retry@example.com");
        UUID organizationId = createOrganization(token, "Usage Parallel Retry Org");
        String rawKey = createApiKey(token, organizationId, "primary");
        jdbcTemplate.update("update plans set included_quantity = 1 where code = 'FREE'");

        int requestCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(requestCount);
        CountDownLatch readyLatch = new CountDownLatch(requestCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();

        for (int index = 0; index < requestCount; index++) {
            futures.add(executor.submit(() -> {
                readyLatch.countDown();
                startLatch.await();
                return mockMvc.perform(post("/api/usage/events")
                                .header("X-API-Key", rawKey)
                                .header("Idempotency-Key", "usage-parallel-retry-key")
                                .contentType(APPLICATION_JSON)
                                .content("""
                                        {"metric":"REQUEST","quantity":1}
                                        """))
                        .andReturn()
                        .getResponse()
                        .getStatus();
            }));
        }

        readyLatch.await();
        startLatch.countDown();

        List<Integer> statuses = new ArrayList<>();
        for (Future<Integer> future : futures) {
            statuses.add(future.get());
        }
        executor.shutdown();

        long createdCount = statuses.stream().filter(status -> status == 201).count();
        long duplicateCount = statuses.stream().filter(status -> status == 200).count();
        Integer usageCount = jdbcTemplate.queryForObject(
                "select count(*) from usage_events where organization_id = ?",
                Integer.class,
                organizationId);

        assertThat(createdCount).isEqualTo(1);
        assertThat(duplicateCount).isEqualTo(requestCount - 1);
        assertThat(usageCount).isEqualTo(1);
        assertThat(requestQuotaCounter(organizationId)).isEqualTo(1);
    }

    @Test
    void invalidUsageQuantityIsRejected() throws Exception {
        String token = signup("owner@example.com");
        UUID organizationId = createOrganization(token, "Invalid Usage Org");
        String rawKey = createApiKey(token, organizationId, "primary");

        mockMvc.perform(post("/api/usage/events")
                        .header("X-API-Key", rawKey)
                        .header("Idempotency-Key", "invalid-quantity")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"metric":"REQUEST","quantity":0}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void quotaAndRateLimitAreEnforcedPerTenant() throws Exception {
        String ownerToken = signup("owner@example.com");
        UUID organizationId = createOrganization(ownerToken, "Quota Org");
        String rawKey = createApiKey(ownerToken, organizationId, "primary");
        jdbcTemplate.update("update plans set included_quantity = 1 where code = 'FREE'");

        mockMvc.perform(post("/api/usage/events")
                        .header("X-API-Key", rawKey)
                        .header("Idempotency-Key", "quota-1")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"metric":"REQUEST","quantity":1}
                                """))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/v1/gateway/mock-completion")
                        .header("X-API-Key", rawKey)
                        .header("Idempotency-Key", "gateway-quota-blocked-1")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"prompt":"blocked by quota"}
                                """))
                .andExpect(status().isTooManyRequests());

        jdbcTemplate.update("update plans set included_quantity = 10000 where code = 'FREE'");
        String secondToken = signup("second@example.com");
        UUID secondOrganizationId = createOrganization(secondToken, "Rate Org");
        String secondRawKey = createApiKey(secondToken, secondOrganizationId, "primary");

        mockMvc.perform(post("/v1/gateway/mock-completion")
                        .header("X-API-Key", secondRawKey)
                        .header("Idempotency-Key", "gateway-rate-1")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"prompt":"first"}
                                """))
                .andExpect(status().isOk());
        mockMvc.perform(post("/v1/gateway/mock-completion")
                        .header("X-API-Key", secondRawKey)
                        .header("Idempotency-Key", "gateway-rate-2")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"prompt":"second"}
                                """))
                .andExpect(status().isOk());
        mockMvc.perform(post("/v1/gateway/mock-completion")
                        .header("X-API-Key", secondRawKey)
                        .header("Idempotency-Key", "gateway-rate-3")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"prompt":"third"}
                                """))
                .andExpect(status().isTooManyRequests());

        String thirdToken = signup("third@example.com");
        UUID thirdOrganizationId = createOrganization(thirdToken, "Independent Rate Org");
        String thirdRawKey = createApiKey(thirdToken, thirdOrganizationId, "primary");
        mockMvc.perform(post("/v1/gateway/mock-completion")
                        .header("X-API-Key", thirdRawKey)
                        .header("Idempotency-Key", "gateway-independent-1")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"prompt":"independent"}
                                """))
                .andExpect(status().isOk());
    }

    @Test
    void explicitUsageEventsCannotOvershootMonthlyQuota() throws Exception {
        String token = signup("usage-quota-explicit@example.com");
        UUID organizationId = createOrganization(token, "Explicit Usage Quota Org");
        String rawKey = createApiKey(token, organizationId, "primary");
        jdbcTemplate.update("update plans set included_quantity = 1 where code = 'FREE'");

        mockMvc.perform(post("/api/usage/events")
                        .header("X-API-Key", rawKey)
                        .header("Idempotency-Key", "usage-quota-explicit-1")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"metric":"REQUEST","quantity":1}
                                """))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/usage/events")
                        .header("X-API-Key", rawKey)
                        .header("Idempotency-Key", "usage-quota-explicit-2")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"metric":"REQUEST","quantity":1}
                                """))
                .andExpect(status().isTooManyRequests());

        Integer usageCount = jdbcTemplate.queryForObject(
                "select count(*) from usage_events where organization_id = ?",
                Integer.class,
                organizationId);
        assertThat(usageCount).isEqualTo(1);
        assertThat(requestQuotaCounter(organizationId)).isEqualTo(1);
    }

    @Test
    void explicitUsageQuotaIsReservedByOccurredAtUtcMonth() throws Exception {
        String token = signup("usage-quota-period@example.com");
        UUID organizationId = createOrganization(token, "Usage Quota Period Org");
        String rawKey = createApiKey(token, organizationId, "primary");
        jdbcTemplate.update("update plans set included_quantity = 1 where code = 'FREE'");

        mockMvc.perform(post("/api/usage/events")
                        .header("X-API-Key", rawKey)
                        .header("Idempotency-Key", "usage-quota-may")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {
                                  "metric":"REQUEST",
                                  "quantity":1,
                                  "occurredAt":"2026-05-31T23:59:59Z"
                                }
                                """))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/usage/events")
                        .header("X-API-Key", rawKey)
                        .header("Idempotency-Key", "usage-quota-june")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {
                                  "metric":"REQUEST",
                                  "quantity":1,
                                  "occurredAt":"2026-06-01T00:00:00Z"
                                }
                                """))
                .andExpect(status().isCreated());

        assertThat(requestQuotaCounter(organizationId, LocalDate.of(2026, 5, 1)))
                .isEqualTo(1);
        assertThat(requestQuotaCounter(organizationId, LocalDate.of(2026, 6, 1)))
                .isEqualTo(1);
    }

    @Test
    void parallelExplicitUsageEventsCannotOvershootMonthlyQuota() throws Exception {
        String token = signup("usage-quota-parallel@example.com");
        UUID organizationId = createOrganization(token, "Parallel Explicit Usage Quota Org");
        String rawKey = createApiKey(token, organizationId, "primary");
        jdbcTemplate.update("update plans set included_quantity = 1 where code = 'FREE'");

        int requestCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(requestCount);
        CountDownLatch readyLatch = new CountDownLatch(requestCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();

        for (int index = 0; index < requestCount; index++) {
            int requestIndex = index;
            futures.add(executor.submit(() -> {
                readyLatch.countDown();
                startLatch.await();
                return mockMvc.perform(post("/api/usage/events")
                                .header("X-API-Key", rawKey)
                                .header("Idempotency-Key", "usage-parallel-quota-" + requestIndex)
                                .contentType(APPLICATION_JSON)
                                .content("""
                                        {"metric":"REQUEST","quantity":1}
                                        """))
                        .andReturn()
                        .getResponse()
                        .getStatus();
            }));
        }

        readyLatch.await();
        startLatch.countDown();

        List<Integer> statuses = new ArrayList<>();
        for (Future<Integer> future : futures) {
            statuses.add(future.get());
        }
        executor.shutdown();

        long createdCount = statuses.stream().filter(status -> status == 201).count();
        long tooManyRequestsCount = statuses.stream().filter(status -> status == 429).count();
        Integer usageCount = jdbcTemplate.queryForObject(
                "select count(*) from usage_events where organization_id = ?",
                Integer.class,
                organizationId);

        assertThat(createdCount).isEqualTo(1);
        assertThat(tooManyRequestsCount).isEqualTo(requestCount - 1);
        assertThat(usageCount).isEqualTo(1);
        assertThat(requestQuotaCounter(organizationId)).isEqualTo(1);
    }

    @Test
    void parallelGatewayCallsCannotOvershootMonthlyQuota() throws Exception {
        String token = signup("gateway-quota-parallel@example.com");
        UUID organizationId = createOrganization(token, "Gateway Parallel Quota Org");
        String rawKey = createApiKey(token, organizationId, "primary");
        jdbcTemplate.update("update plans set included_quantity = 1 where code = 'FREE'");

        int requestCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(requestCount);
        CountDownLatch readyLatch = new CountDownLatch(requestCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();

        for (int index = 0; index < requestCount; index++) {
            int requestIndex = index;
            futures.add(executor.submit(() -> {
                readyLatch.countDown();
                startLatch.await();
                return mockMvc.perform(post("/v1/gateway/mock-completion")
                                .header("X-API-Key", rawKey)
                                .header("Idempotency-Key", "gateway-parallel-quota-" + requestIndex)
                                .contentType(APPLICATION_JSON)
                                .content("""
                                        {"prompt":"parallel quota request %d"}
                                        """.formatted(requestIndex)))
                        .andReturn()
                        .getResponse()
                        .getStatus();
            }));
        }

        readyLatch.await();
        startLatch.countDown();

        List<Integer> statuses = new ArrayList<>();
        for (Future<Integer> future : futures) {
            statuses.add(future.get());
        }
        executor.shutdown();

        long okCount = statuses.stream().filter(status -> status == 200).count();
        long tooManyRequestsCount = statuses.stream().filter(status -> status == 429).count();
        Integer usageCount = jdbcTemplate.queryForObject(
                "select count(*) from usage_events where organization_id = ?",
                Integer.class,
                organizationId);

        assertThat(okCount).isEqualTo(1);
        assertThat(tooManyRequestsCount).isEqualTo(requestCount - 1);
        assertThat(usageCount).isEqualTo(1);
        assertThat(requestQuotaCounter(organizationId)).isEqualTo(1);
    }

    private Long requestQuotaCounter(UUID organizationId) {
        return jdbcTemplate.queryForObject(
                """
                select coalesce(sum(used_quantity), 0)
                  from quota_counters
                 where organization_id = ?
                   and metric = 'REQUEST'
                """,
                Long.class,
                organizationId);
    }

    private Long requestQuotaCounter(UUID organizationId, LocalDate periodStart) {
        return jdbcTemplate.queryForObject(
                """
                select coalesce(sum(used_quantity), 0)
                  from quota_counters
                 where organization_id = ?
                   and metric = 'REQUEST'
                   and period_start = ?
                """,
                Long.class,
                organizationId,
                periodStart);
    }
}
