package com.stayos.audit;
import com.stayos.auth.SessionIdentity;
import com.stayos.common.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/audit-logs")
@PreAuthorize("hasAnyAuthority('audit:management','audit:business','audit:housekeeping')")
public class AuditController {
    private final AuditQuery query;
    public AuditController(AuditQuery query) { this.query=query; }
    private long actor(Authentication a) { return ((SessionIdentity)a.getPrincipal()).userId(); }
    @GetMapping public ApiResponse<?> list(Authentication a,@Valid @ModelAttribute AuditQuery.Filter filter) { return ApiResponse.ok(query.list(actor(a),filter)); }
    @GetMapping("/options") public ApiResponse<?> options(Authentication a) { return ApiResponse.ok(query.options(actor(a))); }
    @GetMapping("/{id}") public ApiResponse<?> detail(Authentication a,@PathVariable long id) { return ApiResponse.ok(query.detail(actor(a),id)); }
}
