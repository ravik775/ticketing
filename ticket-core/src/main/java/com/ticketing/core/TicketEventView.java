package com.ticketing.core;

import java.time.Instant;

public record TicketEventView(String type, String actor, String comment, Instant at) {
}
