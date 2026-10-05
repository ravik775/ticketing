package com.ticketing.core;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Read model: workflow state (Postgres) merged with ticket details (MongoDB). */
public record TicketView(
        UUID id,
        String tenantId,
        String title,
        String mobile,
        String description,
        String createdBy,
        TicketStatus status,
        String lockedBy,
        Instant lockedAt,
        Instant createdAt,
        List<TicketEventView> events) {
}
