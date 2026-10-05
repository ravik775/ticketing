package com.ticketing.security;

import java.util.Locale;
import java.util.Optional;

/** Roles a user can hold inside a single tenant. */
public enum Role {
    APPLICANT,
    APPROVER;

    public static Optional<Role> parse(String value) {
        if (value == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(valueOf(value.trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
