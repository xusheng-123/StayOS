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
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(SecurityConfig.class);
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
            CsrfTokenRepository csrf, UserMapper users, IpLoginLimiter limiter, ObjectMapper json, AuditService audit,ClientAddressResolver addresses) throws Exception {
        http.securityContext(context -> context.securityContextRepository(contexts).requireExplicitSave(true))
            // Supplier callbacks are the ONE CSRF-exempt surface (guide section 14): exact POST paths only,
            // and each request still has to pass the adapter's signature check before anything persists.
            .csrf(config -> config.csrfTokenRepository(csrf)
                .ignoringRequestMatchers(new org.springframework.security.web.util.matcher.RegexRequestMatcher("/api/integration/callbacks/[^/]+", "POST")))
            .requestCache(cache -> cache.disable())
            .formLogin(form -> form.disable()).httpBasic(basic -> basic.disable())
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/health", "/api/health/ready", "/api/auth/csrf", "/api/auth/login").permitAll()
                .requestMatchers(org.springframework.http.HttpMethod.POST, "/api/integration/callbacks/*").permitAll()
                .requestMatchers("/api/auth/me", "/api/auth/status", "/api/auth/logout", "/api/workspaces/**", "/api/users/**", "/api/roles").authenticated()
                .requestMatchers("/api/room-types", "/api/room-types/**", "/api/rooms", "/api/rooms/**", "/api/room-status", "/api/room-status/**", "/api/floors").authenticated()
                 .requestMatchers("/api/room-issues", "/api/room-issues/**", "/api/room-operations/**").authenticated()
                .requestMatchers("/api/stays", "/api/stays/**").authenticated()
                .requestMatchers("/api/audit-logs", "/api/audit-logs/**").authenticated()
                .requestMatchers("/api/operations/**").authenticated()
                .requestMatchers("/api/handovers", "/api/handovers/**").authenticated()
                .requestMatchers("/api/billing", "/api/billing/**", "/api/folios", "/api/folios/**", "/api/service-orders", "/api/service-orders/**").authenticated()
                .requestMatchers("/api/cashier-shifts", "/api/cashier-shifts/**", "/api/business-days", "/api/business-days/**", "/api/reports", "/api/reports/**").authenticated()
                .requestMatchers("/api/reservations", "/api/reservations/**", "/api/availability", "/api/inventory", "/api/inventory/**").authenticated()
                .requestMatchers("/api/channels", "/api/channels/**").authenticated()
                .requestMatchers("/api/customer-profiles", "/api/customer-profiles/**").authenticated()
                .requestMatchers("/api/housekeeping-tasks", "/api/housekeeping-tasks/**").authenticated()
                .anyRequest().denyAll())
            .exceptionHandling(errors -> errors
                .authenticationEntryPoint((request, response, e) -> write(response, json, 401, "UNAUTHORIZED", "请重新登录"))
                .accessDeniedHandler((request, response, e) -> {
                    var auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
                    Long actor = auth != null && auth.getPrincipal() instanceof SessionIdentity identity ? identity.userId() : null;
                    try { audit.denied(actor); } catch (org.springframework.dao.DataAccessException | org.springframework.transaction.TransactionException ignored) { /* Failure reporting must not expose a database exception. Success audit remains fail-closed. */ }
                    if (e instanceof CsrfException) write(response, json, 403, "CSRF_INVALID", "页面安全校验已过期，请刷新后重试");
                    else write(response, json, 403, "FORBIDDEN", "你没有权限执行此操作");
                }))
            .logout(logout -> logout.logoutUrl("/api/auth/logout")
                .addLogoutHandler((request, response, auth) -> {
                    if (auth != null && auth.getPrincipal() instanceof SessionIdentity id) {
                        try { audit.success(id.userId(), "LOGOUT", "USER", id.userId()); }
                        catch (RuntimeException failure) {
                            // Unlike a hotel business commit, logout must always revoke the session.
                            log.warn("Logout audit unavailable id={}; session invalidation will continue.",org.slf4j.MDC.get("requestId"));
                        }
                    }
                })
                .deleteCookies("STAYOS_SESSION")
                .logoutSuccessHandler((request, response, auth) -> {
                    response.setContentType("application/json;charset=UTF-8"); json.writeValue(response.getOutputStream(), ApiResponse.ok(null));
                }))
            .addFilterBefore(new CurrentAccountFilter(users, limiter, json,addresses), CsrfFilter.class);
        return http.build();
    }
}
