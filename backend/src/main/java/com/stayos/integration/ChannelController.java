package com.stayos.integration;

import com.stayos.auth.BusinessActorGuard;
import com.stayos.auth.SessionIdentity;
import com.stayos.common.ApiResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
public class ChannelController {

    final ChannelService channels;
    final BusinessActorGuard actors;

    public ChannelController(ChannelService channels, BusinessActorGuard actors) {
        this.channels = channels;
        this.actors = actors;
    }

    private static long actor(Authentication auth) { return ((SessionIdentity) auth.getPrincipal()).userId(); }

    @GetMapping("/api/channels")
    @PreAuthorize("hasAuthority('channel:manage')")
    public ApiResponse<?> list(Authentication auth) { return ApiResponse.ok(channels.list(actor(auth))); }

    @PostMapping("/api/channels")
    @PreAuthorize("hasAuthority('channel:manage')")
    public ApiResponse<?> create(@RequestBody ChannelDtos.ConnectionInput input, Authentication auth) {
        return ApiResponse.ok(channels.create(actor(auth), input));
    }

    @GetMapping("/api/channels/{id}")
    @PreAuthorize("hasAuthority('channel:manage')")
    public ApiResponse<?> detail(@PathVariable long id) { return ApiResponse.ok(channels.detail(id)); }

    @PatchMapping("/api/channels/{id}")
    @PreAuthorize("hasAuthority('channel:manage')")
    public ApiResponse<?> update(@PathVariable long id, @RequestBody ChannelDtos.ConnectionUpdate input, Authentication auth) {
        return ApiResponse.ok(channels.update(actor(auth), id, input));
    }

    @GetMapping("/api/channels/{id}/mappings")
    @PreAuthorize("hasAuthority('channel:manage')")
    public ApiResponse<?> mappings(@PathVariable long id) { return ApiResponse.ok(channels.mappings(id)); }

    @PostMapping("/api/channels/{id}/mappings")
    @PreAuthorize("hasAuthority('channel:manage')")
    public ApiResponse<?> addMapping(@PathVariable long id, @RequestBody ChannelDtos.MappingInput input, Authentication auth) {
        return ApiResponse.ok(channels.addMapping(actor(auth), id, input));
    }

    @DeleteMapping("/api/channels/mappings/{mappingId}")
    @PreAuthorize("hasAuthority('channel:manage')")
    public ApiResponse<?> deleteMapping(@PathVariable long mappingId, Authentication auth) {
        channels.deleteMapping(actor(auth), mappingId);
        return ApiResponse.ok(Map.of("deleted", true));
    }

    @GetMapping("/api/channels/{id}/inbound")
    @PreAuthorize("hasAuthority('channel:manage')")
    public ApiResponse<?> inbound(@PathVariable long id, @RequestParam(defaultValue = "ALL") String status,
                                  @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int pageSize) {
        return ApiResponse.ok(channels.inboundPage(id, status, Math.max(1, page), Math.min(100, pageSize)));
    }

    @PostMapping("/api/channels/inbound/{eventId}/retry")
    @PreAuthorize("hasAuthority('channel:manage')")
    public ApiResponse<?> retryInbound(@PathVariable long eventId, Authentication auth) {
        channels.retryInbound(actor(auth), eventId);
        return ApiResponse.ok(Map.of("requeued", true));
    }

    @GetMapping("/api/channels/{id}/outbox")
    @PreAuthorize("hasAuthority('channel:manage')")
    public ApiResponse<?> outbox(@PathVariable long id, @RequestParam(defaultValue = "ALL") String status,
                                 @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int pageSize) {
        return ApiResponse.ok(channels.outboxPage(id, status, Math.max(1, page), Math.min(100, pageSize)));
    }

    @PostMapping("/api/channels/outbox/{eventId}/retry")
    @PreAuthorize("hasAuthority('channel:manage')")
    public ApiResponse<?> retryOutbox(@PathVariable long eventId, Authentication auth) {
        channels.retryOutbox(actor(auth), eventId);
        return ApiResponse.ok(Map.of("requeued", true));
    }

    @PostMapping("/api/channels/{id}/process-now")
    @PreAuthorize("hasAuthority('channel:manage')")
    public ApiResponse<?> processNow(@PathVariable long id, @RequestBody(required = false) ChannelDtos.ProcessInput input, Authentication auth) {
        int inboundLimit = input == null || input.inboundLimit() == null ? 50 : Math.min(200, input.inboundLimit());
        int outboxLimit = input == null || input.outboxLimit() == null ? 50 : Math.min(200, input.outboxLimit());
        return ApiResponse.ok(channels.processNow(actor(auth), id, inboundLimit, outboxLimit));
    }

    @PostMapping("/api/channels/{id}/reconcile")
    @PreAuthorize("hasAuthority('channel:manage')")
    public ApiResponse<?> reconcile(@PathVariable long id, Authentication auth) {
        return ApiResponse.ok(channels.reconcile(actor(auth), id));
    }

    @GetMapping("/api/channels/{id}/reconciliations")
    @PreAuthorize("hasAuthority('channel:manage')")
    public ApiResponse<?> reconciliations(@PathVariable long id) {
        return ApiResponse.ok(channels.reconciliationRuns(id));
    }
}
