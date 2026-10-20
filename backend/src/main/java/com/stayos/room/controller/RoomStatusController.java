package com.stayos.room.controller;

import com.stayos.room.dto.RoomFilter;
import com.stayos.room.service.RoomStatusService;
import com.stayos.common.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;

@RestController
@RequestMapping("/api")
@PreAuthorize("hasAuthority('room:read')")
public class RoomStatusController {
    private final RoomStatusService service;
    public RoomStatusController(RoomStatusService service) { this.service=service; }
    @GetMapping("/room-status")
    public ApiResponse<?> board(@Valid @ModelAttribute RoomFilter query) { return ApiResponse.ok(service.board(query)); }
    @GetMapping("/room-status/statistics")
    public ApiResponse<?> statistics(@Valid @ModelAttribute RoomFilter query) { return ApiResponse.ok(service.statistics(query)); }
    @GetMapping("/floors")
    public ApiResponse<?> floors() { return ApiResponse.ok(service.floors()); }
}
