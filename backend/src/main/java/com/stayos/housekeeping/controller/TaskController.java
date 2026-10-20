package com.stayos.housekeeping.controller;
import com.stayos.common.ApiResponse;
import com.stayos.housekeeping.dto.TaskFilter;
import com.stayos.housekeeping.service.TaskQueryService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/housekeeping-tasks")
@PreAuthorize("hasAuthority('housekeeping:read')")
public class TaskController {
    private final TaskQueryService query;
    public TaskController(TaskQueryService query) { this.query=query; }
    @GetMapping public ApiResponse<?> list(@Valid @ModelAttribute TaskFilter filter) { return ApiResponse.ok(query.list(filter)); }
    @GetMapping("/{id}") public ApiResponse<?> detail(@PathVariable long id) { return ApiResponse.ok(query.detail(id)); }
}
