package com.stayos.auth;

import com.stayos.common.ApiResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/workspaces")
public class WorkspaceController {
    private ApiResponse<?> workspace(String name) { return ApiResponse.ok(Map.of("name", name, "message", "认证及权限已验证，业务功能将在后续阶段开发")); }
    @GetMapping("/admin") @PreAuthorize("hasAuthority('system:manage')")
    public ApiResponse<?> admin() { return workspace("系统管理员工作区"); }
    @GetMapping("/hotel-manager") @PreAuthorize("hasAuthority('manager:workspace')")
    public ApiResponse<?> hotelManager() { return workspace("酒店总经理工作区"); }
    @GetMapping("/front-desk") @PreAuthorize("hasAuthority('stay:manage')")
    public ApiResponse<?> frontDesk() { return workspace("前台工作区"); }
    @GetMapping("/housekeeping-manager") @PreAuthorize("hasAuthority('housekeeping:manage')")
    public ApiResponse<?> housekeepingManager() { return workspace("客房主管工作区"); }
    @GetMapping("/housekeeping-staff") @PreAuthorize("hasAuthority('housekeeping:execute')")
    public ApiResponse<?> housekeepingStaff() { return workspace("客房服务员工作区"); }
}
