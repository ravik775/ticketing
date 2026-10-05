package com.ticketing.core.internal;

import com.ticketing.core.TicketForbiddenException;
import com.ticketing.core.TicketNotFoundException;
import com.ticketing.core.TicketStatus;
import com.ticketing.core.internal.OutboxStore.OutboxEvent;
import com.ticketing.security.TenantContext;
import com.ticketing.security.TenantContextHolder;
import java.time.Instant;
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

    /** The updated row together with the outbox event committed with it. */
    public record Change(TicketWorkflow workflow, OutboxEvent event) {
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
                ctx.email(), null, details.title(), details.mobile(), details.description(), workflow.getCreatedAt(), 0);
        outbox.append(event);
        return new Change(workflow, event);
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

    /**
     * Load, apply a state transition, flush (so @Version conflicts surface here), then write the
     * history event to the outbox, all in one transaction.
     */
    @Transactional
    public Change update(UUID id, Function<TicketWorkflow, EventSpec> transition) {
        TenantContext ctx = bindTenant();
        TicketWorkflow workflow = repository.findById(id)
                .orElseThrow(() -> new TicketNotFoundException("Ticket not found"));
        EventSpec spec = transition.apply(workflow);
        TicketWorkflow saved = repository.saveAndFlush(workflow);
        OutboxEvent event = new OutboxEvent(UUID.randomUUID(), ctx.tenantId(), id, saved.getVersion(), spec.type(),
                ctx.email(), spec.comment(), null, null, null, Instant.now(), 0);
        outbox.append(event);
        return new Change(saved, event);
    }
}
