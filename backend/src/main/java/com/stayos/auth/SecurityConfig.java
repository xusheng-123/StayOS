package com.stayos.auth;

import com.stayos.audit.AuditService;
import com.stayos.common.ApiResponse;
import com.stayos.staff.mapper.UserMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.*;
import java.io.IOException;
import org.springframework.context.annotation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.*;
import org.springframework.security.web.context.*;
import org.springframework.security.web.csrf.*;
import org.springframework.security.web.authentication.session.*;
import java.util.List;

@Configuration
@EnableMethodSecurity
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class SecurityConfig {
    @Bean SecurityContextRepository securityContextRepository() { return new HttpSessionSecurityContextRepository(); }
    @Bean CsrfTokenRepository csrfTokenRepository() { return new HttpSessionCsrfTokenRepository(); }
    @Bean SessionAuthenticationStrategy sessionAuthenticationStrategy(CsrfTokenRepository csrf) {
        return new CompositeSessionAuthenticationStrategy(List.of(new ChangeSessionIdAuthenticationStrategy(), new CsrfAuthenticationStrategy(csrf)));
    }
    private static void write(HttpServletResponse response, ObjectMapper json, int status, String code, String message) throws IOException {
        response.setStatus(status); response.setContentType("application/json;charset=UTF-8");
        json.writeValue(response.getOutputStream(), ApiResponse.error(code, message));
    }
    @Bean SecurityFilterChain securityFilterChain(HttpSecurity http, SecurityContextRepository contexts,
            CsrfTokenRepository csrf, UserMapper users, IpLoginLimiter limiter, ObjectMapper json, AuditService audit) throws Exception {
        http.securityContext(context -> context.securityContextRepository(contexts).requireExplicitSave(true))
            .csrf(config -> config.csrfTokenRepository(csrf))
            .requestCache(cache -> cache.disable())
            .formLogin(form -> form.disable()).httpBasic(basic -> basic.disable())
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/health", "/api/auth/csrf", "/api/auth/login").permitAll()
                .requestMatchers("/api/auth/me", "/api/auth/status", "/api/auth/logout", "/api/workspaces/**", "/api/users/**", "/api/roles").authenticated()
                .requestMatchers("/api/room-types", "/api/room-types/**", "/api/rooms", "/api/rooms/**", "/api/room-status", "/api/room-status/**", "/api/floors").authenticated()
                .requestMatchers("/api/stays", "/api/stays/**").authenticated()
                .requestMatchers("/api/housekeeping-tasks", "/api/housekeeping-tasks/**").authenticated()
                .anyRequest().denyAll())
            .exceptionHandling(errors -> errors
                .authenticationEntryPoint((request, response, e) -> write(response, json, 401, "UNAUTHORIZED", "请重新登录"))
                .accessDeniedHandler((request, response, e) -> {
                    var auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
                    Long actor = auth != null && auth.getPrincipal() instanceof SessionIdentity identity ? identity.userId() : null;
                    audit.denied(actor);
                    if (e instanceof CsrfException) write(response, json, 403, "CSRF_INVALID", "页面安全校验已过期，请刷新后重试");
                    else write(response, json, 403, "FORBIDDEN", "你没有权限执行此操作");
                }))
            .logout(logout -> logout.logoutUrl("/api/auth/logout")
                .addLogoutHandler((request, response, auth) -> {
                    if (auth != null && auth.getPrincipal() instanceof SessionIdentity id) audit.success(id.userId(), "LOGOUT", "USER", id.userId());
                })
                .deleteCookies("STAYOS_SESSION")
                .logoutSuccessHandler((request, response, auth) -> {
                    response.setContentType("application/json;charset=UTF-8"); json.writeValue(response.getOutputStream(), ApiResponse.ok(null));
                }))
            .addFilterBefore(new CurrentAccountFilter(users, limiter, json), CsrfFilter.class);
        return http.build();
    }
}
