package com.stayos.reservation;

import com.stayos.auth.SessionIdentity;
import com.stayos.common.ApiResponse;
import com.stayos.reservation.ReservationDtos.*;
import com.stayos.stay.dto.StayRequests;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
public class ReservationController {
    private final ReservationService reservations;
    private final InventoryService inventory;
    public ReservationController(ReservationService reservations,InventoryService inventory) {
        this.reservations=reservations; this.inventory=inventory;
    }
    private static long actor(Authentication auth) { return ((SessionIdentity)auth.getPrincipal()).userId(); }

    @GetMapping("/api/reservations")
    @PreAuthorize("hasAuthority('reservation:manage')")
    public ApiResponse<?> list(@RequestParam(defaultValue="ALL") String status,
            @RequestParam(required=false) String arrivalFrom,@RequestParam(required=false) String arrivalTo,
            @RequestParam(required=false) String query,
            @RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int pageSize,Authentication auth) {
        return ApiResponse.ok(reservations.list(actor(auth),status,arrivalFrom,arrivalTo,query,page,pageSize));
    }
    @PostMapping("/api/reservations")
    @PreAuthorize("hasAuthority('reservation:manage')")
    public ApiResponse<?> create(@RequestBody CreateInput input,
            @RequestHeader(value="Idempotency-Key",required=false) String key,Authentication auth) {
        return ApiResponse.ok(reservations.create(actor(auth),key,input));
    }
    @GetMapping("/api/reservations/{id}")
    @PreAuthorize("hasAuthority('reservation:manage')")
    public ApiResponse<?> detail(@PathVariable long id,Authentication auth) {
        return ApiResponse.ok(reservations.detail(actor(auth),id));
    }
    @PostMapping("/api/reservations/{id}/modify")
    @PreAuthorize("hasAuthority('reservation:manage')")
    public ApiResponse<?> modify(@PathVariable long id,@RequestBody ModifyInput input,
            @RequestHeader(value="Idempotency-Key",required=false) String key,Authentication auth) {
        return ApiResponse.ok(reservations.modify(actor(auth),key,id,input));
    }
    @PostMapping("/api/reservations/{id}/confirm")
    @PreAuthorize("hasAuthority('reservation:manage')")
    public ApiResponse<?> confirm(@PathVariable long id,
            @RequestHeader(value="Idempotency-Key",required=false) String key,Authentication auth) {
        return ApiResponse.ok(reservations.confirm(actor(auth),key,id));
    }
    @PostMapping("/api/reservations/{id}/cancel")
    @PreAuthorize("hasAuthority('reservation:manage')")
    public ApiResponse<?> cancel(@PathVariable long id,@RequestBody CancelInput input,
            @RequestHeader(value="Idempotency-Key",required=false) String key,Authentication auth) {
        return ApiResponse.ok(reservations.cancel(actor(auth),key,id,input));
    }
    @PostMapping("/api/reservations/{id}/mark-no-show")
    @PreAuthorize("hasAuthority('reservation:manage')")
    public ApiResponse<?> markNoShow(@PathVariable long id,@RequestBody(required=false) NoShowInput input,
            @RequestHeader(value="Idempotency-Key",required=false) String key,Authentication auth) {
        return ApiResponse.ok(reservations.markNoShow(actor(auth),key,id,input));
    }
    @PostMapping("/api/reservations/{id}/assign-room")
    @PreAuthorize("hasAuthority('reservation:manage')")
    public ApiResponse<?> assignRoom(@PathVariable long id,@RequestBody AssignRoomInput input,
            @RequestHeader(value="Idempotency-Key",required=false) String key,Authentication auth) {
        return ApiResponse.ok(reservations.assignRoom(actor(auth),key,id,input));
    }
    @PostMapping("/api/reservations/{id}/check-in")
    @PreAuthorize("hasAuthority('reservation:manage')")
    public ApiResponse<?> checkIn(@PathVariable long id,@RequestBody StayRequests.CheckIn input,
            @RequestHeader(value="Idempotency-Key",required=false) String key,Authentication auth) {
        return ApiResponse.ok(reservations.checkIn(actor(auth),key,id,input));
    }
    @PostMapping("/api/reservations/release-expired")
    @PreAuthorize("hasAuthority('reservation:manage')")
    public ApiResponse<?> releaseExpired(Authentication auth) {
        return ApiResponse.ok(reservations.releaseExpired(actor(auth)));
    }
    @PostMapping("/api/reservations/{id}/deposits")
    @PreAuthorize("hasAuthority('reservation:manage')")
    public ApiResponse<?> receiveDeposit(@PathVariable long id,@RequestBody DepositInput input,
            @RequestHeader(value="Idempotency-Key",required=false) String key,Authentication auth) {
        return ApiResponse.ok(reservations.receiveDeposit(actor(auth),key,id,input));
    }
    @PostMapping("/api/reservations/{id}/deposits/refund")
    @PreAuthorize("hasAuthority('reservation:manage')")
    public ApiResponse<?> refundDeposit(@PathVariable long id,@RequestBody DepositRefundInput input,
            @RequestHeader(value="Idempotency-Key",required=false) String key,Authentication auth) {
        return ApiResponse.ok(reservations.refundDeposit(actor(auth),key,id,input));
    }
    @GetMapping("/api/reservations/receipt")
    @PreAuthorize("hasAuthority('reservation:manage')")
    public ApiResponse<?> receipt(@RequestParam String operation,@RequestParam String key,Authentication auth) {
        return ApiResponse.ok(reservations.receipt(actor(auth),operation,key));
    }
    @GetMapping("/api/availability")
    @PreAuthorize("hasAuthority('reservation:manage')")
    public ApiResponse<?> availability(@RequestParam(required=false) String arrivalDate,
            @RequestParam(required=false) String departureDate,Authentication auth) {
        return ApiResponse.ok(inventory.availability(actor(auth),arrivalDate,departureDate));
    }
    @GetMapping("/api/inventory/blocks")
    @PreAuthorize("hasAuthority('inventory:manage')")
    public ApiResponse<?> blocks(@RequestParam(defaultValue="false") boolean includeReleased,
            @RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int pageSize,Authentication auth) {
        return ApiResponse.ok(inventory.blocks(actor(auth),includeReleased,page,pageSize));
    }
    @PostMapping("/api/inventory/blocks")
    @PreAuthorize("hasAuthority('inventory:manage')")
    public ApiResponse<?> createBlock(@RequestBody BlockCreateInput input,
            @RequestHeader(value="Idempotency-Key",required=false) String key,Authentication auth) {
        return ApiResponse.ok(inventory.createBlock(actor(auth),key,input));
    }
    @PostMapping("/api/inventory/blocks/{id}/release")
    @PreAuthorize("hasAuthority('inventory:manage')")
    public ApiResponse<?> releaseBlock(@PathVariable long id,
            @RequestHeader(value="Idempotency-Key",required=false) String key,Authentication auth) {
        return ApiResponse.ok(inventory.releaseBlock(actor(auth),key,id));
    }
}
