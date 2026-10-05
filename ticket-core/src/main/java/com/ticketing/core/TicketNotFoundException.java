package com.ticketing.core;

/** Also used when a ticket exists but belongs to someone else, so existence is never leaked (HTTP 404). */
public class TicketNotFoundException extends RuntimeException {
    public TicketNotFoundException(String message) {
        super(message);
    }
}
