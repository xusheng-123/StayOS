package com.stayos.auth;

import org.springframework.context.annotation.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.core.userdetails.*;

@Configuration
public class SecurityBeans {
    @Bean PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(12); }
    // Login uses LoginService; explicitly suppress Spring Boot's generated default user/password.
    @Bean UserDetailsService noDefaultUser() { return username -> { throw new UsernameNotFoundException("Unavailable"); }; }
}
