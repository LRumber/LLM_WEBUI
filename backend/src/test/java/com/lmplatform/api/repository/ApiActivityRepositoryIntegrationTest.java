package com.lmplatform.api.repository;

import static org.assertj.core.api.Assertions.*;

import com.lmplatform.api.service.ApiCallTrace;
import com.lmplatform.api.service.ApiFailure;
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

@EnabledIfEnvironmentVariable(named = "CREDIT_TEST_DB_URL", matches = ".+")
class ApiActivityRepositoryIntegrationTest {
    JdbcTemplate admin;
    JdbcTemplate jdbc;
    ApiCreditRequestRepository requests;
    ApiCreditRepository credits;
    ApiGatewayRepository gateway;
    ApiCallLogRepository logs;
    TransactionTemplate transaction;
    String schema;

    @BeforeEach
    void setup() {
        String url = System.getenv("CREDIT_TEST_DB_URL");
        String user = System.getenv("CREDIT_TEST_DB_USER");
        String password = System.getenv("CREDIT_TEST_DB_PASSWORD");
        admin = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
        schema = "activity_test_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE SCHEMA " + schema);
        var ds = new DriverManagerDataSource(url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema, user, password);
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE app_user(id BIGINT PRIMARY KEY, account_number TEXT, display_name TEXT, user_number TEXT, status SMALLINT DEFAULT 1)");
        new ResourceDatabasePopulator(
                new ClassPathResource("db/migration/V21__api_gateway.sql"),
                new ClassPathResource("db/migration/V22__api_default_quota.sql"),
                new ClassPathResource("db/migration/V28__api_unlimited_quota.sql"),
                new ClassPathResource("db/migration/V29__api_credit_reservations.sql"),
                new ClassPathResource("db/migration/V30__api_credit_requests_and_call_logs.sql")
        ).execute(ds);
        jdbc.update("INSERT INTO app_user(id,display_name,user_number) VALUES (1,'Alice','001'),(2,'Bob','002')");
        credits = new ApiCreditRepository(jdbc);
        gateway = new ApiGatewayRepository(jdbc);
        requests = new ApiCreditRequestRepository(jdbc, credits);
        logs = new ApiCallLogRepository(jdbc);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(ds));
    }

    @AfterEach
    void cleanup() {
        ApiCallTrace.clear();
        if (schema != null && schema.matches("activity_test_[a-f0-9]{32}")) admin.execute("DROP SCHEMA " + schema + " CASCADE");
    }

    UUID submit(long user, String type, long amount) {
        return transaction.execute(s -> requests.submit(user, type, amount, "test reason"));
    }

    @Test
    void requestsArePrivateAndOnlyOneCanBePending() {
        submit(1, "tokens", 500);
        submit(2, "unlimited", 0);
        assertThatThrownBy(() -> submit(1, "tokens", 1)).isInstanceOf(ApiGatewayException.class);
        assertThat(requests.list(1L, "all", 0, 20).items()).hasSize(1).allMatch(r -> r.userId() == 1);
        assertThat(requests.list(null, "pending", 0, 20).total()).isEqualTo(2);
        assertThatThrownBy(() -> requests.submit(1, null, 0, "reason")).isInstanceOf(ApiGatewayException.class);
    }

    @Test
    void concurrentApprovalGrantsExactlyOnce() throws Exception {
        UUID id = submit(1, "tokens", 500);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<Boolean> approve = () -> {
                start.await();
                try {
                    transaction.executeWithoutResult(s -> requests.review(id, true, 700, "approved", "test-admin"));
                    return true;
                } catch (ApiGatewayException exception) { return false; }
            };
            var first = executor.submit(approve);
            var second = executor.submit(approve);
            start.countDown();
            assertThat(java.util.List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        }
        assertThat(gateway.quota(1).remainingTokens()).isEqualTo(10_000_700);
        assertThat(credits.history(1)).hasSize(1);
        assertThat(requests.list(1L, "approved", 0, 20).items().getFirst().approvedTokens()).isEqualTo(700);
        assertThat(requests.list(null, "all", 0, 20).pendingCount()).isZero();
    }

    @Test
    void rejectionDoesNotGrantAndUnlimitedPreservesUsage() {
        UUID rejected = submit(1, "tokens", 500);
        transaction.executeWithoutResult(s -> requests.review(rejected, false, 0, "not now", "admin"));
        assertThat(credits.history(1)).isEmpty();
        assertThat(gateway.quota(1).remainingTokens()).isEqualTo(10_000_000);
        UUID unlimited = submit(1, "unlimited", 0);
        transaction.executeWithoutResult(s -> requests.review(unlimited, true, 0, "", "admin"));
        assertThat(gateway.quota(1).unlimited()).isTrue();
        assertThat(gateway.quota(1).usedTokens()).isZero();
        assertThat(credits.history(1)).hasSize(1);
    }

    @Test
    void approvalRollsBackCreditAndDecisionTogether() {
        UUID id = submit(1, "tokens", 500);
        transaction.executeWithoutResult(s -> {
            requests.review(id, true, 500, "", "admin");
            s.setRollbackOnly();
        });
        assertThat(requests.list(1L, "pending", 0, 20).total()).isOne();
        assertThat(credits.history(1)).isEmpty();
        assertThat(gateway.quota(1).remainingTokens()).isEqualTo(10_000_000);
    }

    @Test
    void diagnosticsArePrivateFilteredIdempotentAndDoNotBill() {
        var trace = ApiCallTrace.begin();
        trace.userId = 1L;
        trace.model = "test-model";
        trace.failure = ApiFailure.from(new java.net.SocketTimeoutException("secret key and prompt"));
        logs.record(trace, "/api/v1/chat/completions", 504);
        logs.record(trace, "/api/v1/chat/completions", 504);
        var other = ApiCallTrace.begin();
        other.userId = 2L;
        logs.record(other, "/api/v1/models", 200);
        var anonymous = ApiCallTrace.begin();
        logs.record(anonymous, "/api/v1/models", 401);
        var own = logs.list(1L, "failed", "001", "test-model", "", "", 0, 20);
        assertThat(own.total()).isOne();
        assertThat(own.items().getFirst().detail()).isNull();
        assertThat(own.items().getFirst().errorCode()).isEqualTo("upstream_timeout");
        assertThat(logs.list(null, "all", "", "", "", "", 0, 1).items()).hasSize(1);
        assertThat(logs.list(null, "all", "", "", "", "", 0, 20).total()).isEqualTo(3);
        assertThat(logs.list(null, "failed", trace.requestId.toString(), "", "", "", 0, 20).items().getFirst().detail())
                .doesNotContain("secret", "prompt").contains("SocketTimeoutException");
        assertThat(gateway.quota(1).usedTokens()).isZero();
        assertThat(gateway.quota(1).requestCount()).isZero();
        assertThatThrownBy(() -> logs.list(1L, "all", "", "", "2026-11-12", "2026-11-01", 0, 20))
                .isInstanceOf(ApiGatewayException.class);
    }
}
