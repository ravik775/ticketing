package com.ticketing.core.internal;

import com.ticketing.security.TenantContextHolder;
import java.util.Collection;
import java.util.HashMap;
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
 */
@Component
public class DocumentStore {

    private final MongoTemplate mongo;

    DocumentStore(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    private static String tenant() {
        return TenantContextHolder.require().tenantId();
    }

    public void insert(TicketDocument document) {
        if (!document.tenantId().equals(tenant())) {
            throw new IllegalStateException("Document tenant does not match the security context");
        }
        mongo.insert(document);
    }

    public Map<UUID, TicketDocument> findByIds(Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        Query query = Query.query(Criteria.where("_id").in(ids.stream().map(UUID::toString).toList())
                .and("tenantId").is(tenant()));
        Map<UUID, TicketDocument> result = new HashMap<>();
        mongo.find(query, TicketDocument.class).forEach(d -> result.put(UUID.fromString(d.id()), d));
        return result;
    }

    public void appendEvent(UUID id, TicketDocument.EventDoc event) {
        Query query = Query.query(Criteria.where("_id").is(id.toString()).and("tenantId").is(tenant()));
        mongo.updateFirst(query, new Update().push("events", event), TicketDocument.class);
    }

    /** Compensation when the Postgres insert fails after the document was written. */
    public void delete(UUID id) {
        Query query = Query.query(Criteria.where("_id").is(id.toString()).and("tenantId").is(tenant()));
        mongo.remove(query, TicketDocument.class);
    }
}
