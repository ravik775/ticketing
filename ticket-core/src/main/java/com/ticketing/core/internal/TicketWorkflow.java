package com.ticketing.core.internal;

import com.ticketing.core.Decision;
import com.ticketing.core.TicketConflictException;
import com.ticketing.core.TicketForbiddenException;
import com.ticketing.core.TicketStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.TenantId;

/**
 * Structured workflow state of a ticket (PostgreSQL). Details/comments live in MongoDB.
 * {@link TenantId} makes Hibernate add "tenant_id = :current" to every query on this entity;
 * Postgres Row-Level Security enforces the same rule again at the database.
 * All state transitions are encoded here so the lock rules exist in exactly one place.
 */
@Entity
@Table(name = "ticket_workflow")
public class TicketWorkflow {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private String tenantId;

    @Column(name = "owner_email", nullable = false, updatable = false)
    private String ownerEmail;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TicketStatus status;

    @Column(name = "locked_by")
    private String lockedBy;

    @Column(name = "locked_at")
    private Instant lockedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Optimistic lock: two approvers racing to claim the same ticket cannot both win. */
    @Version
    private long version;

    protected TicketWorkflow() {
    }

    public TicketWorkflow(UUID id, String tenantId, String ownerEmail) {
        this.id = id;
        this.tenantId = tenantId;
        this.ownerEmail = ownerEmail;
        this.status = TicketStatus.OPEN;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    /**
     * Approver picks the ticket up; nobody else may act on it until it is decided or unlocked.
     * If {@code lockTimeout} is positive, a lock older than that is stale and may be taken over.
     *
     * @return the previous holder of a stale lock that was taken over, or null
     */
    public String claim(String approver, Duration lockTimeout) {
        requireNotOwner(approver);
        String takenOver = null;
        if (status == TicketStatus.LOCKED) {
            if (!isStale(lockTimeout) || approver.equals(lockedBy)) {
                throw new TicketConflictException("Ticket is locked by " + lockedBy);
            }
            takenOver = lockedBy;
        } else if (status != TicketStatus.OPEN) {
            throw new TicketConflictException("Ticket cannot be picked up in status " + status);
        }
        status = TicketStatus.LOCKED;
        lockedBy = approver;
        lockedAt = Instant.now();
        touch();
        return takenOver;
    }

    /** Any approver of the tenant may release a lock held by someone else. Returns the previous holder. */
    public String unlock(String approver) {
        requireNotOwner(approver);
        if (status != TicketStatus.LOCKED) {
            throw new TicketConflictException("Ticket is not locked");
        }
        String previous = lockedBy;
        status = TicketStatus.OPEN;
        clearLock();
        touch();
        return previous;
    }

    /** Only the current lock holder may decide. Deciding releases the lock. */
    public void decide(String approver, Decision decision) {
        requireNotOwner(approver);
        if (status != TicketStatus.LOCKED) {
            throw new TicketConflictException("Ticket must be picked up (locked) before a decision");
        }
        if (!approver.equals(lockedBy)) {
            throw new TicketConflictException("Ticket is locked by " + lockedBy + "; unlock it first");
        }
        status = switch (decision) {
            case APPROVE -> TicketStatus.APPROVED;
            case REJECT -> TicketStatus.REJECTED;
            case REQUEST_INFO -> TicketStatus.MORE_INFO;
        };
        clearLock();
        touch();
    }

    /** Applicant answers a "more details" request; the ticket goes back to the approvers' queue. */
    public void respond() {
        if (status != TicketStatus.MORE_INFO) {
            throw new TicketConflictException("No additional information was requested");
        }
        status = TicketStatus.OPEN;
        touch();
    }

    /**
     * Separation of duties: approval rights come from the per-tenant APPROVER role, but a user who
     * holds both roles must never act on a ticket they raised themselves.
     */
    private void requireNotOwner(String approver) {
        if (approver.equalsIgnoreCase(ownerEmail)) {
            throw new TicketForbiddenException("Approvers cannot act on tickets they raised themselves");
        }
    }

    private boolean isStale(Duration lockTimeout) {
        return lockTimeout != null && lockTimeout.isPositive() && lockedAt != null
                && lockedAt.plus(lockTimeout).isBefore(Instant.now());
    }

    private void clearLock() {
        lockedBy = null;
        lockedAt = null;
    }

    private void touch() {
        updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public String getTenantId() {
        return tenantId;
    }

    public String getOwnerEmail() {
        return ownerEmail;
    }

    public TicketStatus getStatus() {
        return status;
    }

    public String getLockedBy() {
        return lockedBy;
    }

    public Instant getLockedAt() {
        return lockedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
