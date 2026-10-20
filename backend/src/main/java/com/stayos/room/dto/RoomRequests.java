package com.stayos.room.dto;

import jakarta.validation.constraints.*;

public final class RoomRequests {
    private RoomRequests() {}
    public record TypeInput(@NotBlank @Size(max=50) String typeCode,
            @NotBlank @Size(max=100) String typeName, @Size(max=2000) String description,
            @Size(max=200) String bedDescription, @NotNull @Min(1) @Max(20) Integer suggestedOccupancy,
            @PositiveOrZero Long version) {}
    public record RoomInput(@NotBlank @Size(max=20) String roomNumber,
            @NotNull @Min(-99) @Max(199) Integer floor, @NotNull @Positive Long roomTypeId,
            @Size(max=2000) String notes, @PositiveOrZero Long version) {}
    public record Enabled(@NotNull Boolean enabled, @NotNull @PositiveOrZero Long version) {}
}
