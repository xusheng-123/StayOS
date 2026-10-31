package com.stayos.stay.controller;

import com.stayos.auth.SessionIdentity;
import com.stayos.common.ApiResponse;
import com.stayos.stay.dto.StayExtensionDtos.Request;
import com.stayos.stay.service.StayExtensionService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/stays")
@PreAuthorize("hasAuthority('stay:manage')")
public class StayExtensionController {
    private final StayExtensionService service;
    public StayExtensionController(StayExtensionService service) { this.service=service; }
    @PostMapping("/{id}/extensions")
    public ApiResponse<?> extend(@PathVariable long id, @RequestHeader(value="Idempotency-Key",required=false) String key,
                                 @RequestBody Request input, Authentication auth) {
        return ApiResponse.ok(service.extend(((SessionIdentity)auth.getPrincipal()).userId(),key,id,input));
    }
    @GetMapping("/{id}/extensions")
    public ApiResponse<?> history(@PathVariable long id, @RequestParam(defaultValue="1") int page,
                                  @RequestParam(defaultValue="20") int pageSize) {
        return ApiResponse.ok(service.history(id,page,pageSize));
    }
    @GetMapping("/extensions/result/{key}")
    public ApiResponse<?> receipt(@PathVariable String key, Authentication auth) {
        return ApiResponse.ok(service.receipt(((SessionIdentity)auth.getPrincipal()).userId(),key));
    }
}
