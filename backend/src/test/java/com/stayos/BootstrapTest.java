package com.stayos;

import com.stayos.staff.service.AccountService;
import com.stayos.common.ApiException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.junit.jupiter.api.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class BootstrapTest {
    @Autowired AccountService accounts;
    @Autowired JdbcTemplate jdbc;
    @BeforeEach @AfterEach void clearTestDatabase() {
        assertThat(jdbc.queryForObject("select current_database()",String.class)).isEqualTo("stayos_test");
        jdbc.update("delete from audit_logs"); jdbc.update("delete from user_roles"); jdbc.update("delete from users");
    }
    @Test void firstAdministratorCanBeCreatedButSecondCannotEvenIfDisabled() {
        var first=accounts.bootstrap("first_admin",UUID.randomUUID().toString()); assertThat(first.roles()).containsExactly("ADMIN");
        jdbc.update("update users set enabled=false where id=?",first.id());
        assertThatThrownBy(() -> accounts.bootstrap("second_admin",UUID.randomUUID().toString())).isInstanceOf(ApiException.class).hasMessageContaining("已有管理员");
        assertThat(jdbc.queryForObject("select count(*) from users",Integer.class)).isEqualTo(1);
    }
    @Test void concurrentBootstrapCreatesOnlyOneAdministrator() throws Exception {
        try(var pool=Executors.newFixedThreadPool(2)) {
            var start=new CountDownLatch(1); var jobs=new ArrayList<Future<Boolean>>();
            for(int i=0;i<2;i++){ final int number=i; jobs.add(pool.submit(() -> {start.await();try{accounts.bootstrap("admin_"+number,UUID.randomUUID().toString());return true;}catch(ApiException e){return false;}})); }
            start.countDown(); int successes=0;for(var job:jobs)if(job.get(20,TimeUnit.SECONDS))successes++;
            assertThat(successes).isEqualTo(1);
        }
    }
    @Test void invalidPasswordDoesNotLeavePartialAdministrator() {
        assertThatThrownBy(() -> accounts.bootstrap("first_admin","short")).isInstanceOf(ApiException.class);
        assertThat(jdbc.queryForObject("select count(*) from users",Integer.class)).isZero();
    }
}
