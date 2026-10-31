package com.stayos.billing;

import com.stayos.auth.SessionIdentity;
import com.stayos.billing.BillingDtos.*;
import com.stayos.common.ApiResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@PreAuthorize("hasAuthority('billing:read')")
public class BillingController {
    private final BillingConfigService config;
    private final FolioService folios;
    private final ServiceOrderService orders;
    public BillingController(BillingConfigService config,FolioService folios,ServiceOrderService orders) {
        this.config=config; this.folios=folios; this.orders=orders;
    }
    private static long actor(Authentication auth) { return ((SessionIdentity)auth.getPrincipal()).userId(); }
    private static String key(String value) { return value; }

    // ---------- billing configuration ----------
    @GetMapping("/api/billing/policies")
    public ApiResponse<?> policies(Authentication auth) { return ApiResponse.ok(config.policyOverview(actor(auth))); }
    @PostMapping("/api/billing/policies")
    public ApiResponse<?> savePolicy(@RequestBody PolicyInput input,Authentication auth) {
        return ApiResponse.ok(config.createPolicy(actor(auth),input));
    }
    @GetMapping("/api/billing/rate-plans")
    public ApiResponse<?> ratePlans(Authentication auth) { return ApiResponse.ok(config.ratePlans(actor(auth))); }
    @PostMapping("/api/billing/rate-plans")
    public ApiResponse<?> createRatePlan(@RequestBody RatePlanCreateInput input,Authentication auth) {
        return ApiResponse.ok(config.createRatePlan(actor(auth),input));
    }
    @PutMapping("/api/billing/rate-plans/{id}")
    public ApiResponse<?> updateRatePlan(@PathVariable long id,@RequestBody RatePlanUpdateInput input,Authentication auth) {
        return ApiResponse.ok(config.updateRatePlan(actor(auth),id,input));
    }
    @GetMapping("/api/billing/rate-plans/{id}/rate-days")
    public ApiResponse<?> rateDays(@PathVariable long id,@RequestParam String from,@RequestParam String to,Authentication auth) {
        return ApiResponse.ok(config.rateDays(actor(auth),id,from,to));
    }
    @PostMapping("/api/billing/rate-plans/{id}/rate-days")
    public ApiResponse<?> saveRateDay(@PathVariable long id,@RequestBody RateDayInput input,Authentication auth) {
        return ApiResponse.ok(config.upsertRateDay(actor(auth),id,input));
    }
    @GetMapping("/api/billing/service-catalog")
    public ApiResponse<?> catalog(Authentication auth) { return ApiResponse.ok(config.catalog(actor(auth))); }
    @PostMapping("/api/billing/service-catalog")
    public ApiResponse<?> createCatalogItem(@RequestBody CatalogCreateInput input,Authentication auth) {
        return ApiResponse.ok(config.createCatalogItem(actor(auth),input));
    }
    @PutMapping("/api/billing/service-catalog/{id}")
    public ApiResponse<?> updateCatalogItem(@PathVariable long id,@RequestBody CatalogUpdateInput input,Authentication auth) {
        return ApiResponse.ok(config.updateCatalogItem(actor(auth),id,input));
    }

    // ---------- folio queries and actions ----------
    @GetMapping("/api/billing/folios")
    public ApiResponse<?> folioList(@RequestParam(defaultValue="ALL") String status,
            @RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int pageSize,Authentication auth) {
        return ApiResponse.ok(folios.list(actor(auth),status,page,pageSize));
    }
    @GetMapping("/api/folios/result/{key}")
    public ApiResponse<?> receipt(@PathVariable String key,@RequestParam String operation,Authentication auth) {
        if(operation.startsWith("SERVICE_ORDER")) return ApiResponse.ok(orders.receipt(actor(auth),operation,key(key)));
        return ApiResponse.ok(folios.receipt(actor(auth),operation,key(key)));
    }
    @GetMapping("/api/folios/{id}")
    public ApiResponse<?> folioDetail(@PathVariable long id,Authentication auth) {
        return ApiResponse.ok(folios.detail(actor(auth),id));
    }
    @PostMapping("/api/folios/{id}/charges")
    public ApiResponse<?> postCharge(@PathVariable long id,@RequestBody ChargeInput input,
            @RequestHeader(value="Idempotency-Key",required=false) String key,Authentication auth) {
        return ApiResponse.ok(folios.postCharge(actor(auth),key(key),id,input));
    }
    @PostMapping("/api/folios/{id}/adjustments")
    public ApiResponse<?> adjust(@PathVariable long id,@RequestBody AdjustInput input,
            @RequestHeader(value="Idempotency-Key",required=false) String key,Authentication auth) {
        return ApiResponse.ok(folios.adjust(actor(auth),key(key),id,input));
    }
    @PostMapping("/api/folios/{id}/payments")
    public ApiResponse<?> receive(@PathVariable long id,@RequestBody PaymentInput input,
            @RequestHeader(value="Idempotency-Key",required=false) String key,Authentication auth) {
        return ApiResponse.ok(folios.receive(actor(auth),key(key),id,input));
    }
    @PostMapping("/api/folios/{id}/refunds")
    public ApiResponse<?> refund(@PathVariable long id,@RequestBody RefundInput input,
            @RequestHeader(value="Idempotency-Key",required=false) String key,Authentication auth) {
        return ApiResponse.ok(folios.refund(actor(auth),key(key),id,input));
    }
    @PostMapping("/api/folios/{id}/close")
    public ApiResponse<?> close(@PathVariable long id,@RequestBody CloseInput input,
            @RequestHeader(value="Idempotency-Key",required=false) String key,Authentication auth) {
        return ApiResponse.ok(folios.close(actor(auth),key(key),id,input));
    }
    @PostMapping("/api/folios/{id}/reopen")
    public ApiResponse<?> reopen(@PathVariable long id,@RequestBody ReopenInput input,
            @RequestHeader(value="Idempotency-Key",required=false) String key,Authentication auth) {
        return ApiResponse.ok(folios.reopen(actor(auth),key(key),id,input));
    }

    // ---------- stay-scoped folio and service orders ----------
    @GetMapping("/api/stays/{id}/folio")
    public ApiResponse<?> folioByStay(@PathVariable long id,Authentication auth) {
        return ApiResponse.ok(folios.byStay(actor(auth),id));
    }
    @PostMapping("/api/stays/{id}/folio")
    public ApiResponse<?> openFolio(@PathVariable long id,@RequestBody(required=false) FolioOpenInput input,
            @RequestHeader(value="Idempotency-Key",required=false) String key,Authentication auth) {
        return ApiResponse.ok(folios.open(actor(auth),key(key),id,input));
    }
    @GetMapping("/api/stays/{id}/service-orders")
    public ApiResponse<?> ordersByStay(@PathVariable long id,Authentication auth) {
        return ApiResponse.ok(orders.byStay(actor(auth),id));
    }
    @PostMapping("/api/stays/{id}/service-orders")
    public ApiResponse<?> createOrder(@PathVariable long id,@RequestBody ServiceOrderCreateInput input,
            @RequestHeader(value="Idempotency-Key",required=false) String key,Authentication auth) {
        return ApiResponse.ok(orders.create(actor(auth),key(key),id,input));
    }
    @PutMapping("/api/service-orders/{id}")
    public ApiResponse<?> updateOrder(@PathVariable long id,@RequestBody ServiceOrderUpdateInput input,Authentication auth) {
        return ApiResponse.ok(orders.update(actor(auth),id,input));
    }
    @PostMapping("/api/service-orders/{id}/fulfill")
    public ApiResponse<?> fulfillOrder(@PathVariable long id,
            @RequestHeader(value="Idempotency-Key",required=false) String key,Authentication auth) {
        return ApiResponse.ok(orders.fulfill(actor(auth),key(key),id));
    }
    @PostMapping("/api/service-orders/{id}/cancel")
    public ApiResponse<?> cancelOrder(@PathVariable long id,@RequestBody ServiceOrderCancelInput input,
            @RequestHeader(value="Idempotency-Key",required=false) String key,Authentication auth) {
        return ApiResponse.ok(orders.cancel(actor(auth),key(key),id,input));
    }
}
