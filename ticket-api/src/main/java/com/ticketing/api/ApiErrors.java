package com.ticketing.api;

import com.ticketing.core.TicketConflictException;
import com.ticketing.core.TicketForbiddenException;
import com.ticketing.core.TicketNotFoundException;
import com.ticketing.security.TenantAccessException;
import jakarta.validation.ConstraintViolation;
import java.util.Collection;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;

/**
 * The single mapping from domain and security exceptions to a status and a message that is safe to show
 * the caller. Both inbound adapters use it: REST renders it as an RFC 7807 problem
 * ({@link ApiExceptionHandler}), MCP as a tool error ({@link TicketMcpTools}). The two channels therefore
 * always report the same outcome with the same words.
 */
final class ApiErrors {

    record ApiError(HttpStatus status, String detail) {
    }

    private ApiErrors() {
    }

    /** Empty for exceptions that are not expected business outcomes (they stay 500 / internal errors). */
    static Optional<ApiError> classify(Throwable e) {
        if (e instanceof TicketNotFoundException) {
            return Optional.of(new ApiError(HttpStatus.NOT_FOUND, e.getMessage()));
        }
        if (e instanceof TicketConflictException) {
            return Optional.of(new ApiError(HttpStatus.CONFLICT, e.getMessage()));
        }
        if (e instanceof AccessDeniedException) {
            // Framework text; do not echo it.
            return Optional.of(new ApiError(HttpStatus.FORBIDDEN, "Your role in this tenant does not allow this operation"));
        }
        if (e instanceof TicketForbiddenException || e instanceof TenantAccessException) {
            return Optional.of(new ApiError(HttpStatus.FORBIDDEN, e.getMessage()));
        }
        return Optional.empty();
    }

    /** "field message; field message", the same format REST uses for invalid request bodies. */
    static String describe(Collection<? extends ConstraintViolation<?>> violations) {
        return violations.stream()
                .map(v -> v.getPropertyPath() + " " + v.getMessage())
                .sorted()
                .collect(Collectors.joining("; "));
    }
}
