package com.stayos.stay.controller;
import com.stayos.auth.SessionIdentity;
import com.stayos.common.ApiResponse;
import com.stayos.room.dto.RoomFilter;
import com.stayos.stay.dto.RoomChangeDtos.Request;
import com.stayos.stay.service.RoomChangeService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/stays") @PreAuthorize("hasAuthority('stay:manage')")
public class RoomChangeController {
 private final RoomChangeService service;
 public RoomChangeController(RoomChangeService service) { this.service=service; }
 private long actor(Authentication a) { return ((SessionIdentity)a.getPrincipal()).userId(); }
 @GetMapping("/{id}/room-change/available-rooms") public ApiResponse<?> available(@PathVariable long id,@Valid @ModelAttribute RoomFilter f,Authentication a) { return ApiResponse.ok(service.available(actor(a),id,f)); }
 @PostMapping("/{id}/room-changes") public ApiResponse<?> change(@PathVariable long id,@RequestHeader(value="Idempotency-Key",required=false) String key,@Valid @RequestBody Request r,Authentication a) { return ApiResponse.ok(service.change(actor(a),key,id,r)); }
 @GetMapping("/{id}/room-changes") public ApiResponse<?> history(@PathVariable long id,Authentication a) { return ApiResponse.ok(service.history(actor(a),id)); }
 @GetMapping("/room-changes/result/{key}") public ApiResponse<?> result(@PathVariable String key,Authentication a) { return ApiResponse.ok(service.result(actor(a),key)); }
}
