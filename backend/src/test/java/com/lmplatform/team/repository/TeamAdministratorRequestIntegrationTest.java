package com.lmplatform.team.repository;

import static org.assertj.core.api.Assertions.*;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@EnabledIfEnvironmentVariable(named = "CREDIT_TEST_DB_URL", matches = ".+")
class TeamAdministratorRequestIntegrationTest {
    JdbcTemplate admin;
    JdbcTemplate jdbc;
    TeamAdministratorRequestRepository requests;
    TransactionTemplate transaction;
    String schema;

    @BeforeEach
    void setup() {
        String url = System.getenv("CREDIT_TEST_DB_URL");
        String user = System.getenv("CREDIT_TEST_DB_USER");
        String password = System.getenv("CREDIT_TEST_DB_PASSWORD");
        admin = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
        schema = "team_request_test_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE SCHEMA " + schema);
        var ds = new DriverManagerDataSource(url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema, user, password);
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE app_user(id BIGINT PRIMARY KEY, display_name TEXT, user_number TEXT, status INT DEFAULT 1, is_team_admin BOOLEAN DEFAULT FALSE, updated_at TIMESTAMPTZ)");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V32__team_administrator_requests.sql")).execute(ds);
        jdbc.update("INSERT INTO app_user(id, display_name) VALUES (1, 'Alice'), (2, 'Bob')");
        requests = new TeamAdministratorRequestRepository(jdbc);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(ds));
    }

    @AfterEach
    void cleanup() {
        if (schema != null && schema.matches("team_request_test_[a-f0-9]{32}")) admin.execute("DROP SCHEMA " + schema + " CASCADE");
    }

    UUID submit(long userId) { return transaction.execute(s -> requests.submit(userId, "协作申请")); }

    @Test
    void approvalGrantsPermissionAndCannotBeRepeated() {
        UUID id = submit(1);
        assertThatThrownBy(() -> submit(1)).isInstanceOf(ResponseStatusException.class);
        assertThat(requests.status(2).latest()).isNull();
        transaction.executeWithoutResult(s -> requests.review(id, true, "同意", "admin"));
        assertThat(requests.status(1).administrator()).isTrue();
        assertThat(requests.status(1).latest().status()).isEqualTo("approved");
        assertThatThrownBy(() -> transaction.executeWithoutResult(s -> requests.review(id, false, "", "admin")))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> submit(1)).isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void rejectionAllowsReapplicationAndApprovalRollsBackAtomically() {
        UUID id = submit(1);
        transaction.executeWithoutResult(s -> requests.review(id, false, "请补充说明", "admin"));
        assertThat(requests.status(1).administrator()).isFalse();
        assertThat(requests.status(1).latest().reviewNote()).isEqualTo("请补充说明");
        UUID next = submit(1);
        transaction.executeWithoutResult(s -> {
            requests.review(next, true, "", "admin");
            s.setRollbackOnly();
        });
        assertThat(requests.status(1).administrator()).isFalse();
        assertThat(requests.status(1).latest().status()).isEqualTo("pending");
        jdbc.update("UPDATE app_user SET status = 0 WHERE id = 1");
        assertThatThrownBy(() -> transaction.executeWithoutResult(s -> requests.review(next, true, "", "admin")))
                .isInstanceOf(ResponseStatusException.class);
    }
}
