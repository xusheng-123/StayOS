package com.stayos.stay.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.OffsetDateTime;
import java.util.List;

public final class StayRequests {
    private StayRequests() {}
    public record GuestInput(@NotBlank @Size(max=100) String name,
        @NotBlank @Pattern(regexp="ID_CARD|PASSPORT|OTHER") String documentType,
        @NotBlank @Size(max=64) String documentNumber,
        @Size(max=30) String phone,@Size(max=1000) String notes,boolean primary) {}
    public record CheckIn(@NotNull @Positive Long roomId,
        @NotNull OffsetDateTime expectedCheckOutAt,
        @NotEmpty @Size(max=20) List<@NotNull @Valid GuestInput> guests,
        @Size(max=1000) String notes) {}
}
