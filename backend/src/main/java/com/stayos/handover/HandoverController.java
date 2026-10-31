package com.stayos.handover;

import com.stayos.auth.SessionIdentity;
import com.stayos.common.ApiResponse;
import com.stayos.handover.HandoverDtos.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/handovers")
@PreAuthorize("hasAnyAuthority('handover:front-desk','handover:housekeeping')")
public class HandoverController {
    private final HandoverService service;
    public HandoverController(HandoverService service) { this.service=service; }

    @GetMapping
    public ApiResponse<?> list(@RequestParam String scope,@RequestParam(required=false) String status,
            @RequestParam(required=false) String involving,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int pageSize,
            Authentication auth) {
        boolean receiving="RECEIVING".equals(involving),mine="MINE".equals(involving);
        if (involving!=null && !receiving && !mine) throw new com.stayos.common.ApiException(400,"VALIDATION_ERROR","交班人筛选不正确");
        return ApiResponse.ok(service.list(((SessionIdentity)auth.getPrincipal()).userId(),scope,status,receiving,mine,page,pageSize));
    }
    @PostMapping
    public ApiResponse<?> create(@RequestBody CreateRequest input,
            @RequestHeader(value="Idempotency-Key",required=false) String key,Authentication auth) {
        return ApiResponse.ok(service.create(((SessionIdentity)auth.getPrincipal()).userId(),key,input));
    }
    @GetMapping("/result/{key}")
    public ApiResponse<?> receipt(@PathVariable String key,@RequestParam String operation,Authentication auth) {
        return ApiResponse.ok(service.receipt(((SessionIdentity)auth.getPrincipal()).userId(),operation,key));
    }
    @GetMapping("/candidates")
    public ApiResponse<?> candidates(@RequestParam String scope,Authentication auth) {
        return ApiResponse.ok(service.candidates(((SessionIdentity)auth.getPrincipal()).userId(),scope));
    }
    @GetMapping("/{id}")
    public ApiResponse<?> detail(@PathVariable long id,Authentication auth) {
        return ApiResponse.ok(service.detail(((SessionIdentity)auth.getPrincipal()).userId(),id));
    }
    @PutMapping("/{id}")
    public ApiResponse<?> update(@PathVariable long id,@RequestBody UpdateRequest input,Authentication auth) {
        return ApiResponse.ok(service.update(((SessionIdentity)auth.getPrincipal()).userId(),id,input));
    }
    @PostMapping("/{id}/submit")
    public ApiResponse<?> submit(@PathVariable long id,@RequestBody SubmitRequest input,
            @RequestHeader(value="Idempotency-Key",required=false) String key,Authentication auth) {
        return ApiResponse.ok(service.submit(((SessionIdentity)auth.getPrincipal()).userId(),key,id,input));
    }
    @PostMapping("/{id}/reassign")
    public ApiResponse<?> reassign(@PathVariable long id,@RequestBody ReassignRequest input,Authentication auth) {
        return ApiResponse.ok(service.reassign(((SessionIdentity)auth.getPrincipal()).userId(),id,input));
    }
    @PostMapping("/{id}/acknowledge")
    public ApiResponse<?> acknowledge(@PathVariable long id,@RequestBody SubmitRequest input,
            @RequestHeader(value="Idempotency-Key",required=false) String key,Authentication auth) {
        return ApiResponse.ok(service.acknowledge(((SessionIdentity)auth.getPrincipal()).userId(),key,id,input));
    }
}
