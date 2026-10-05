package com.ticketing.core;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record DecisionRequest(
        @NotNull Decision decision,
        @NotBlank @Size(max = 2000) String comment) {
}
