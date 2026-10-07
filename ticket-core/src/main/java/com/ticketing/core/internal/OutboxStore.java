package com.ticketing.core.internal;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * PostgreSQL transactional outbox. {@link #append} runs inside the caller's workflow transaction
 * (tenant already bound for Row-Level Security). The maintenance methods work across tenants and opt in
 * with the transaction-local flag {@code app.outbox_relay} (see V4__outbox.sql).
 */
@Component
public class OutboxStore {

    /** One history event waiting to be (or already) projected into MongoDB. {@code channel}: REST or MCP. */
    public record OutboxEvent(UUID id, String tenantId, UUID ticketId, long ticketVersion, String type,
                              String actor, String channel, String comment, String title, String mobile,
                              String description, Instant occurredAt, int attempts) {
    }

    /** Health of the outbox for metrics and alerts. */
    public record Backlog(long pending, long dead, Instant oldestPending) {
    }

    private static final RowMapper<OutboxEvent> ROW = (rs, n) -> new OutboxEvent(
            rs.getObject("id", UUID.class), rs.getString("tenant_id"), rs.getObject("ticket_id", UUID.class),
            rs.getLong("ticket_version"), rs.getString("event_type"), rs.getString("actor"), rs.getString("channel"), rs.getString("comment"),
            rs.getString("title"), rs.getString("mobile"), rs.getString("description"),
            rs.getTimestamp("occurred_at").toInstant(), rs.getInt("attempts"));

    private final JdbcTemplate jdbc;

    OutboxStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Must be called inside the workflow transaction (MANDATORY): the event commits with the state change or not at all. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void append(OutboxEvent e) {
        jdbc.update("""
                insert into ticket_outbox (id, tenant_id, ticket_id, ticket_version, event_type, actor, channel, comment,
                                           title, mobile, description, occurred_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                e.id(), e.tenantId(), e.ticketId(), e.ticketVersion(), e.type(), e.actor(), e.channel(), e.comment(),
                e.title(), e.mobile(), e.description(), Timestamp.from(e.occurredAt()));
    }

    private void relayMode() {
        jdbc.queryForObject("select set_config('app.outbox_relay', 'on', true)", String.class);
    }

    /** Oldest-first pending events that have not exhausted their retries. */
    @Transactional(readOnly = true)
    public List<OutboxEvent> pending(int limit, int maxAttempts) {
        relayMode();
        return jdbc.query("""
                select * from ticket_outbox
                where published_at is null and attempts < ?
                order by occurred_at, ticket_version
                limit ?""", ROW, maxAttempts, limit);
    }

    /** Marks an event as projected. Idempotent. Details are kept until {@link #purgePublished} (rebuild window). */
    @Transactional
    public void markPublished(UUID id) {
        relayMode();
        jdbc.update("update ticket_outbox set published_at = now(), last_error = null where id = ? and published_at is null", id);
    }

    /**
     * Retention: published events older than the cutoff are deleted (with the personal data they carry).
     * Until then the MongoDB projection can be rebuilt from the outbox; afterwards from backups.
     */
    @Transactional
    public int purgePublished(Instant olderThan) {
        relayMode();
        return jdbc.update("delete from ticket_outbox where published_at is not null and published_at < ?",
                Timestamp.from(olderThan));
    }

    @Transactional
    public void markFailed(UUID id, String error) {
        relayMode();
        jdbc.update("update ticket_outbox set attempts = attempts + 1, last_error = left(?, 1000) where id = ?",
                error, id);
    }

    @Transactional(readOnly = true)
    public Backlog backlog(int maxAttempts) {
        relayMode();
        return jdbc.queryForObject("""
                select count(*) filter (where attempts < ?)  as pending,
                       count(*) filter (where attempts >= ?) as dead,
                       min(occurred_at)                      as oldest
                from ticket_outbox where published_at is null""",
                (rs, n) -> new Backlog(rs.getLong("pending"), rs.getLong("dead"),
                        rs.getTimestamp("oldest") == null ? null : rs.getTimestamp("oldest").toInstant()),
                maxAttempts, maxAttempts);
    }
}
