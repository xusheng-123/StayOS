package com.stayos.auth;

import java.util.*;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

public final class RolePermissions {
    public static final Set<String> ROLES = Set.of("ADMIN", "HOTEL_MANAGER", "FRONT_DESK", "HOUSEKEEPING_MANAGER", "HOUSEKEEPING_STAFF");
    private static final Map<String, Set<String>> MAPPING = Map.of(
        "ADMIN", Set.of("room:read", "room:manage", "system:manage", "audit:management"),
        "FRONT_DESK", Set.of("room:read", "stay:manage", "stay:guest-correct", "handover:front-desk", "issue:report", "billing:read", "billing:charge", "billing:receive", "cashier:operate"),
        "HOUSEKEEPING_STAFF", Set.of("room:read", "housekeeping:read", "housekeeping:execute", "issue:report"),
        "HOUSEKEEPING_MANAGER", Set.of("room:read", "housekeeping:read", "housekeeping:execute", "housekeeping:manage", "handover:housekeeping", "issue:report", "issue:resolve", "audit:housekeeping"),
        "HOTEL_MANAGER", Set.of("room:read", "stay:manage", "stay:guest-correct", "handover:front-desk", "housekeeping:read", "housekeeping:execute", "housekeeping:manage", "handover:housekeeping", "issue:report", "issue:resolve", "manager:workspace", "audit:business", "billing:read", "billing:charge", "billing:receive", "billing:adjust", "billing:refund", "billing:configure", "cashier:operate", "cashier:close", "report:finance")
    );
    private RolePermissions() {}
    public static Set<String> permissions(Collection<String> roles) {
        var result = new TreeSet<String>();
        roles.forEach(role -> result.addAll(MAPPING.getOrDefault(role, Set.of())));
        return Collections.unmodifiableSet(result);
    }
    public static List<SimpleGrantedAuthority> authorities(Collection<String> roles) {
        return permissions(roles).stream().map(SimpleGrantedAuthority::new).toList();
    }
}
