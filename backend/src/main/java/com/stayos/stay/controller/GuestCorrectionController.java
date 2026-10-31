package com.stayos.stay.controller;

import com.stayos.auth.SessionIdentity;
import com.stayos.common.ApiResponse;
import com.stayos.stay.dto.GuestCorrectionDtos.Request;
import com.stayos.stay.service.GuestCorrectionService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/stays")
@PreAuthorize("hasAuthority('stay:guest-correct')")
public class GuestCorrectionController {
    private final GuestCorrectionService service;
    public GuestCorrectionController(GuestCorrectionService service) { this.service=service; }
    @PostMapping("/{stay}/guests/{guest}/corrections")
    public ApiResponse<?> correct(@PathVariable long stay,@PathVariable long guest,@RequestBody Request input,
            @RequestHeader(value="Idempotency-Key",required=false) String key,Authentication auth) {
        return ApiResponse.ok(service.correct(((SessionIdentity)auth.getPrincipal()).userId(),key,stay,guest,input));
    }
    @GetMapping("/{stay}/guests/{guest}/corrections")
    public ApiResponse<?> history(@PathVariable long stay,@PathVariable long guest,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int pageSize) {
        return ApiResponse.ok(service.history(stay,guest,page,pageSize));
    }
    @GetMapping("/guest-corrections/result/{key}")
    public ApiResponse<?> receipt(@PathVariable String key,Authentication auth) {
        return ApiResponse.ok(service.receipt(((SessionIdentity)auth.getPrincipal()).userId(),key));
    }
}
