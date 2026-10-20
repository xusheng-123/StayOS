package com.stayos.auth;

import com.stayos.staff.entity.UserAccount;
import java.util.*;

public record UserView(long id, String username, String displayName, boolean enabled, List<String> roles, Set<String> permissions) {
    public static UserView of(UserAccount account, List<String> roles) {
        return new UserView(account.id, account.username, account.displayName, account.enabled, List.copyOf(roles), RolePermissions.permissions(roles));
    }
}
