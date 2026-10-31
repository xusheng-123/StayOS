package com.stayos.cashier;

import com.stayos.auth.SessionIdentity;
import com.stayos.cashier.CashierDtos.*;
import com.stayos.common.ApiResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
public class CashierController {
    private final CashierShiftService shifts;
    private final BusinessDayService days;
    public CashierController(CashierShiftService shifts,BusinessDayService days) { this.shifts=shifts; this.days=days; }
    private static long actor(Authentication auth) { return ((SessionIdentity)auth.getPrincipal()).userId(); }

    @GetMapping("/api/cashier-shifts")
    @PreAuthorize("hasAuthority('cashier:operate')")
    public ApiResponse<?> shiftList(@RequestParam(defaultValue="mine") String scope,
            @RequestParam(defaultValue="ALL") String status,
            @RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int pageSize,Authentication auth) {
        return ApiResponse.ok(shifts.list(actor(auth),scope,status,page,pageSize));
    }
    @PostMapping("/api/cashier-shifts")
    @PreAuthorize("hasAuthority('cashier:operate')")
    public ApiResponse<?> openShift(@RequestBody ShiftOpenInput input,
            @RequestHeader(value="Idempotency-Key",required=false) String key,Authentication auth) {
        return ApiResponse.ok(shifts.open(actor(auth),key,input));
    }
    @GetMapping("/api/cashier-shifts/{id}")
    @PreAuthorize("hasAuthority('cashier:operate')")
    public ApiResponse<?> shiftDetail(@PathVariable long id,Authentication auth) {
        return ApiResponse.ok(shifts.detail(actor(auth),id));
    }
    @PostMapping("/api/cashier-shifts/{id}/submit")
    @PreAuthorize("hasAuthority('cashier:operate')")
    public ApiResponse<?> submitShift(@PathVariable long id,@RequestBody ShiftSubmitInput input,
            @RequestHeader(value="Idempotency-Key",required=false) String key,Authentication auth) {
        return ApiResponse.ok(shifts.submit(actor(auth),key,id,input));
    }
    @PostMapping("/api/cashier-shifts/{id}/confirm")
    @PreAuthorize("hasAuthority('cashier:close')")
    public ApiResponse<?> confirmShift(@PathVariable long id,@RequestBody ShiftConfirmInput input,
            @RequestHeader(value="Idempotency-Key",required=false) String key,Authentication auth) {
        return ApiResponse.ok(shifts.confirm(actor(auth),key,id,input));
    }
    @GetMapping("/api/business-days")
    @PreAuthorize("hasAuthority('cashier:operate')")
    public ApiResponse<?> businessDays(@RequestParam(defaultValue="14") int limit,Authentication auth) {
        return ApiResponse.ok(days.days(actor(auth),limit));
    }
    @PostMapping("/api/business-days/{date}/close")
    @PreAuthorize("hasAuthority('cashier:close')")
    public ApiResponse<?> closeDay(@PathVariable String date,Authentication auth) {
        return ApiResponse.ok(days.close(actor(auth),java.time.LocalDate.parse(date)));
    }
    @GetMapping("/api/reports/daily")
    @PreAuthorize("hasAuthority('report:finance')")
    public ApiResponse<?> dailyReports(@RequestParam(required=false) String from,@RequestParam(required=false) String to,
            @RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int pageSize,Authentication auth) {
        java.time.LocalDate fromDay=null,toDay=null;
        try {
            if(from!=null) fromDay=java.time.LocalDate.parse(from);
            if(to!=null) toDay=java.time.LocalDate.parse(to);
        } catch(java.time.format.DateTimeParseException failure) { throw new com.stayos.common.ApiException(400,"VALIDATION_ERROR","日期格式不正确"); }
        return ApiResponse.ok(days.reports(actor(auth),fromDay,toDay,page,pageSize));
    }
}
