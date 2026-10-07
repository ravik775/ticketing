package com.ticketing.core.internal;

import com.ticketing.core.TicketForbiddenException;
import com.ticketing.core.TicketNotFoundException;
import com.ticketing.core.TicketStatus;
import com.ticketing.core.internal.OutboxStore.OutboxEvent;
import com.ticketing.security.AccessChannel;
import com.ticketing.security.TenantContext;
import com.ticketing.security.TenantContextHolder;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * PostgreSQL access for ticket workflow state. Every transaction first binds the caller's
 * tenant to the database session ({@code app.tenant_id}) so Row-Level Security applies.
 * Every state change writes its history event to the outbox in the SAME transaction.
 */
@Component
public class WorkflowStore {

    /** Details of a new ticket, carried by the CREATED outbox event. */
    public record NewTicket(String title, String mobile, String description) {
    }

    /** What happened, decided after the state transition ran (e.g. the previous lock holder). */
    public record EventSpec(String type, String comment) {
    }

    /** The updated row together with the outbox event(s) committed with it, in the order they happened. */
    public record Change(TicketWorkflow workflow, List<OutboxEvent> events) {

        /** The last event (for single-step changes: the only one). */
        public OutboxEvent event() {
            return events.get(events.size() - 1);
        }
    }

    private final WorkflowRepository repository;
    private final OutboxStore outbox;
    private final JdbcTemplate jdbc;

    WorkflowStore(WorkflowRepository repository, OutboxStore outbox, JdbcTemplate jdbc) {
        this.repository = repository;
        this.outbox = outbox;
        this.jdbc = jdbc;
    }

    /** Transaction-local (is_local = true): cleared automatically on commit/rollback. */
    private TenantContext bindTenant() {
        TenantContext ctx = TenantContextHolder.require();
        jdbc.queryForObject("select set_config('app.tenant_id', ?, true)", String.class, ctx.tenantId());
        return ctx;
    }

    @Transactional
    public Change insert(UUID id, NewTicket details) {
        TenantContext ctx = bindTenant();
        // Checked explicitly (the FK alone would also fire for unrelated integrity errors, and ignores 'active').
        Boolean registered = jdbc.queryForObject(
                "select exists(select 1 from tenant where id = ? and active)", Boolean.class, ctx.tenantId());
        if (!Boolean.TRUE.equals(registered)) {
            throw new TicketForbiddenException("Tenant '" + ctx.tenantId() + "' is not registered or not active");
        }
        TicketWorkflow workflow = repository.saveAndFlush(new TicketWorkflow(id, ctx.tenantId(), ctx.email()));
        OutboxEvent event = new OutboxEvent(UUID.randomUUID(), ctx.tenantId(), id, workflow.getVersion(), "CREATED",
                ctx.email(), AccessChannel.current().name(), null, details.title(), details.mobile(),
                details.description(), workflow.getCreatedAt(), 0);
        outbox.append(event);
        return new Change(workflow, List.of(event));
    }

    @Transactional(readOnly = true)
    public TicketWorkflow get(UUID id) {
        bindTenant();
        return repository.findById(id).orElseThrow(() -> new TicketNotFoundException("Ticket not found"));
    }

    @Transactional(readOnly = true)
    public List<TicketWorkflow> listOwnedByCaller(int page, int size) {
        TenantContext ctx = bindTenant();
        return repository.findByOwnerEmailOrderByCreatedAtDescIdAsc(ctx.email(), PageRequest.of(page, size));
    }

    @Transactional(readOnly = true)
    public List<TicketWorkflow> listTenant(TicketStatus statusOrNull, int page, int size) {
        bindTenant();
        PageRequest request = PageRequest.of(page, size);
        return statusOrNull == null
                ? repository.findAllByOrderByCreatedAtDescIdAsc(request)
                : repository.findByStatusOrderByCreatedAtDescIdAsc(statusOrNull, request);
    }

    /** A single-step state transition; see {@link #updateAll}. */
    @Transactional
    public Change update(UUID id, Function<TicketWorkflow, EventSpec> transition) {
        return updateAll(id, w -> List.of(transition.apply(w)));
    }

    /**
     * Load, apply one or more state transitions, flush (so @Version conflicts surface here), then write one
     * history event per step to the outbox, all in ONE transaction: either every step happens or none.
     * Steps get strictly increasing timestamps (1 microsecond apart, PostgreSQL's precision) so the relay
     * and the history show them in the order they ran.
     */
    @Transactional
    public Change updateAll(UUID id, Function<TicketWorkflow, List<EventSpec>> transitions) {
        TenantContext ctx = bindTenant();
        TicketWorkflow workflow = repository.findById(id)
                .orElseThrow(() -> new TicketNotFoundException("Ticket not found"));
        List<EventSpec> specs = transitions.apply(workflow);
        TicketWorkflow saved = repository.saveAndFlush(workflow);
        Instant at = Instant.now().truncatedTo(ChronoUnit.MICROS);
        String channel = AccessChannel.current().name();
        List<OutboxEvent> events = new ArrayList<>(specs.size());
        for (int i = 0; i < specs.size(); i++) {
            EventSpec spec = specs.get(i);
            OutboxEvent event = new OutboxEvent(UUID.randomUUID(), ctx.tenantId(), id, saved.getVersion(), spec.type(),
                    ctx.email(), channel, spec.comment(), null, null, null, at.plus(i, ChronoUnit.MICROS), 0);
            outbox.append(event);
            events.add(event);
        }
        return new Change(saved, List.copyOf(events));
    }
}
