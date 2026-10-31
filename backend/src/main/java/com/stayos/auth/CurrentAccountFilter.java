package com.stayos.auth;

import com.stayos.staff.mapper.UserMapper;
import com.stayos.common.ApiResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

// Created inside the SecurityFilterChain only, so it cannot also run as a servlet filter.
public class CurrentAccountFilter extends OncePerRequestFilter {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(CurrentAccountFilter.class);
    private final UserMapper users;
    private final IpLoginLimiter limiter;
    private final ObjectMapper json;
    private final ClientAddressResolver addresses;
    public CurrentAccountFilter(UserMapper users, IpLoginLimiter limiter, ObjectMapper json,ClientAddressResolver addresses) { this.users = users; this.limiter = limiter; this.json = json;this.addresses=addresses; }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        if (request.getMethod().equals("POST") && request.getServletPath().equals("/api/auth/login") && !limiter.allow(addresses.resolve(request))) {
            response.setStatus(429); response.setHeader("Retry-After", "60"); response.setContentType("application/json;charset=UTF-8");
            json.writeValue(response.getOutputStream(), ApiResponse.error("TOO_MANY_REQUESTS", "登录尝试过于频繁，请稍后重试")); return;
        }
        var auth = SecurityContextHolder.getContext().getAuthentication();
        boolean sessionMaintenance = request.getMethod().equals("GET") && request.getServletPath().equals("/api/auth/csrf")
            || request.getMethod().equals("POST") && request.getServletPath().equals("/api/auth/logout")
            || request.getMethod().equals("GET") && (request.getServletPath().equals("/api/health") || request.getServletPath().equals("/api/health/ready"));
        // CSRF and local session invalidation must remain available during a database outage.
        if (!sessionMaintenance && auth != null && auth.getPrincipal() instanceof SessionIdentity identity) {
          try {
            var user = users.selectById(identity.userId());
            if (user == null || !user.enabled || user.securityVersion != identity.securityVersion()) {
                var session = request.getSession(false);
                if (session != null) session.invalidate();
                SecurityContextHolder.clearContext();
                response.setStatus(401);response.setContentType("application/json;charset=UTF-8");
                json.writeValue(response.getOutputStream(),ApiResponse.error("UNAUTHORIZED","请重新登录"));
                return;
            } else {
                var fresh = SecurityContextHolder.createEmptyContext();
                fresh.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(identity, null, RolePermissions.authorities(users.roles(user.id))));
                SecurityContextHolder.setContext(fresh);
            }
          } catch (org.springframework.dao.DataAccessException unavailable) {
              log.warn("Account verification database unavailable; returning SERVICE_UNAVAILABLE.");
              response.setStatus(503); response.setContentType("application/json;charset=UTF-8");
              json.writeValue(response.getOutputStream(), ApiResponse.error("SERVICE_UNAVAILABLE", "暂时无法验证账号状态，请保留原请求标识，服务恢复后重试或查询记录"));
              return;
          }
        }
        chain.doFilter(request, response);
    }
}
