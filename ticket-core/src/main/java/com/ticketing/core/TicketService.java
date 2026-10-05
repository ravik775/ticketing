package com.ticketing.core;

import com.ticketing.core.internal.DocumentStore;
import com.ticketing.core.internal.OutboxPublisher;
import com.ticketing.core.internal.OutboxStore.OutboxEvent;
import com.ticketing.core.internal.TicketDocument;
import com.ticketing.core.internal.TicketWorkflow;
import com.ticketing.core.internal.WorkflowStore;
import com.ticketing.core.internal.WorkflowStore.Change;
import com.ticketing.core.internal.WorkflowStore.EventSpec;
import com.ticketing.core.internal.WorkflowStore.NewTicket;
import com.ticketing.security.Role;
import com.ticketing.security.TenantContext;
import com.ticketing.security.TenantContextHolder;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
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
 * history in MongoDB ({@link DocumentStore}). PostgreSQL is the source of truth: every change commits
 * its history event to a transactional outbox in the same transaction; the event is then projected
 * into MongoDB immediately, and retried by the relay if that fails ({@link OutboxPublisher}). History is
 * therefore never lost; at worst it appears a few seconds late.
 */
@Service
public class TicketService {

    /** Largest page a list endpoint returns; also bounds the MongoDB $in query built from it. */
    public static final int MAX_PAGE_SIZE = 200;

    private final WorkflowStore workflows;
    private final DocumentStore documents;
    private final OutboxPublisher publisher;
    private final TenantUsageMeter usage;
    private final Duration lockTimeout;

    /**
     * @param lockTimeout after this long a lock is stale and another approver may take it over;
     *                    zero (the default) keeps locks until they are decided or explicitly unlocked
     */
    public TicketService(WorkflowStore workflows, DocumentStore documents, OutboxPublisher publisher,
                         TenantUsageMeter usage,
                         @Value("${ticketing.lock-timeout:PT0S}") Duration lockTimeout) {
        this.workflows = workflows;
        this.documents = documents;
        this.publisher = publisher;
        this.usage = usage;
        this.lockTimeout = lockTimeout;
    }

    // ---------------------------------------------------------------- applicant

    public TicketView create(CreateTicketRequest request) {
        TenantContext ctx = require(Role.APPLICANT);
        Change change = workflows.insert(UUID.randomUUID(),
                new NewTicket(request.title(), request.mobile(), request.description()));
        publisher.publishNow(change.event());
        usage.recordTicketCreated(ctx.tenantId());
        TicketWorkflow workflow = change.workflow();
        TicketDocument details = documents.findByIds(List.of(workflow.getId())).get(workflow.getId());
        if (details == null) {
            // MongoDB is unavailable right now: answer from the committed event; the relay will project it.
            OutboxEvent e = change.event();
            details = new TicketDocument(workflow.getId().toString(), ctx.tenantId(), e.title(), e.mobile(),
                    e.description(), ctx.email(), e.occurredAt(),
                    List.of(new TicketDocument.EventDoc(e.id().toString(), e.type(), e.actor(), null, e.occurredAt())));
        }
        return toView(workflow, details);
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
        return mutate(id, w -> {
            w.respond();
            return new EventSpec("RESPONDED", request.comment());
        });
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
        return mutate(id, w -> {
            String takenOver = w.claim(ctx.email(), lockTimeout);
            return new EventSpec("CLAIMED", takenOver == null ? null : "Stale lock held by " + takenOver + " taken over");
        });
    }

    /** Release a lock, including one held by another approver of the same tenant. */
    public TicketView unlock(UUID id) {
        TenantContext ctx = require(Role.APPROVER);
        return mutate(id, w -> new EventSpec("UNLOCKED", "Lock held by " + w.unlock(ctx.email()) + " released"));
    }

    public TicketView decide(UUID id, DecisionRequest request) {
        TenantContext ctx = require(Role.APPROVER);
        return mutate(id, w -> {
            w.decide(ctx.email(), request.decision());
            return new EventSpec(request.decision().name(), request.comment());
        });
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

    /** Runs the transition with its outbox event in one transaction, then projects the event. */
    private TicketView mutate(UUID id, Function<TicketWorkflow, EventSpec> transition) {
        Change change;
        try {
            change = workflows.update(id, transition);
        } catch (OptimisticLockingFailureException e) {
            throw new TicketConflictException("Ticket was modified by someone else; reload and retry");
        }
        publisher.publishNow(change.event());
        return viewOf(List.of(change.workflow())).get(0);
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
