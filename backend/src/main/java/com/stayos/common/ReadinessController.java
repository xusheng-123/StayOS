package com.stayos.common;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
public class ReadinessController {
    private final DataSource dataSource;
    public ReadinessController(DataSource dataSource) { this.dataSource=dataSource; }
    @GetMapping("/api/health/ready")
    public ResponseEntity<?> ready() {
        try(var connection=dataSource.getConnection();var statement=connection.createStatement()) {
            statement.setQueryTimeout(2);
            try(var result=statement.executeQuery("select 1")) {
                if(result.next() && result.getInt(1)==1) return ResponseEntity.ok(ApiResponse.ok(Map.of("status","UP")));
            }
        } catch(SQLException unavailable) { /* Intentionally omit database details. */ }
        return ResponseEntity.status(503).body(ApiResponse.error("NOT_READY","服务暂未就绪"));
    }
}
