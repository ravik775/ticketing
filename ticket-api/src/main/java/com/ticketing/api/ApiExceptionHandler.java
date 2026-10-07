package com.ticketing.api;

import com.ticketing.core.TicketConflictException;
import com.ticketing.core.TicketForbiddenException;
import com.ticketing.core.TicketNotFoundException;
import com.ticketing.security.TenantAccessException;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** REST rendering of {@link ApiErrors}: RFC 7807 problem details. */
@RestControllerAdvice
class ApiExceptionHandler {

    @ExceptionHandler({TicketNotFoundException.class, TicketConflictException.class, TicketForbiddenException.class,
            TenantAccessException.class, AccessDeniedException.class})
    ProblemDetail known(RuntimeException e) {
        ApiErrors.ApiError error = ApiErrors.classify(e).orElseThrow(() -> e);
        return ProblemDetail.forStatusAndDetail(error.status(), error.detail());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail invalid(MethodArgumentNotValidException e) {
        String detail = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + " " + f.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
    }
}
