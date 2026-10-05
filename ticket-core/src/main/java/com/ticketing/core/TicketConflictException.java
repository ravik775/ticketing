package com.ticketing.core;

/** Illegal state transition, ticket locked by another approver, or concurrent modification (HTTP 409). */
public class TicketConflictException extends RuntimeException {
    public TicketConflictException(String message) {
        super(message);
    }
}
