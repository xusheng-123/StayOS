package com.stayos.customer;

import com.stayos.auth.SessionIdentity;
import com.stayos.common.ApiResponse;
import com.stayos.customer.CustomerDtos.*;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/customer-profiles")
public class CustomerProfileController {
    private final CustomerProfileService profiles;
    public CustomerProfileController(CustomerProfileService profiles) { this.profiles=profiles; }
    private static long actor(Authentication auth) { return ((SessionIdentity)auth.getPrincipal()).userId(); }

    @GetMapping @PreAuthorize("hasAuthority('customer:read')")
    public ApiResponse<?> list(@RequestParam(required=false) String query,@RequestParam(defaultValue="ACTIVE") String status,
        @RequestParam(required=false) Integer page,@RequestParam(required=false) Integer pageSize,Authentication auth) {
        var q=new CustomerProfileMapper.Filter();q.query=query==null?"":query.trim();q.status=status;q.page=page==null?1:page;q.pageSize=pageSize==null?20:pageSize;
        return ApiResponse.ok(profiles.list(actor(auth),q));
    }
    @GetMapping("/{id}") @PreAuthorize("hasAuthority('customer:read')")
    public ApiResponse<?> detail(@PathVariable long id,Authentication auth) { return ApiResponse.ok(profiles.detail(actor(auth),id)); }

    @PostMapping @PreAuthorize("hasAuthority('customer:manage')")
    public ApiResponse<?> create(@RequestHeader(value="Idempotency-Key",required=false) String key,@Valid @RequestBody ProfileInput input,Authentication auth) {
        return ApiResponse.ok(profiles.create(actor(auth),key,input));
    }
    @PutMapping("/{id}") @PreAuthorize("hasAuthority('customer:manage')")
    public ApiResponse<?> update(@PathVariable long id,@RequestHeader(value="Idempotency-Key",required=false) String key,@Valid @RequestBody ProfileUpdate input,Authentication auth) {
        return ApiResponse.ok(profiles.update(actor(auth),key,id,input));
    }
    @PostMapping("/{id}/set-status") @PreAuthorize("hasAuthority('customer:manage')")
    public ApiResponse<?> setStatus(@PathVariable long id,@RequestHeader(value="Idempotency-Key",required=false) String key,@Valid @RequestBody StatusInput input,Authentication auth) {
        return ApiResponse.ok(profiles.setStatus(actor(auth),key,id,input));
    }
    @PostMapping("/{id}/merge") @PreAuthorize("hasAuthority('customer:merge')")
    public ApiResponse<?> merge(@PathVariable long id,@RequestHeader(value="Idempotency-Key",required=false) String key,@Valid @RequestBody MergeInput input,Authentication auth) {
        return ApiResponse.ok(profiles.merge(actor(auth),key,id,input));
    }
    @PostMapping("/{id}/stays") @PreAuthorize("hasAuthority('customer:manage')")
    public ApiResponse<?> linkStay(@PathVariable long id,@RequestHeader(value="Idempotency-Key",required=false) String key,@Valid @RequestBody LinkInput input,Authentication auth) {
        return ApiResponse.ok(profiles.linkStay(actor(auth),key,id,input));
    }
    @PostMapping("/stay-links/{linkId}/detach") @PreAuthorize("hasAuthority('customer:manage')")
    public ApiResponse<?> detachStay(@PathVariable long linkId,@RequestHeader(value="Idempotency-Key",required=false) String key,Authentication auth) {
        return ApiResponse.ok(profiles.detachStay(actor(auth),key,linkId));
    }
}
