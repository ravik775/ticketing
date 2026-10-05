package com.ticketing.core.internal;

import java.time.Instant;
import java.util.List;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/** Ticket details and history (MongoDB). The _id equals the workflow row id in Postgres. */
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

    /** Embedded audit/comment entry. */
    public record EventDoc(String type, String actor, String comment, Instant at) {
    }
}
