package com.stayos;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.dao.DataIntegrityViolationException;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test") @Transactional
class CheckoutSchemaTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired Flyway flyway;
    @Test void incrementalMigrationAndRevalidationPreserveRoles() {
        assertThat(jdbc.queryForObject("select current_database()",String.class)).isEqualTo("stayos_test");
        flyway.validate();assertThat(flyway.migrate().migrationsExecuted).isZero();
        assertThat(jdbc.queryForObject("select success from flyway_schema_history where version='7'",Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("select success from flyway_schema_history where version='8'",Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("select count(*) from roles",Integer.class)).isEqualTo(5);
    }
    @Test void taskSourceAndCompositeReferenceAreDatabaseConstraints() {
        assertThat(jdbc.queryForList("select conname from pg_constraint where conrelid='housekeeping_tasks'::regclass",String.class))
            .contains("uq_housekeeping_source","uq_housekeeping_checkout_segment","fk_task_segment_business");
        assertThat(jdbc.queryForObject("select definition from pg_views where viewname='room_operational_facts'",String.class)).contains("housekeeping_tasks");
    }
    @Test void unsupportedOperationsStillRejected() {
        assertThatThrownBy(()->jdbc.update("insert into idempotency_requests(operator_id,operation,request_key,request_hash) values(-1,'PAYMENT',gen_random_uuid(),repeat('a',64))"))
            .isInstanceOf(DataIntegrityViolationException.class);
    }
}
