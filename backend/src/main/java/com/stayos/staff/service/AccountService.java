package com.stayos.staff.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.stayos.staff.mapper.UserMapper;
import com.stayos.staff.entity.UserAccount;
import com.stayos.auth.*;
import com.stayos.audit.AuditService;
import com.stayos.common.ApiException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

@Service
public class AccountService {
    private final UserMapper users;
    private final PasswordEncoder passwords;
    private final AuditService audit;
    public AccountService(UserMapper users, PasswordEncoder passwords, AuditService audit) { this.users = users; this.passwords = passwords; this.audit = audit; }

    public record Page(List<UserView> items, long total, int page, int pageSize) {}
    public Page list(int page, int pageSize) {
        if (page < 1 || pageSize < 1 || pageSize > 100 || page > 100000) throw new ApiException(400, "VALIDATION_ERROR", "分页参数不正确");
        var query = new QueryWrapper<UserAccount>().orderByAsc("id").last("LIMIT " + pageSize + " OFFSET " + ((long)(page - 1) * pageSize));
        return new Page(users.selectList(query).stream().map(u -> UserView.of(u, users.roles(u.id))).toList(), users.selectCount(null), page, pageSize);
    }
    private void validateRoles(Set<String> roles) {
        if (roles == null || roles.isEmpty() || !RolePermissions.ROLES.containsAll(roles)) throw new ApiException(400, "VALIDATION_ERROR", "请选择有效的系统角色");
    }
    private UserAccount required(long id) {
        var user = users.lockById(id);
        if (user == null) throw new ApiException(404, "USER_NOT_FOUND", "账号不存在");
        return user;
    }
    private UserView insert(String inputName, String password, String displayName, Set<String> roles) {
        String username = PasswordPolicy.username(inputName);
        PasswordPolicy.validateUsername(username); PasswordPolicy.validatePassword(password); validateRoles(roles);
        if (displayName == null || displayName.isBlank() || displayName.length() > 100) throw new ApiException(400, "VALIDATION_ERROR", "请填写有效显示姓名");
        var account = new UserAccount(); account.username = username; account.passwordHash = passwords.encode(password);
        account.displayName = displayName.trim(); account.enabled = true;
        users.insert(account);
        for (var role : new TreeSet<>(roles)) users.addRole(account.id, role);
        return UserView.of(users.selectById(account.id), users.roles(account.id));
    }
    @Transactional
    public UserView create(long actor, String username, String password, String name, Set<String> roles) {
        var result = insert(username, password, name, roles);
        audit.success(actor, "USER_CREATE", "USER", result.id()); return result;
    }
    @Transactional
    public UserView bootstrap(String username, String password) {
        users.lockAccountAdministration();
        if (users.administratorCount() > 0) throw new ApiException(409, "ADMIN_ALREADY_INITIALIZED", "已有管理员，禁止再次初始化");
        var result = insert(username, password, "系统管理员", Set.of("ADMIN"));
        audit.success(result.id(), "ADMIN_BOOTSTRAP", "USER", result.id()); return result;
    }
    @Transactional
    public void setEnabled(long actor, long id, boolean enabled) {
        users.lockAccountAdministration();
        var user = required(id);
        if (!enabled && user.enabled && users.roles(id).contains("ADMIN") && users.enabledAdministratorCount() <= 1)
            throw new ApiException(409, "LAST_ADMIN_REQUIRED", "至少保留一名启用的系统管理员");
        users.setEnabled(id, enabled); audit.success(actor, "USER_SET_ENABLED", "USER", id);
    }
    @Transactional
    public void setRoles(long actor, long id, Set<String> roles) {
        validateRoles(roles); users.lockAccountAdministration(); var user = required(id);
        if (user.enabled && users.roles(id).contains("ADMIN") && !roles.contains("ADMIN") && users.enabledAdministratorCount() <= 1)
            throw new ApiException(409, "LAST_ADMIN_REQUIRED", "至少保留一名启用的系统管理员");
        if (!new HashSet<>(users.roles(id)).equals(roles)) {
            users.removeRoles(id); for (var role : new TreeSet<>(roles)) users.addRole(id, role);
        }
        audit.success(actor, "USER_SET_ROLES", "USER", id);
    }
    @Transactional
    public void resetPassword(long actor, long id, String password) {
        PasswordPolicy.validatePassword(password); required(id); users.setPassword(id, passwords.encode(password));
        audit.success(actor, "USER_RESET_PASSWORD", "USER", id);
    }
}
