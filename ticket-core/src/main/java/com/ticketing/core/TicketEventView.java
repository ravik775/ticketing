package com.ticketing.core;

import java.time.Instant;

/**
 * One entry of a ticket's history: what happened, who did it ({@code actor}), through which path
 * ({@code channel}: "REST" or "MCP"; null for events recorded before channels existed), and when.
 */
public record TicketEventView(String type, String actor, String channel, String comment, Instant at) {
}
