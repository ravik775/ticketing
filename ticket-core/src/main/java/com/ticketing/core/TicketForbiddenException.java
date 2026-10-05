package com.ticketing.core;

/** Caller lacks the role for this operation in the active tenant (HTTP 403). */
public class TicketForbiddenException extends RuntimeException {
    public TicketForbiddenException(String message) {
        super(message);
    }
}
