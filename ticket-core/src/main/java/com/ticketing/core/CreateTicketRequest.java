package com.ticketing.core;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Email and tenant are deliberately absent: they come from the security context only. */
public record CreateTicketRequest(
        @NotBlank @Size(max = 120) String title,
        @NotBlank @Pattern(regexp = "^\\+?[0-9]{7,15}$", message = "must be 7-15 digits, optional leading +") String mobile,
        @NotBlank @Size(max = 4000) String description) {
}
