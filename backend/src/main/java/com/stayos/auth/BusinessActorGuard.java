package com.stayos.auth;

import com.stayos.common.ApiException;
import com.stayos.staff.mapper.UserMapper;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** User shared lock precedes stay/room locks and is held until the business commits. */
@Service
public class BusinessActorGuard {
    private final UserMapper users;
    public BusinessActorGuard(UserMapper users) { this.users=users; }
    @Transactional(propagation=Propagation.MANDATORY)
    public void require(long actor,String permission) {
        var user=users.lockForBusiness(actor);
        var auth=SecurityContextHolder.getContext().getAuthentication();
        if(user==null || !user.enabled || auth!=null && auth.getPrincipal() instanceof SessionIdentity identity
            && (identity.userId()!=actor || identity.securityVersion()!=user.securityVersion))
            throw new ApiException(403,"ACCOUNT_UNAVAILABLE","账号状态已变化，请重新登录");
        if(!RolePermissions.permissions(users.roles(actor)).contains(permission))
            throw new ApiException(403,"ACCESS_DENIED","当前账号没有执行此操作的权限");
    }
}
