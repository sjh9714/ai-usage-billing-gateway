package io.github.sungjh.aiusagebillinggateway;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.YearMonth;
import java.util.HexFormat;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
abstract class IntegrationTestSupport {

    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    static final GenericContainer<?> REDIS =
            new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    static {
        POSTGRES.start();
        REDIS.start();
    }

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    StringRedisTemplate redisTemplate;

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.execute(
                """
                truncate table
                    audit_logs,
                    ledger_entries,
                    payment_webhook_events,
                    payments,
                    invoice_items,
                    invoices,
                    quota_counters,
                    usage_events,
                    api_keys,
                    subscriptions,
                    organization_members,
                    organizations,
                    users
                cascade
                """);
        jdbcTemplate.update(
                """
                update plans
                   set included_quantity = case code
                       when 'FREE' then 10000
                       when 'PRO' then 100000
                       else 1000000
                   end,
                   overage_unit_amount_minor = case code
                       when 'FREE' then 0
                       when 'PRO' then 2
                       else 1
                   end,
                   overage_allowed = case code
                       when 'FREE' then false
                       else true
                   end
                """);
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushDb();
    }

    String signup(String email) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/signup")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"Password123!"}
                                """.formatted(email)))
                .andExpect(status().isCreated())
                .andReturn();
        return json(result).get("accessToken").asText();
    }

    String login(String email) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"Password123!"}
                                """.formatted(email)))
                .andExpect(status().isOk())
                .andReturn();
        return json(result).get("accessToken").asText();
    }

    UUID createOrganization(String token, String name) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/organizations")
                        .header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"name":"%s"}
                                """.formatted(name)))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(json(result).get("id").asText());
    }

    String createApiKey(String token, UUID organizationId, String name) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/organizations/{orgId}/api-keys", organizationId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"name":"%s"}
                                """.formatted(name)))
                .andExpect(status().isCreated())
                .andReturn();
        return json(result).get("rawApiKey").asText();
    }

    JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    String webhookSignature(String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(
                "test-webhook-secret".getBytes(StandardCharsets.UTF_8),
                "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
    }

    YearMonth currentPeriod() {
        return YearMonth.now();
    }
}
