package com.lmplatform.api.repository;

import static org.assertj.core.api.Assertions.*;

import com.lmplatform.api.model.ApiPrincipal;
import com.lmplatform.api.service.ApiGatewayException;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

/** Uses a disposable schema; never modifies application tables. */
@EnabledIfEnvironmentVariable(named = "CREDIT_TEST_DB_URL", matches = ".+")
class ApiCreditRepositoryIntegrationTest {
    private JdbcTemplate admin;
    private JdbcTemplate jdbc;
    private ApiCreditRepository credits;
    private ApiGatewayRepository gateway;
    private TransactionTemplate transaction;
    private String schema;

    @BeforeEach
    void setup() {
        String url = System.getenv("CREDIT_TEST_DB_URL");
        String user = System.getenv("CREDIT_TEST_DB_USER");
        String password = System.getenv("CREDIT_TEST_DB_PASSWORD");
        admin = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
        schema = "credit_test_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE SCHEMA " + schema);
        var ds = new DriverManagerDataSource(url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema, user, password);
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE app_user(id BIGINT PRIMARY KEY, account_number TEXT, display_name TEXT, status SMALLINT DEFAULT 1)");
        new ResourceDatabasePopulator(
                new ClassPathResource("db/migration/V21__api_gateway.sql"),
                new ClassPathResource("db/migration/V22__api_default_quota.sql"),
                new ClassPathResource("db/migration/V28__api_unlimited_quota.sql"),
                new ClassPathResource("db/migration/V29__api_credit_reservations.sql")
        ).execute(ds);
        jdbc.update("INSERT INTO app_user(id, account_number, display_name) VALUES (1, 'test', 'test')");
        credits = new ApiCreditRepository(jdbc);
        gateway = new ApiGatewayRepository(jdbc);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(ds));
    }

    @AfterEach
    void cleanup() {
        if (schema != null && schema.matches("credit_test_[a-f0-9]{32}")) admin.execute("DROP SCHEMA " + schema + " CASCADE");
    }

    @Test
    void newUserGetsDefaultBalanceAndAdjustmentsAreAudited() {
        assertThat(gateway.quota(1).remainingTokens()).isEqualTo(10_000_000);
        UUID operation = UUID.randomUUID();
        for (int i = 0; i < 2; i++) transaction.executeWithoutResult(s -> credits.adjust(1, operation, "add", 500, "grant", "local:1:admin"));
        transaction.executeWithoutResult(s -> credits.adjust(1, UUID.randomUUID(), "deduct", 100, "correction", "local:1:admin"));
        assertThat(gateway.quota(1).remainingTokens()).isEqualTo(10_000_400);
        assertThat(credits.history(1)).hasSize(2);
        assertThat(credits.history(1).getFirst().get("actor")).isEqualTo("local:1:admin");
    }

    @Test
    void concurrentRequestsCannotReserveTheSameBalance() throws Exception {
        jdbc.update("INSERT INTO api_user_quota(user_id,total_token_quota) VALUES (1,100)");
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<Boolean> reserve = () -> {
                start.await();
                try {
                    transaction.execute(s -> credits.reserve(1, 80));
                    return true;
                } catch (ApiGatewayException exception) { return false; }
            };
            var first = executor.submit(reserve);
            var second = executor.submit(reserve);
            start.countDown();
            assertThat(java.util.List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        }
        assertThat(gateway.quota(1).remainingTokens()).isEqualTo(20);
        assertThat(gateway.quota(1).reservedTokens()).isEqualTo(80);
    }

    @Test
    void settlementReleasesUnusedReservationAndIsIdempotent() {
        var key = gateway.createKey(1, "test").key();
        UUID request = transaction.execute(s -> credits.reserve(1, 100));
        var principal = new ApiPrincipal(key.id(), 1, "test", 10_000_000, 0, false);
        for (int i = 0; i < 2; i++) transaction.executeWithoutResult(s -> gateway.recordUsage(
                request, principal, "test", "chat/completions", 10, 20, "completed", null, 10, false));
        assertThat(gateway.quota(1).usedTokens()).isEqualTo(30);
        assertThat(gateway.quota(1).remainingTokens()).isEqualTo(9_999_970);
        assertThat(gateway.quota(1).reservedTokens()).isZero();
        assertThat(gateway.quota(1).requestCount()).isOne();
        assertThat(gateway.quota(1).todayTokens()).isEqualTo(30);
        assertThat(gateway.quota(1).monthTokens()).isEqualTo(30);
    }

    @Test
    void failedReservationAndExcessDeductionDoNotChangeBalanceOrAudit() {
        jdbc.update("INSERT INTO api_user_quota(user_id,total_token_quota) VALUES (1,100)");
        UUID request = transaction.execute(s -> credits.reserve(1, 80));
        assertThatThrownBy(() -> transaction.executeWithoutResult(s -> credits.adjust(1, UUID.randomUUID(), "deduct", 30, "", "admin")))
                .isInstanceOf(ApiGatewayException.class);
        assertThat(credits.history(1)).isEmpty();
        transaction.executeWithoutResult(s -> credits.release(request));
        assertThat(gateway.quota(1).remainingTokens()).isEqualTo(100);
    }

    @Test
    void abandonedReservationsExpireWithoutErasingHistoricalUsage() {
        jdbc.update("INSERT INTO api_user_quota(user_id,total_token_quota) VALUES (1,100)");
        jdbc.update("INSERT INTO daily_api_usage(usage_date,user_id,input_tokens) VALUES (CURRENT_DATE,1,30)");
        UUID id = transaction.execute(s -> credits.reserve(1, 60));
        assertThat(gateway.quota(1).remainingTokens()).isEqualTo(10);
        jdbc.update("UPDATE api_credit_reservation SET expires_at = CURRENT_TIMESTAMP - INTERVAL '1 second' WHERE request_id = ?", id);
        assertThat(gateway.quota(1).remainingTokens()).isEqualTo(70);
        transaction.executeWithoutResult(s -> credits.adjust(1, UUID.randomUUID(), "add", 50, "", "admin"));
        assertThat(gateway.quota(1).remainingTokens()).isEqualTo(120);
        assertThat(gateway.quota(1).usedTokens()).isEqualTo(30);
    }

    @Test
    void unlimitedStillTracksUsageAndCanReturnToLimited() {
        jdbc.update("INSERT INTO api_user_quota(user_id,total_token_quota) VALUES (1,0)");
        transaction.executeWithoutResult(s -> credits.adjust(1, UUID.randomUUID(), "unlimited", 0, "", "admin"));
        UUID request = transaction.execute(s -> credits.reserve(1, 500));
        assertThat(gateway.quota(1).unlimited()).isTrue();
        transaction.executeWithoutResult(s -> credits.release(request));
        transaction.executeWithoutResult(s -> credits.adjust(1, UUID.randomUUID(), "limited", 0, "", "admin"));
        assertThatThrownBy(() -> transaction.execute(s -> credits.reserve(1, 1))).isInstanceOf(ApiGatewayException.class);
    }
}
