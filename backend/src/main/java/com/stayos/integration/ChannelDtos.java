package com.stayos.integration;

/** Channel management DTOs. Vendor-specific shapes never appear here — only neutral facts. */
final class ChannelDtos {

    private ChannelDtos() {}

    public record ConnectionInput(String supplierCode, String name, String baseUrl, String credentialRef,
                                  String callbackSecretRef, String notes) {}

    public record ConnectionUpdate(Long version, String status, String baseUrl, String credentialRef,
                                   String callbackSecretRef, String notes) {}

    public record MappingInput(String kind, Long localId, String externalCode) {}

    public record ProcessInput(Integer inboundLimit, Integer outboxLimit) {}
}
