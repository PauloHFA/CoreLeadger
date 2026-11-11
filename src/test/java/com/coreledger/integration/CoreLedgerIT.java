package com.coreledger.integration;

import com.coreledger.infrastructure.outbox.OutboxPublisher;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** Critérios de aceite CA-01..CA-06 contra PostgreSQL, Redis e RabbitMQ reais (Testcontainers). */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class CoreLedgerIT {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");
    @Container @ServiceConnection
    static RabbitMQContainer rabbit = new RabbitMQContainer("rabbitmq:3.13-alpine");
    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    static WireMockServer acquirer = new WireMockServer(0);

    @BeforeAll
    static void startAcquirer() {
        acquirer.start();
        acquirer.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(urlEqualTo("/authorize")).willReturn(aResponse().withStatus(200).withFixedDelay(800)));
    }

    @AfterAll
    static void stopAcquirer() {
        acquirer.stop();
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.data.redis.host", redis::getHost);
        r.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        r.add("coreledger.acquirer.base-url", () -> "http://localhost:" + acquirer.port());
        r.add("coreledger.acquirer.read-timeout", () -> "300ms");
        r.add("resilience4j.retry.instances.acquirerGateway.max-attempts", () -> "1");
        r.add("coreledger.outbox.poll-ms", () -> "3600000"); // publicação manual nos testes
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redisTemplate;
    @Autowired MeterRegistry meters;
    @Autowired CircuitBreakerRegistry cbRegistry;
    @Autowired OutboxPublisher outboxPublisher;

    static final RequestPostProcessor WRITE = jwt().authorities(() -> "SCOPE_payment:write");
    static final RequestPostProcessor READ = jwt().authorities(() -> "SCOPE_ledger:read");

    UUID account(long initialCents) throws Exception {
        String body = """
                {"holderName":"Teste","accountNumber":"%s","initialBalanceCents":%d}
                """.formatted(UUID.randomUUID().toString().substring(0, 20), initialCents);
        MvcResult res = mvc.perform(post("/api/v1/accounts").with(WRITE)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
        assertThat(res.getResponse().getStatus()).isEqualTo(201);
        return UUID.fromString(mapper.readTree(res.getResponse().getContentAsString()).get("id").asText());
    }

    long balance(UUID id) {
        return jdbc.queryForObject("select balance_cents from accounts where id = ?", Long.class, id);
    }

    MvcResult transfer(String key, UUID from, UUID to, long cents, String type) throws Exception {
        String body = """
                {"sourceAccountId":"%s","targetAccountId":"%s","amountCents":%d,"type":"%s"}
                """.formatted(from, to, cents, type);
        return mvc.perform(post("/api/v1/transactions").with(WRITE).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
    }

    @Test
    void ca01_doubleEntry() throws Exception {
        UUID a = account(50_000), b = account(10_000);
        MvcResult res = transfer("ca01-" + UUID.randomUUID(), a, b, 15_000, "INTERNAL");

        assertThat(res.getResponse().getStatus()).isEqualTo(201);
        UUID txId = UUID.fromString(mapper.readTree(res.getResponse().getContentAsString()).get("transactionId").asText());
        assertThat(balance(a)).isEqualTo(35_000);
        assertThat(balance(b)).isEqualTo(25_000);
        List<Long> entries = jdbc.queryForList(
                "select case when entry_type='DEBIT' then -amount_cents else amount_cents end from ledger_entries "
                        + "where transaction_id = ? order by entry_type", Long.class, txId);
        assertThat(entries).containsExactlyInAnyOrder(-15_000L, 15_000L);
        Integer pending = jdbc.queryForObject(
                "select count(*) from outbox_events where aggregate_id = ? and status = 'PENDING'",
                Integer.class, txId.toString());
        assertThat(pending).isEqualTo(1);
    }

    @Test
    void ca02_idempotency() throws Exception {
        UUID a = account(50_000), b = account(0);
        String key = "K-100-" + UUID.randomUUID();

        MvcResult first = transfer(key, a, b, 1_000, "INTERNAL");
        MvcResult replay = transfer(key, a, b, 1_000, "INTERNAL");
        assertThat(first.getResponse().getStatus()).isEqualTo(201);
        assertThat(replay.getResponse().getHeader("Idempotent-Replayed")).isEqualTo("true");
        assertThat(replay.getResponse().getContentAsString()).isEqualTo(first.getResponse().getContentAsString());
        assertThat(balance(a)).isEqualTo(49_000);
        assertThat(jdbc.queryForObject("select count(*) from transactions where idempotency_key = ?",
                Integer.class, key)).isEqualTo(1);

        String inProgress = "K-INPROG-" + UUID.randomUUID();
        redisTemplate.opsForValue().set("coreledger:idemp:" + inProgress, "IN_PROGRESS");
        assertThat(transfer(inProgress, a, b, 1_000, "INTERNAL").getResponse().getStatus()).isEqualTo(409);
        assertThat(balance(a)).isEqualTo(49_000);
    }

    @Test
    void ca02_missingKeyReturns400() throws Exception {
        UUID a = account(1_000), b = account(0);
        String body = """
                {"sourceAccountId":"%s","targetAccountId":"%s","amountCents":100}
                """.formatted(a, b);
        MvcResult res = mvc.perform(post("/api/v1/transactions").with(WRITE)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
        assertThat(res.getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    void ca03_extremeConcurrency() throws Exception {
        UUID a = account(10_000), b = account(0);
        int threads = 50;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch gate = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            String key = "ca03-" + UUID.randomUUID();
            futures.add(pool.submit(() -> {
                gate.await();
                return transfer(key, a, b, 2_000, "INTERNAL").getResponse().getStatus();
            }));
        }
        gate.countDown();
        int created = 0, unprocessable = 0;
        for (Future<Integer> f : futures) {
            int s = f.get(60, TimeUnit.SECONDS);
            if (s == 201) created++;
            else if (s == 422) unprocessable++;
        }
        pool.shutdown();

        assertThat(created).isEqualTo(5);
        assertThat(unprocessable).isEqualTo(45);
        assertThat(balance(a)).isZero();
        assertThat(balance(b)).isEqualTo(10_000);
    }

    @Test
    void ca04_security() throws Exception {
        String body = "{\"sourceAccountId\":\"%s\",\"targetAccountId\":\"%s\",\"amountCents\":100}"
                .formatted(UUID.randomUUID(), UUID.randomUUID());
        int before = jdbc.queryForObject("select count(*) from transactions", Integer.class);
        assertThat(mvc.perform(post("/api/v1/transactions").header("Idempotency-Key", "x")
                .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().getResponse().getStatus())
                .isEqualTo(401);
        assertThat(mvc.perform(post("/api/v1/transactions").with(READ).header("Idempotency-Key", "x")
                .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().getResponse().getStatus())
                .isEqualTo(403);
        assertThat(jdbc.queryForObject("select count(*) from transactions", Integer.class)).isEqualTo(before);
    }

    @Test
    void ca05_circuitBreakerOpens() throws Exception {
        UUID a = account(1_000_000), b = account(0);
        for (int i = 0; i < 10; i++) {
            assertThat(transfer("ca05-" + UUID.randomUUID(), a, b, 100, "EXTERNAL").getResponse().getStatus())
                    .isEqualTo(503);
        }
        CircuitBreaker cb = cbRegistry.circuitBreaker("acquirerGateway");
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        int requestsBefore = acquirer.getAllServeEvents().size();
        long start = System.nanoTime();
        int status = transfer("ca05-open-" + UUID.randomUUID(), a, b, 100, "EXTERNAL").getResponse().getStatus();
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(status).isEqualTo(503);
        assertThat(elapsedMs).isLessThan(150);
        assertThat(acquirer.getAllServeEvents().size()).isEqualTo(requestsBefore); // sem chamada de rede
        assertThat(meters.find("acquirer.fallback.total").counters()).isNotEmpty();
        assertThat(balance(a)).isEqualTo(1_000_000); // nada debitado
    }

    @Test
    void ca06_observability() throws Exception {
        UUID a = account(5_000), b = account(0);
        assertThat(transfer("ca06-" + UUID.randomUUID(), a, b, 1_000, "INTERNAL").getResponse().getStatus())
                .isEqualTo(201);

        String prom = mvc.perform(get("/actuator/prometheus")).andReturn().getResponse().getContentAsString();
        assertThat(prom).contains("ledger_transactions_total").contains("status=\"SUCCESS\"");

        outboxPublisher.publishPending();
        await().atMost(15, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(meters.find("ledger.events.consumed").counter()).isNotNull());
        assertThat(jdbc.queryForObject("select count(*) from outbox_events where status = 'PENDING'", Integer.class))
                .isZero();
    }

    @Test
    void ledgerIntegrityEndpoint() throws Exception {
        UUID a = account(5_000), b = account(0);
        transfer("int-" + UUID.randomUUID(), a, b, 1_200, "INTERNAL");
        MvcResult res = mvc.perform(get("/api/v1/ledger/accounts/" + b + "/integrity").with(READ)).andReturn();
        JsonNode json = mapper.readTree(res.getResponse().getContentAsString());
        assertThat(json.get("consistent").asBoolean()).isTrue();
        assertThat(json.get("balanceCents").asLong()).isEqualTo(1_200);
        MvcResult page = mvc.perform(get("/api/v1/ledger/accounts/" + a + "/entries").with(READ)).andReturn();
        assertThat(mapper.readTree(page.getResponse().getContentAsString()).get("totalElements").asInt()).isEqualTo(2);
    }
}
