package com.lmplatform.common.service;
import static org.assertj.core.api.Assertions.*;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.ClassPathResource;
import org.springframework.transaction.support.TransactionTemplate;
import com.lmplatform.common.model.RuntimePolicy;
import org.springframework.web.server.ResponseStatusException;

@EnabledIfEnvironmentVariable(named="CREDIT_TEST_DB_URL", matches=".+")
class RuntimeAdmissionIntegrationTest {
    JdbcTemplate admin, jdbc; String schema; TransactionTemplate tx; RuntimeAdmissionService service;
    @BeforeEach void setup() throws Exception {
        String url = System.getenv("CREDIT_TEST_DB_URL"), user = System.getenv("CREDIT_TEST_DB_USER"), password = System.getenv("CREDIT_TEST_DB_PASSWORD");
        admin = new JdbcTemplate(new DriverManagerDataSource(url,user,password));
        schema = "runtime_test_" + UUID.randomUUID().toString().replace("-", ""); admin.execute("CREATE SCHEMA " + schema);
        var ds = new DriverManagerDataSource(url+(url.contains("?")?"&":"?")+"currentSchema="+schema,user,password);
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE app_user(id BIGINT PRIMARY KEY,status INT DEFAULT 1); CREATE TABLE api_user_quota(user_id BIGINT PRIMARY KEY REFERENCES app_user(id),total_token_quota BIGINT DEFAULT 10000000); CREATE TABLE system_setting(setting_key TEXT PRIMARY KEY,setting_value TEXT)");
        jdbc.execute("INSERT INTO app_user(id) VALUES (1),(2); INSERT INTO api_user_quota VALUES(1,777)");
        // Send the complete PostgreSQL script so dollar-quoted function bodies remain intact.
        jdbc.execute(new ClassPathResource("db/migration/V49__runtime_policy.sql").getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        tx = new TransactionTemplate(new DataSourceTransactionManager(ds)); service = new RuntimeAdmissionService(jdbc);
    }
    @AfterEach void cleanup() { if(schema != null && schema.matches("runtime_test_[a-f0-9]{32}")) admin.execute("DROP SCHEMA "+schema+" CASCADE"); }
    @Test void defaultsAffectOnlyNewUsers() {
        assertThat(jdbc.queryForObject("SELECT total_token_quota FROM api_user_quota WHERE user_id=1",Long.class)).isEqualTo(777);
        assertThat(jdbc.queryForObject("SELECT total_token_quota FROM api_user_quota WHERE user_id=2",Long.class)).isEqualTo(10000000);
        jdbc.update("INSERT INTO system_setting VALUES ('runtime_policy', ?)", "{\"defaultApiTokens\":500}");
        jdbc.execute("INSERT INTO app_user(id) VALUES(3)");
        jdbc.update("UPDATE system_setting SET setting_value=?", "{\"defaultApiTokens\":0}");
        jdbc.execute("INSERT INTO app_user(id) VALUES(4)");
        assertThat(jdbc.queryForObject("SELECT total_token_quota FROM api_user_quota WHERE user_id=3",Long.class)).isEqualTo(500);
        assertThat(jdbc.queryForObject("SELECT total_token_quota FROM api_user_quota WHERE user_id=4",Long.class)).isZero();
    }
    @Test void concurrentRequestsCannotBypassCapAndReleasedSlotIsReusable() throws Exception {
        var policy = new RuntimePolicy(0,2,1,false,"maintenance"); var start = new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            Callable<UUID> acquire=()->{start.await();try{return tx.execute(s->service.acquire(1,true,policy));}catch(ResponseStatusException e){return null;}};
            var a=pool.submit(acquire);var b=pool.submit(acquire);start.countDown();
            UUID first=a.get(10,TimeUnit.SECONDS), second=b.get(10,TimeUnit.SECONDS);
            assertThat(first==null ^ second==null).isTrue();service.release(first==null?second:first);
        }
        UUID next=tx.execute(s->service.acquire(1,true,policy));service.release(next);
        assertThatThrownBy(()->tx.execute(s->service.acquire(1,true,policy))).isInstanceOf(ResponseStatusException.class);
        assertThat(jdbc.queryForObject("SELECT requests FROM runtime_daily_requests WHERE user_id=1",Long.class)).isEqualTo(2);
        // API calls share concurrency but do not consume the web daily limit.
        UUID api=tx.execute(s->service.acquire(1,false,policy));service.release(api);
    }
    @Test void expiredLeasesAndPreviousDaysDoNotBlockNewRequests() {
        jdbc.update("INSERT INTO runtime_request_lease VALUES (?,1,CURRENT_TIMESTAMP-INTERVAL '1 second')",UUID.randomUUID());
        jdbc.execute("INSERT INTO runtime_daily_requests VALUES (1,(CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Shanghai')::date-1,999)");
        UUID id=tx.execute(s->service.acquire(1,true,new RuntimePolicy(0,1,1,false,"maintenance")));
        assertThat(id).isNotNull();service.release(id);
    }
}
