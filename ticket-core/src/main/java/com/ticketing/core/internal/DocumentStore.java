package com.ticketing.core.internal;

import com.mongodb.client.result.UpdateResult;
import com.ticketing.core.internal.OutboxStore.OutboxEvent;
import com.ticketing.security.TenantContextHolder;
import java.time.Duration;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

/**
 * MongoDB access for ticket details. MongoDB has no row-level security, so the tenant is applied
 * here, in the only class that touches the collection, and it is read from the security context
 * rather than passed in by callers.
 *
 * <p>Writes are projections of PostgreSQL outbox events and are idempotent, so the relay can retry
 * them safely. A write that cannot be applied throws: nothing fails silently.
 */
@Component
public class DocumentStore {

    /** Upper bound for any query, so one slow tenant query cannot hold a connection indefinitely. */
    static final Duration QUERY_TIMEOUT = Duration.ofSeconds(5);

    private final MongoTemplate mongo;

    DocumentStore(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    private static String tenant() {
        return TenantContextHolder.require().tenantId();
    }

    private static Query byId(UUID id) {
        return Query.query(Criteria.where("_id").is(id.toString()).and("tenantId").is(tenant()));
    }

    /** Applies one outbox event to the projection. Idempotent; throws if it cannot be applied. */
    public void apply(OutboxEvent e) {
        if (!e.tenantId().equals(tenant())) {
            throw new IllegalStateException("Event tenant does not match the security context");
        }
        if ("CREATED".equals(e.type())) {
            applyCreated(e);
        } else {
            appendEvent(e);
        }
    }

    private void applyCreated(OutboxEvent e) {
        // Upsert with setOnInsert: a retry after success changes nothing.
        Update create = new Update()
                .setOnInsert("tenantId", e.tenantId())
                .setOnInsert("title", e.title())
                .setOnInsert("mobile", e.mobile())
                .setOnInsert("description", e.description())
                .setOnInsert("createdBy", e.actor())
                .setOnInsert("createdAt", e.occurredAt())
                .setOnInsert("events", List.of(eventDoc(e)));
        mongo.upsert(byId(e.ticketId()), create, TicketDocument.class);
    }

    private void appendEvent(OutboxEvent e) {
        Query notYetApplied = byId(e.ticketId());
        notYetApplied.addCriteria(Criteria.where("events.eventId").ne(e.id().toString()));
        UpdateResult result = mongo.updateFirst(notYetApplied, new Update().push("events", eventDoc(e)), TicketDocument.class);
        if (result.getMatchedCount() == 0 && !mongo.exists(byId(e.ticketId()), TicketDocument.class)) {
            // Previously this case was silently ignored and the history entry was lost.
            throw new IllegalStateException("Ticket document " + e.ticketId() + " does not exist (yet); event "
                    + e.type() + " not applied");
        }
        // matched == 0 but the document exists: the event was already applied (idempotent retry).
    }

    private static TicketDocument.EventDoc eventDoc(OutboxEvent e) {
        return new TicketDocument.EventDoc(e.id().toString(), e.type(), e.actor(), e.comment(), e.occurredAt());
    }

    public Map<UUID, TicketDocument> findByIds(Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        Query query = Query.query(Criteria.where("_id").in(ids.stream().map(UUID::toString).toList())
                .and("tenantId").is(tenant()));
        query.maxTime(QUERY_TIMEOUT);
        Map<UUID, TicketDocument> result = new HashMap<>();
        mongo.find(query, TicketDocument.class).forEach(d -> result.put(UUID.fromString(d.id()), d));
        return result;
    }
}
