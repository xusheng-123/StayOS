package com.stayos.room.controller;

import com.stayos.room.dto.*;
import com.stayos.room.dto.RoomRequests.*;
import com.stayos.room.service.RoomCatalogService;
import com.stayos.auth.SessionIdentity;
import com.stayos.common.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
@PreAuthorize("hasAuthority('room:read')")
public class RoomCatalogController {
    private final RoomCatalogService service;
    public RoomCatalogController(RoomCatalogService service) { this.service=service; }
    private long actor(Authentication auth) { return ((SessionIdentity)auth.getPrincipal()).userId(); }
    @GetMapping("/room-types")
    public ApiResponse<?> types(@RequestParam(defaultValue="1") int page, @RequestParam(defaultValue="20") int pageSize,
            @RequestParam(required=false) Boolean enabled, @RequestParam(required=false) String search) {
        return ApiResponse.ok(service.listTypes(page,pageSize,enabled,search));
    }
    @PostMapping("/room-types") @PreAuthorize("hasAuthority('room:manage')")
    public ApiResponse<?> createType(@Valid @RequestBody TypeInput input, Authentication auth) { return ApiResponse.ok(service.createType(actor(auth),input)); }
    @PutMapping("/room-types/{id}") @PreAuthorize("hasAuthority('room:manage')")
    public ApiResponse<?> editType(@PathVariable long id, @Valid @RequestBody TypeInput input, Authentication auth) { return ApiResponse.ok(service.editType(actor(auth),id,input)); }
    @PostMapping("/room-types/{id}/set-enabled") @PreAuthorize("hasAuthority('room:manage')")
    public ApiResponse<?> enableType(@PathVariable long id, @Valid @RequestBody Enabled input, Authentication auth) { return ApiResponse.ok(service.enableType(actor(auth),id,input)); }
    @GetMapping("/rooms")
    public ApiResponse<?> rooms(@Valid @ModelAttribute RoomFilter query) { return ApiResponse.ok(service.list(query)); }
    @GetMapping("/rooms/{id}")
    public ApiResponse<?> detail(@PathVariable long id) { return ApiResponse.ok(service.detail(id)); }
    @PostMapping("/rooms") @PreAuthorize("hasAuthority('room:manage')")
    public ApiResponse<?> createRoom(@Valid @RequestBody RoomInput input, Authentication auth) { return ApiResponse.ok(service.createRoom(actor(auth),input)); }
    @PutMapping("/rooms/{id}") @PreAuthorize("hasAuthority('room:manage')")
    public ApiResponse<?> editRoom(@PathVariable long id, @Valid @RequestBody RoomInput input, Authentication auth) { return ApiResponse.ok(service.editRoom(actor(auth),id,input)); }
    @PostMapping("/rooms/{id}/set-enabled") @PreAuthorize("hasAuthority('room:manage')")
    public ApiResponse<?> enableRoom(@PathVariable long id, @Valid @RequestBody Enabled input, Authentication auth) { return ApiResponse.ok(service.enableRoom(actor(auth),id,input)); }
}
