package com.stayos;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.flywaydb.core.Flyway;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class SchemaTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired Flyway flyway;

    @Test void migrationsAreRepeatableAndFiveRolesExist() {
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        assertThat(jdbc.queryForObject("select count(*) from roles", Integer.class)).isEqualTo(5);
        assertThat(jdbc.queryForList("select role_code from roles", String.class))
            .containsExactlyInAnyOrder("ADMIN", "HOTEL_MANAGER", "FRONT_DESK", "HOUSEKEEPING_MANAGER", "HOUSEKEEPING_STAFF");
    }
    @Test void plainTextPasswordsAreRejectedByDatabase() {
        assertThatThrownBy(() -> jdbc.update("insert into users(username,password_hash,display_name) values('invalid','plaintext','Test')"))
            .isInstanceOf(DataIntegrityViolationException.class);
    }
    @Test void userAndRoleConstraintsPreventDuplicatesAndDanglingReferences() {
        String hash = "$2a$12$" + "a".repeat(53);
        jdbc.update("delete from user_roles where user_id in (select id from users where username='schema_test')");
        jdbc.update("delete from users where username='schema_test'");
        Long id = jdbc.queryForObject("insert into users(username,password_hash,display_name) values('schema_test',?,'Test') returning id", Long.class, hash);
        try {
            assertThatThrownBy(() -> jdbc.update("insert into users(username,password_hash,display_name) values('schema_test',?,'Test')", hash))
                .isInstanceOf(DataIntegrityViolationException.class);
            Long role = jdbc.queryForObject("select id from roles where role_code='ADMIN'", Long.class);
            jdbc.update("insert into user_roles values(?,?)", id, role);
            assertThatThrownBy(() -> jdbc.update("insert into user_roles values(?,?)", id, role)).isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(() -> jdbc.update("insert into user_roles values(?, -1)", id)).isInstanceOf(DataIntegrityViolationException.class);
        } finally { jdbc.update("delete from user_roles where user_id=?", id); jdbc.update("delete from users where id=?", id); }
    }
}
