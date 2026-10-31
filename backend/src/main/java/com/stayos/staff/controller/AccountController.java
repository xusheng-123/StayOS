package com.stayos.staff.controller;

import com.stayos.staff.service.AccountService;
import com.stayos.auth.*;
import com.stayos.common.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.Authentication;
import org.springframework.security.access.prepost.PreAuthorize;
import java.util.*;

@RestController
@PreAuthorize("hasAuthority('system:manage')")
@RequestMapping("/api")
public class AccountController {
    private final AccountService accounts;
    public AccountController(AccountService accounts) { this.accounts = accounts; }
    public record NewUser(@NotBlank @Size(max=100) String username, @NotBlank @Size(max=72) String password,
            @NotBlank @Size(max=100) String displayName, @NotEmpty Set<String> roles) {
        @Override public String toString() { return "NewUser[REDACTED]"; }
    }
    public record Enabled(@NotNull Boolean enabled) {}
    public record Roles(@NotEmpty Set<String> roles) {}
    public record Password(@NotBlank @Size(max=72) String password) {
        @Override public String toString() { return "Password[REDACTED]"; }
    }
    private long actor(Authentication auth) { return ((SessionIdentity) auth.getPrincipal()).userId(); }
    @GetMapping("/users") public ApiResponse<?> list(@RequestParam(defaultValue="1") int page, @RequestParam(defaultValue="20") int pageSize,@RequestParam(defaultValue="") String search) { return ApiResponse.ok(accounts.list(page, pageSize,search)); }
    @GetMapping("/roles") public ApiResponse<?> roles() { return ApiResponse.ok(new TreeSet<>(RolePermissions.ROLES)); }
    @PostMapping("/users") public ApiResponse<?> create(@Valid @RequestBody NewUser input, Authentication auth) {
        return ApiResponse.ok(accounts.create(actor(auth), input.username(), input.password(), input.displayName(), input.roles()));
    }
    @PostMapping("/users/{id}/set-enabled") public ApiResponse<?> enabled(@PathVariable long id, @Valid @RequestBody Enabled input, Authentication auth) {
        accounts.setEnabled(actor(auth), id, input.enabled()); return ApiResponse.ok(null);
    }
    @PutMapping("/users/{id}/roles") public ApiResponse<?> roles(@PathVariable long id, @Valid @RequestBody Roles input, Authentication auth) {
        accounts.setRoles(actor(auth), id, input.roles()); return ApiResponse.ok(null);
    }
    @PostMapping("/users/{id}/reset-password") public ApiResponse<?> password(@PathVariable long id, @Valid @RequestBody Password input, Authentication auth) {
        accounts.resetPassword(actor(auth), id, input.password()); return ApiResponse.ok(null);
    }
}
