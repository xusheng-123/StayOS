package com.stayos;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class StayosApplication {
    public static void main(String[] args) {
        var context = SpringApplication.run(StayosApplication.class, args);
        if (context.getEnvironment().getProperty("stayos.bootstrap-admin", Boolean.class, false)) context.close();
    }
}
