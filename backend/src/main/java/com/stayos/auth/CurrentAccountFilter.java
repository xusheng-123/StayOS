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
    private final UserMapper users;
    private final IpLoginLimiter limiter;
    private final ObjectMapper json;
    public CurrentAccountFilter(UserMapper users, IpLoginLimiter limiter, ObjectMapper json) { this.users = users; this.limiter = limiter; this.json = json; }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        if (request.getMethod().equals("POST") && request.getServletPath().equals("/api/auth/login") && !limiter.allow(request.getRemoteAddr())) {
            response.setStatus(429); response.setHeader("Retry-After", "60"); response.setContentType("application/json;charset=UTF-8");
            json.writeValue(response.getOutputStream(), ApiResponse.error("TOO_MANY_REQUESTS", "登录尝试过于频繁，请稍后重试")); return;
        }
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof SessionIdentity identity) {
            var user = users.selectById(identity.userId());
            if (user == null || !user.enabled || user.securityVersion != identity.securityVersion()) {
                var session = request.getSession(false);
                if (session != null) session.invalidate();
                SecurityContextHolder.clearContext();
            } else {
                var fresh = SecurityContextHolder.createEmptyContext();
                fresh.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(identity, null, RolePermissions.authorities(users.roles(user.id))));
                SecurityContextHolder.setContext(fresh);
            }
        }
        chain.doFilter(request, response);
    }
}
