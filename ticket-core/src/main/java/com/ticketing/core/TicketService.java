package com.ticketing.core;

import com.ticketing.core.internal.DocumentStore;
import com.ticketing.core.internal.TicketDocument;
import com.ticketing.core.internal.TicketDocument.EventDoc;
import com.ticketing.core.internal.TicketWorkflow;
import com.ticketing.core.internal.WorkflowStore;
import com.ticketing.security.Role;
import com.ticketing.security.TenantContext;
import com.ticketing.security.TenantContextHolder;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;

/**
 * The ONLY entry point to ticket data (the rest of this module is not exported).
 *
 * <p>Tenant, email and roles are read from the {@link TenantContextHolder}; no method accepts them
 * as arguments, so a caller cannot ask for another tenant's data. Roles are re-checked here even
 * though the web layer also checks them (defence in depth).
 *
 * <p>Storage split: workflow/lock state in PostgreSQL ({@link WorkflowStore}), ticket details and
 * comments in MongoDB ({@link DocumentStore}). They are separate databases, so there is no single
 * transaction: create writes Mongo first and compensates if Postgres fails; later events are
 * appended after the Postgres change commits (a failure there is logged, not rolled back).
 */
@Service
public class TicketService {

    private static final Logger log = LoggerFactory.getLogger(TicketService.class);

    /** Largest page a list endpoint returns; also bounds the MongoDB $in query built from it. */
    public static final int MAX_PAGE_SIZE = 200;

    private final WorkflowStore workflows;
    private final DocumentStore documents;
    private final Duration lockTimeout;

    /**
     * @param lockTimeout after this long a lock is stale and another approver may take it over;
     *                    zero (the default) keeps locks until they are decided or explicitly unlocked
     */
    public TicketService(WorkflowStore workflows, DocumentStore documents,
                         @Value("${ticketing.lock-timeout:PT0S}") Duration lockTimeout) {
        this.workflows = workflows;
        this.documents = documents;
        this.lockTimeout = lockTimeout;
    }

    // ---------------------------------------------------------------- applicant

    public TicketView create(CreateTicketRequest request) {
        TenantContext ctx = require(Role.APPLICANT);
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        documents.insert(new TicketDocument(id.toString(), ctx.tenantId(), request.title(), request.mobile(),
                request.description(), ctx.email(), now, List.of(new EventDoc("CREATED", ctx.email(), null, now))));
        TicketWorkflow workflow;
        try {
            workflow = workflows.insert(id);
        } catch (RuntimeException e) {
            try {
                documents.delete(id);
            } catch (RuntimeException compensation) {
                // Keep the original failure as the cause the caller sees.
                e.addSuppressed(compensation);
                log.warn("Ticket {} could not be removed from MongoDB after the Postgres insert failed", id, compensation);
            }
            throw e;
        }
        return viewOf(List.of(workflow)).get(0);
    }

    public List<TicketView> myTickets(int page, int size) {
        require(Role.APPLICANT);
        return viewOf(workflows.listOwnedByCaller(checkPage(page), checkSize(size)));
    }

    public TicketView myTicket(UUID id) {
        TenantContext ctx = require(Role.APPLICANT);
        return viewOf(List.of(ownedBy(ctx, workflows.get(id)))).get(0);
    }

    /** Applicant answers an approver's "more details" request. */
    public TicketView respond(UUID id, CommentRequest request) {
        TenantContext ctx = require(Role.APPLICANT);
        ownedBy(ctx, workflows.get(id));
        TicketWorkflow updated = mutate(id, TicketWorkflow::respond);
        record(id, "RESPONDED", ctx.email(), request.comment());
        return viewOf(List.of(updated)).get(0);
    }

    // ---------------------------------------------------------------- approver

    public List<TicketView> tenantTickets(TicketStatus statusOrNull, int page, int size) {
        require(Role.APPROVER);
        return viewOf(workflows.listTenant(statusOrNull, checkPage(page), checkSize(size)));
    }

    public TicketView tenantTicket(UUID id) {
        require(Role.APPROVER);
        return viewOf(List.of(workflows.get(id))).get(0);
    }

    /** Pick up (lock) a ticket. Approvers can never pick up a ticket they raised themselves (403). */
    public TicketView claim(UUID id) {
        TenantContext ctx = require(Role.APPROVER);
        String[] takenOver = new String[1];
        TicketWorkflow updated = mutate(id, w -> takenOver[0] = w.claim(ctx.email(), lockTimeout));
        record(id, "CLAIMED", ctx.email(),
                takenOver[0] == null ? null : "Stale lock held by " + takenOver[0] + " taken over");
        return viewOf(List.of(updated)).get(0);
    }

    /** Release a lock, including one held by another approver of the same tenant. */
    public TicketView unlock(UUID id) {
        TenantContext ctx = require(Role.APPROVER);
        String[] previous = new String[1];
        TicketWorkflow updated = mutate(id, w -> previous[0] = w.unlock(ctx.email()));
        record(id, "UNLOCKED", ctx.email(), "Lock held by " + previous[0] + " released");
        return viewOf(List.of(updated)).get(0);
    }

    public TicketView decide(UUID id, DecisionRequest request) {
        TenantContext ctx = require(Role.APPROVER);
        TicketWorkflow updated = mutate(id, w -> w.decide(ctx.email(), request.decision()));
        record(id, request.decision().name(), ctx.email(), request.comment());
        return viewOf(List.of(updated)).get(0);
    }

    // ---------------------------------------------------------------- helpers

    private static TenantContext require(Role role) {
        TenantContext ctx = TenantContextHolder.require();
        if (!ctx.hasRole(role)) {
            throw new TicketForbiddenException("Role " + role + " is required in tenant '" + ctx.tenantId() + "'");
        }
        return ctx;
    }

    private static int checkPage(int page) {
        if (page < 0) {
            throw new IllegalArgumentException("page must be >= 0");
        }
        return page;
    }

    private static int checkSize(int size) {
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("size must be between 1 and " + MAX_PAGE_SIZE);
        }
        return size;
    }

    /** Applicants only ever see their own tickets; anything else looks like it does not exist. */
    private static TicketWorkflow ownedBy(TenantContext ctx, TicketWorkflow workflow) {
        if (!workflow.getOwnerEmail().equalsIgnoreCase(ctx.email())) {
            throw new TicketNotFoundException("Ticket not found");
        }
        return workflow;
    }

    private TicketWorkflow mutate(UUID id, java.util.function.Consumer<TicketWorkflow> change) {
        try {
            return workflows.update(id, change);
        } catch (OptimisticLockingFailureException e) {
            throw new TicketConflictException("Ticket was modified by someone else; reload and retry");
        }
    }

    private void record(UUID id, String type, String actor, String comment) {
        try {
            documents.appendEvent(id, new EventDoc(type, actor, comment, Instant.now()));
        } catch (RuntimeException e) {
            log.warn("Ticket {} changed in Postgres but event '{}' could not be written to MongoDB", id, type, e);
        }
    }

    private List<TicketView> viewOf(List<TicketWorkflow> rows) {
        Map<UUID, TicketDocument> details = documents.findByIds(rows.stream().map(TicketWorkflow::getId).toList());
        return rows.stream().map(w -> toView(w, details.get(w.getId()))).toList();
    }

    private static TicketView toView(TicketWorkflow w, TicketDocument d) {
        List<TicketEventView> events = d == null || d.events() == null ? List.of()
                : d.events().stream().map(e -> new TicketEventView(e.type(), e.actor(), e.comment(), e.at())).toList();
        return new TicketView(w.getId(), w.getTenantId(),
                d == null ? "(details unavailable)" : d.title(),
                d == null ? null : d.mobile(),
                d == null ? null : d.description(),
                w.getOwnerEmail(), w.getStatus(), w.getLockedBy(), w.getLockedAt(), w.getCreatedAt(), events);
    }
}
