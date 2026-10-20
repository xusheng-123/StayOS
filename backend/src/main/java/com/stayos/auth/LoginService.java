package com.stayos.auth;

import com.stayos.staff.mapper.UserMapper;
import com.stayos.audit.AuditService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.util.UUID;

@Service
public class LoginService {
    private final UserMapper users;
    private final PasswordEncoder passwords;
    private final AuditService audit;
    private final int maxFailures;
    private final Duration lockDuration;
    private final String dummyHash;
    public LoginService(UserMapper users, PasswordEncoder passwords, AuditService audit,
            @Value("${stayos.auth.max-failures}") int maxFailures, @Value("${stayos.auth.lock-duration}") Duration lockDuration) {
        this.users = users; this.passwords = passwords; this.audit = audit;
        this.maxFailures = maxFailures; this.lockDuration = lockDuration;
        this.dummyHash = passwords.encode(UUID.randomUUID().toString());
    }
    public record Result(SessionIdentity identity, UserView user, Long failedActor) { public boolean accepted() { return identity != null; } }

    @Transactional
    public Result login(String username, String password) {
        var user = users.lockByUsername(PasswordPolicy.username(username));
        // Do a password hash comparison for unknown, disabled and locked accounts as well.
        boolean matches = passwords.matches(password, user == null ? dummyHash : user.passwordHash);
        Instant now = Instant.now();
        if (user == null || !user.enabled || (user.lockedUntil != null && user.lockedUntil.isAfter(now))) {
            return new Result(null, null, user == null ? null : user.id);
        }
        if (!matches) {
            int failures = (user.lockedUntil != null ? 0 : user.failedLoginAttempts) + 1;
            users.loginAttempts(user.id, failures, failures >= maxFailures ? now.plus(lockDuration) : null);
            return new Result(null, null, user.id);
        }
        users.loginAttempts(user.id, 0, null);
        var roles = users.roles(user.id);
        audit.success(user.id, "LOGIN", "USER", user.id);
        return new Result(new SessionIdentity(user.id, user.securityVersion), UserView.of(user, roles), null);
    }
}
