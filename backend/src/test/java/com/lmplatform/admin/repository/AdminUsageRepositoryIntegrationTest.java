package com.lmplatform.admin.repository;

import static org.assertj.core.api.Assertions.*;

import java.time.YearMonth;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@EnabledIfEnvironmentVariable(named = "CREDIT_TEST_DB_URL", matches = ".+")
class AdminUsageRepositoryIntegrationTest {
    private JdbcTemplate admin;
    private JdbcTemplate jdbc;
    private AdminUsageRepository repository;
    private String schema;

    @BeforeEach
    void setup() {
        String url = System.getenv("CREDIT_TEST_DB_URL");
        String user = System.getenv("CREDIT_TEST_DB_USER");
        String password = System.getenv("CREDIT_TEST_DB_PASSWORD");
        admin = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
        schema = "stats_test_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE SCHEMA " + schema);
        jdbc = new JdbcTemplate(new DriverManagerDataSource(url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema, user, password));
        for (String table : new String[] {"usage_event", "api_usage_event"}) {
            jdbc.execute("CREATE TABLE " + table + " (model_key TEXT, input_tokens INTEGER, output_tokens INTEGER, status TEXT, occurred_at TIMESTAMPTZ)");
        }
        repository = new AdminUsageRepository(jdbc);
    }

    @AfterEach
    void cleanup() {
        if (schema != null && schema.matches("stats_test_[a-f0-9]{32}")) admin.execute("DROP SCHEMA " + schema + " CASCADE");
    }

    @Test
    void separatesSourcesAndUsesShanghaiMonthBoundaries() {
        jdbc.execute("""
                INSERT INTO usage_event VALUES
                ('removed-model', 10, 20, 'completed', '2026-02-28T15:59:59Z'),
                ('chat-model', 30, 40, 'completed', '2026-02-28T16:00:00Z'),
                ('chat-model', 50, 60, 'completed', '2026-03-31T16:00:00Z');
                INSERT INTO api_usage_event VALUES
                ('chat-model', 100, 200, 'completed', '2026-03-10T00:00:00Z'),
                ('chat-model', 5, 6, 'failed', '2026-03-11T00:00:00Z');
                """);
        var report = repository.report(YearMonth.of(2026, 3), false);
        var chat = report.selectedMonth().stream().filter(row -> row.source().equals("chat")).findFirst().orElseThrow();
        assertThat(chat.requestCount()).isOne();
        assertThat(chat.inputTokens()).isEqualTo(30);
        var api = report.selectedMonth().stream().filter(row -> row.source().equals("api")).findFirst().orElseThrow();
        assertThat(api.requestCount()).isEqualTo(2);
        assertThat(api.failedRequestCount()).isOne();
        assertThat(api.inputTokens()).isEqualTo(105);
        assertThat(report.models()).hasSize(2);
        assertThat(report.months()).extracting(AdminUsageRepository.Monthly::month).contains("2026-02", "2026-03").doesNotContain("2026-04");
        var all = repository.report(YearMonth.of(2026, 3), true);
        assertThat(all.models()).extracting(AdminUsageRepository.ModelUsage::modelKey).contains("removed-model");
        assertThat(all.totals().stream().mapToLong(AdminUsageRepository.Totals::requestCount).sum()).isEqualTo(5);
        assertThat(all.models().stream().mapToLong(AdminUsageRepository.ModelUsage::inputTokens).sum())
                .isEqualTo(all.totals().stream().mapToLong(AdminUsageRepository.Totals::inputTokens).sum());
    }

    @Test
    void emptyPeriodDoesNotResetLifetimeUsage() {
        assertThat(repository.totals()).isEmpty();
        jdbc.execute("INSERT INTO usage_event VALUES ('test', 2000000000, 0, 'completed', '2026-01-01T00:00:00Z'), ('test', 2000000000, 0, 'completed', '2026-01-02T00:00:00Z')");
        var report = repository.report(YearMonth.of(2026, 11), false);
        assertThat(report.selectedMonth()).isEmpty();
        assertThat(report.models()).isEmpty();
        assertThat(report.totals().getFirst().inputTokens()).isEqualTo(4_000_000_000L);
    }
}
