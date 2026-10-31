package com.stayos.auth;

import javax.sql.DataSource;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;

/** Fail closed in prod if application credentials can administer PostgreSQL or rewrite audit history. */
@Component @Profile("prod")
public class ProductionDatabaseGuard implements ApplicationRunner {
    private final DataSource dataSource;
    public ProductionDatabaseGuard(DataSource dataSource){this.dataSource=dataSource;}
    @Override public void run(ApplicationArguments args) throws Exception {
        try(var connection=dataSource.getConnection();var statement=connection.createStatement();var r=statement.executeQuery("""
            select rolsuper or rolcreatedb or rolcreaterole or rolreplication or rolbypassrls
                or has_schema_privilege(current_user,'public','CREATE')
                or has_table_privilege(current_user,'audit_logs','UPDATE')
                or has_table_privilege(current_user,'audit_logs','DELETE') unsafe
            from pg_roles where rolname=current_user
            """)) {
            if(!r.next() || r.getBoolean(1))throw new IllegalStateException("Production runtime database account has excessive privileges; configure a separate restricted runtime role");
        }
    }
}
