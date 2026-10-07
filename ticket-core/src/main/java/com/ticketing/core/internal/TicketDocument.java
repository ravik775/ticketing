package com.ticketing.core.internal;

import java.time.Instant;
import java.util.List;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * Ticket details and history (MongoDB). The _id equals the workflow row id in Postgres.
 * This document is a projection of the PostgreSQL outbox (see {@link OutboxStore}).
 */
@Document(collection = "ticket_details")
public record TicketDocument(
        @Id String id,
        String tenantId,
        String title,
        String mobile,
        String description,
        String createdBy,
        Instant createdAt,
        List<EventDoc> events) {

    /**
     * Embedded audit/comment entry. {@code eventId} is the outbox row id: it makes applying an event
     * idempotent (the relay may retry). Events written before the outbox existed have no id.
     * {@code channel} is the path the actor used: REST or MCP (absent on older events).
     */
    public record EventDoc(String eventId, String type, String actor, String channel, String comment, Instant at) {
    }
}
