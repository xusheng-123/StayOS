package com.stayos.housekeeping.controller;
import com.stayos.auth.SessionIdentity;
import com.stayos.common.ApiResponse;
import com.stayos.housekeeping.dto.*;
import com.stayos.housekeeping.service.*;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.Locale;
@RestController @RequestMapping("/api/housekeeping-tasks")
@PreAuthorize("hasAuthority('housekeeping:read')")
public class TaskController {
    private final AssignmentService assignment;
    private final TaskQueryService query;
    private final TaskWorkflowService workflow;
    public TaskController(TaskQueryService query,TaskWorkflowService workflow,AssignmentService assignment) { this.query=query;this.workflow=workflow;this.assignment=assignment; }
    private long actor(Authentication auth) { return ((SessionIdentity)auth.getPrincipal()).userId(); }
    @GetMapping public ApiResponse<?> list(@Valid @ModelAttribute TaskFilter filter,Authentication auth) { return ApiResponse.ok(query.list(actor(auth),filter)); }
    @GetMapping("/statistics") public ApiResponse<?> statistics(Authentication auth) { return ApiResponse.ok(query.statistics(actor(auth))); }
    @GetMapping("/employees") @PreAuthorize("hasAuthority('housekeeping:manage')")
    public ApiResponse<?> employees(Authentication auth) { return ApiResponse.ok(query.employees(actor(auth))); }
    @GetMapping("/assignees") @PreAuthorize("hasAuthority('housekeeping:manage')")
    public ApiResponse<?> assignees(Authentication auth) { return ApiResponse.ok(query.assignees(actor(auth))); }
    @GetMapping("/{id}") public ApiResponse<?> detail(@PathVariable long id,Authentication auth) { return ApiResponse.ok(query.detail(actor(auth),id)); }
    @GetMapping("/{id}/history") public ApiResponse<?> history(@PathVariable long id,Authentication auth) { return ApiResponse.ok(query.history(actor(auth),id)); }
    @PostMapping("/manual") @PreAuthorize("hasAuthority('housekeeping:manage')")
    public ApiResponse<?> manual(@RequestHeader(value="Idempotency-Key",required=false) String key,@Valid @RequestBody TaskRequests.Manual input,Authentication auth) {
        return ApiResponse.ok(workflow.manual(actor(auth),key,input));
    }
    @GetMapping("/assignment-employees") @PreAuthorize("hasAuthority('housekeeping:manage')")
    public ApiResponse<?> assignmentEmployees(Authentication auth) { return ApiResponse.ok(assignment.employees(actor(auth))); }
    @GetMapping("/assignment-employees/{id}/tasks") @PreAuthorize("hasAuthority('housekeeping:manage')")
    public ApiResponse<?> employeeTasks(@PathVariable long id,Authentication auth) { return ApiResponse.ok(assignment.currentTasks(actor(auth),id)); }
    @GetMapping("/assignment-rooms") @PreAuthorize("hasAuthority('housekeeping:manage')")
    public ApiResponse<?> assignmentRooms(@Valid @ModelAttribute AssignmentDtos.Filter filter,Authentication auth) { return ApiResponse.ok(assignment.rooms(actor(auth),filter)); }
    @GetMapping("/assignment-batches/{id}") @PreAuthorize("hasAuthority('housekeeping:manage')")
    public ApiResponse<?> batchReceipt(@PathVariable long id,Authentication auth) { return ApiResponse.ok(assignment.receipt(actor(auth),id)); }
    @GetMapping("/assignment-results/{key}") @PreAuthorize("hasAuthority('housekeeping:manage')")
    public ApiResponse<?> batchResult(@PathVariable String key,Authentication auth) { return ApiResponse.ok(assignment.result(actor(auth),key)); }
    @PostMapping("/batch-assign") @PreAuthorize("hasAuthority('housekeeping:manage')")
    public ApiResponse<?> batchAssign(@RequestHeader(value="Idempotency-Key",required=false) String key,@Valid @RequestBody AssignmentDtos.Request input,Authentication auth) {
        return ApiResponse.ok(assignment.assign(actor(auth),key,input));
    }
    @PostMapping("/{id}/claim") @PreAuthorize("denyAll()")
    public ApiResponse<?> claim() { throw new com.stayos.common.ApiException(403,"SELF_CLAIM_DISABLED","自主领取已关闭，请由主管进行分房"); }
    @PostMapping("/{id}/{action:assign|reassign|start|complete}") @PreAuthorize("(#action == 'assign' or #action == 'reassign') ? hasAuthority('housekeeping:manage') : hasAuthority('housekeeping:execute')")
    public ApiResponse<?> act(@PathVariable long id,@PathVariable String action,@RequestHeader(value="Idempotency-Key",required=false) String key,@Valid @RequestBody TaskRequests.Action input,Authentication auth) {
        return ApiResponse.ok(workflow.act(actor(auth),key,id,action.toUpperCase(Locale.ROOT),input));
    }
}
