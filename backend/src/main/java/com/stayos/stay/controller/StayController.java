package com.stayos.stay.controller;

import com.stayos.auth.SessionIdentity;
import com.stayos.common.ApiResponse;
import com.stayos.room.dto.RoomFilter;
import com.stayos.room.service.RoomCatalogService;
import com.stayos.stay.dto.*;
import com.stayos.stay.dto.StayRequests.CheckIn;
import com.stayos.stay.service.*;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/stays")
@PreAuthorize("hasAuthority('stay:manage')")
public class StayController {
    private final CheckInService checkIn;
    private final StayQueryService query;
    private final RoomCatalogService rooms;
    private final CheckoutService checkout;
    public StayController(CheckInService checkIn,StayQueryService query,RoomCatalogService rooms,CheckoutService checkout) { this.checkIn=checkIn;this.query=query;this.rooms=rooms;this.checkout=checkout; }
    @PostMapping("/{id}/check-out")
    public ApiResponse<?> checkout(@PathVariable long id,@RequestHeader(value="Idempotency-Key",required=false) String key,@Valid @RequestBody CheckoutRequest input,Authentication auth) {
        return ApiResponse.ok(checkout.checkout(((SessionIdentity)auth.getPrincipal()).userId(),key,id,input));
    }
    @PostMapping
    public ApiResponse<?> create(@RequestHeader(value="Idempotency-Key",required=false) String key,@Valid @RequestBody CheckIn input,Authentication auth) {
        return ApiResponse.ok(query.detail(checkIn.create(((SessionIdentity)auth.getPrincipal()).userId(),key,input)));
    }
    @GetMapping public ApiResponse<?> list(@ModelAttribute StayFilter filter) { return ApiResponse.ok(query.list(filter)); }
    @GetMapping("/history") public ApiResponse<?> history(@ModelAttribute StayFilter f) {
        return ApiResponse.ok(query.list(new StayFilter(f.page(),f.pageSize(),"CHECKED_OUT",f.roomNumber(),f.guestName(),f.checkInFrom(),f.checkInTo())));
    }
    @GetMapping("/{id}") public ApiResponse<?> detail(@PathVariable long id) { return ApiResponse.ok(query.detail(id)); }
    @GetMapping("/available-rooms") public ApiResponse<?> available(@Valid @ModelAttribute RoomFilter f) {
        return ApiResponse.ok(rooms.list(new RoomFilter(f.page(),f.pageSize(),f.roomNumber(),f.floor(),f.roomTypeId(),f.availability(),f.cleaningStatus(),f.occupancyStatus(),f.maintenanceStatus(),f.enabled(),true)));
    }
}
