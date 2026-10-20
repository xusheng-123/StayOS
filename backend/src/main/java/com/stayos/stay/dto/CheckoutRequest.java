package com.stayos.stay.dto;
import jakarta.validation.constraints.*;
public record CheckoutRequest(@NotNull @Positive Long segmentId) {}
