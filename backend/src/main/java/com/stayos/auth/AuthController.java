package com.stayos.auth;

import com.stayos.common.*;
import com.stayos.staff.mapper.UserMapper;
import jakarta.servlet.http.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.Authentication;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import java.util.Map;

@RestController
@org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
@RequestMapping("/api")
public class AuthController {
    private final LoginService login;
    private final UserMapper users;
    private final SecurityContextRepository contexts;
    private final SessionAuthenticationStrategy sessions;
    private final com.stayos.audit.AuditService audit;
    public AuthController(LoginService login, UserMapper users, SecurityContextRepository contexts, SessionAuthenticationStrategy sessions, com.stayos.audit.AuditService audit) {
        this.login = login; this.users = users; this.contexts = contexts; this.sessions = sessions; this.audit = audit;
    }
    public record Credentials(@NotBlank @Size(max=100) String username, @NotBlank @Size(max=72) String password) {
        @Override public String toString() { return "Credentials[REDACTED]"; }
    }
    @GetMapping("/health") ApiResponse<?> health() { return ApiResponse.ok(Map.of("status", "UP")); }
    @GetMapping("/auth/csrf") ApiResponse<?> csrf(CsrfToken token) { return ApiResponse.ok(Map.of("headerName", token.getHeaderName(), "token", token.getToken())); }
    @PostMapping("/auth/login") ApiResponse<UserView> login(@Valid @RequestBody Credentials input, HttpServletRequest request, HttpServletResponse response) {
        if (input.password().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72)
            throw new ApiException(400, "VALIDATION_ERROR", "输入内容过长");
        var result = login.login(input.username(), input.password());
        if (!result.accepted()) {
            // Login transaction is finished before independent failure auditing; no cross-transaction row lock conflict.
            audit.failure(result.failedActor(), "LOGIN", "USER", result.failedActor());
            throw new ApiException(401, "LOGIN_FAILED", "账号或密码错误，或账号暂不可用");
        }
        var auth = UsernamePasswordAuthenticationToken.authenticated(result.identity(), null, RolePermissions.authorities(result.user().roles()));
        sessions.onAuthentication(auth, request, response);
        var context = SecurityContextHolder.createEmptyContext(); context.setAuthentication(auth);
        SecurityContextHolder.setContext(context); contexts.saveContext(context, request, response);
        return ApiResponse.ok(result.user());
    }
    @GetMapping({"/auth/me", "/auth/status"}) ApiResponse<UserView> me(Authentication auth) {
        var identity = (SessionIdentity) auth.getPrincipal();
        return ApiResponse.ok(UserView.of(users.selectById(identity.userId()), users.roles(identity.userId())));
    }
}
