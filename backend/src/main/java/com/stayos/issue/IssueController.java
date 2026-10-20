package com.stayos.issue;
import com.stayos.auth.SessionIdentity;
import com.stayos.common.ApiResponse;
import com.stayos.issue.IssueDtos.*;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.Locale;
@RestController @RequestMapping("/api") @PreAuthorize("hasAuthority('issue:report')")
public class IssueController {
 private final IssueService service;public IssueController(IssueService service) { this.service=service; }
 private long actor(Authentication a) { return ((SessionIdentity)a.getPrincipal()).userId(); }
 @GetMapping("/room-operations/{operation}/{key}") public ApiResponse<?> result(Authentication a,@PathVariable String operation,@PathVariable String key) { return ApiResponse.ok(service.result(actor(a),operation,key)); }
 @GetMapping("/room-issues") public ApiResponse<?> list(Authentication a,@Valid @ModelAttribute Filter filter) { return ApiResponse.ok(service.list(actor(a),filter)); }
 @GetMapping("/room-issues/statistics") public ApiResponse<?> statistics(Authentication a) { return ApiResponse.ok(service.statistics(actor(a))); }
 @GetMapping("/room-issues/{id}") public ApiResponse<?> detail(Authentication a,@PathVariable long id) { return ApiResponse.ok(service.detail(actor(a),id)); }
 @GetMapping("/room-issues/{id}/history") public ApiResponse<?> history(Authentication a,@PathVariable long id) { return ApiResponse.ok(service.history(actor(a),id)); }
 @PostMapping("/room-issues") public ApiResponse<?> report(Authentication a,@RequestHeader(value="Idempotency-Key",required=false) String key,@Valid @RequestBody Report input) { return ApiResponse.ok(service.report(actor(a),key,input)); }
 @PostMapping("/room-issues/{id}/{action:start|resolve|severity}") @PreAuthorize("hasAuthority('issue:resolve')")
 public ApiResponse<?> act(Authentication a,@PathVariable long id,@PathVariable String action,@RequestHeader(value="Idempotency-Key",required=false) String key,@Valid @RequestBody Action input) { return ApiResponse.ok(service.act(actor(a),key,id,action.toUpperCase(Locale.ROOT),input)); }
 @GetMapping("/rooms/{id}/maintenance-history") public ApiResponse<?> maintenanceHistory(Authentication a,@PathVariable long id) { return ApiResponse.ok(service.maintenanceHistory(actor(a),id)); }
 @PostMapping("/rooms/{id}/maintenance-stop") @PreAuthorize("hasAuthority('issue:resolve')")
 public ApiResponse<?> stop(Authentication a,@PathVariable long id,@RequestHeader(value="Idempotency-Key",required=false) String key,@Valid @RequestBody Stop input) { return ApiResponse.ok(service.stop(actor(a),key,id,input)); }
 @PostMapping("/rooms/{id}/maintenance-resume") @PreAuthorize("hasAuthority('issue:resolve')")
 public ApiResponse<?> resume(Authentication a,@PathVariable long id,@RequestHeader(value="Idempotency-Key",required=false) String key,@Valid @RequestBody Resume input) { return ApiResponse.ok(service.resume(actor(a),key,id,input)); }
}
