package com.stayos.housekeeping.dto;
import jakarta.validation.constraints.*;
public final class TaskRequests {
    private TaskRequests() {}
    public record Manual(@NotNull @Positive Long roomId,@NotBlank @Size(max=1000) String reason) {}
    public record Action(@NotNull @PositiveOrZero Long version,@Positive Long assigneeId,@Size(max=1000) String reason) {}
}
