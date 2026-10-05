package com.ticketing.core.internal;

import com.ticketing.core.TicketForbiddenException;
import com.ticketing.core.TicketNotFoundException;
import com.ticketing.core.TicketStatus;
import com.ticketing.security.TenantContext;
import com.ticketing.security.TenantContextHolder;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * PostgreSQL access for ticket workflow state. Every transaction first binds the caller's
 * tenant to the database session ({@code app.tenant_id}) so Row-Level Security applies.
 */
@Component
public class WorkflowStore {

    private final WorkflowRepository repository;
    private final JdbcTemplate jdbc;

    WorkflowStore(WorkflowRepository repository, JdbcTemplate jdbc) {
        this.repository = repository;
        this.jdbc = jdbc;
    }

    /** Transaction-local (is_local = true): cleared automatically on commit/rollback. */
    private TenantContext bindTenant() {
        TenantContext ctx = TenantContextHolder.require();
        jdbc.queryForObject("select set_config('app.tenant_id', ?, true)", String.class, ctx.tenantId());
        return ctx;
    }

    @Transactional
    public TicketWorkflow insert(UUID id) {
        TenantContext ctx = bindTenant();
        // Checked explicitly (the FK alone would also fire for unrelated integrity errors, and ignores 'active').
        Boolean registered = jdbc.queryForObject(
                "select exists(select 1 from tenant where id = ? and active)", Boolean.class, ctx.tenantId());
        if (!Boolean.TRUE.equals(registered)) {
            throw new TicketForbiddenException("Tenant '" + ctx.tenantId() + "' is not registered or not active");
        }
        return repository.saveAndFlush(new TicketWorkflow(id, ctx.tenantId(), ctx.email()));
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

    /** Load, apply a state transition, flush (so @Version conflicts surface here), return. */
    @Transactional
    public TicketWorkflow update(UUID id, Consumer<TicketWorkflow> change) {
        bindTenant();
        TicketWorkflow workflow = repository.findById(id)
                .orElseThrow(() -> new TicketNotFoundException("Ticket not found"));
        change.accept(workflow);
        return repository.saveAndFlush(workflow);
    }
}
