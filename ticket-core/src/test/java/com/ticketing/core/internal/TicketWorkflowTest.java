package com.ticketing.core.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ticketing.core.Decision;
import com.ticketing.core.TicketConflictException;
import com.ticketing.core.TicketForbiddenException;
import com.ticketing.core.TicketStatus;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The lock / approval rules of the use case, tested without any database. */
class TicketWorkflowTest {

    private static final Duration NEVER = Duration.ZERO;

    private TicketWorkflow open() {
        return new TicketWorkflow(UUID.randomUUID(), "acme", "alice@acme.test");
    }

    @Test
    void claimedTicketIsLockedToThatApprover() {
        TicketWorkflow t = open();
        t.claim("bob@acme.test", NEVER);
        assertThat(t.getStatus()).isEqualTo(TicketStatus.LOCKED);
        assertThat(t.getLockedBy()).isEqualTo("bob@acme.test");
    }

    @Test
    void secondApproverCannotClaimOrDecideALockedTicket() {
        TicketWorkflow t = open();
        t.claim("bob@acme.test", NEVER);
        assertThatThrownBy(() -> t.claim("carol@acme.test", NEVER)).isInstanceOf(TicketConflictException.class);
        assertThatThrownBy(() -> t.decide("carol@acme.test", Decision.APPROVE))
                .isInstanceOf(TicketConflictException.class);
    }

    @Test
    void anotherApproverCanUnlockThenClaimAndApprove() {
        TicketWorkflow t = open();
        t.claim("bob@acme.test", NEVER);
        assertThat(t.unlock("carol@acme.test")).isEqualTo("bob@acme.test");
        assertThat(t.getStatus()).isEqualTo(TicketStatus.OPEN);
        t.claim("carol@acme.test", NEVER);
        t.decide("carol@acme.test", Decision.APPROVE);
        assertThat(t.getStatus()).isEqualTo(TicketStatus.APPROVED);
        assertThat(t.getLockedBy()).isNull();
    }

    @Test
    void decisionRequiresALock() {
        assertThatThrownBy(() -> open().decide("bob@acme.test", Decision.REJECT))
                .isInstanceOf(TicketConflictException.class);
    }

    @Test
    void requestInfoThenApplicantResponseReturnsToQueue() {
        TicketWorkflow t = open();
        t.claim("bob@acme.test", NEVER);
        t.decide("bob@acme.test", Decision.REQUEST_INFO);
        assertThat(t.getStatus()).isEqualTo(TicketStatus.MORE_INFO);
        t.respond();
        assertThat(t.getStatus()).isEqualTo(TicketStatus.OPEN);
    }

    @Test
    void finalDecisionsAreFinal() {
        TicketWorkflow t = open();
        t.claim("bob@acme.test", NEVER);
        t.decide("bob@acme.test", Decision.REJECT);
        assertThatThrownBy(() -> t.claim("carol@acme.test", NEVER)).isInstanceOf(TicketConflictException.class);
        assertThatThrownBy(t::respond).isInstanceOf(TicketConflictException.class);
    }

    @Test
    void ownerCannotActAsApproverOnTheirOwnTicket() {
        TicketWorkflow t = open();
        assertThatThrownBy(() -> t.claim("Alice@Acme.test", NEVER)).isInstanceOf(TicketForbiddenException.class);
        t.claim("bob@acme.test", NEVER);
        assertThatThrownBy(() -> t.unlock("alice@acme.test")).isInstanceOf(TicketForbiddenException.class);
        assertThatThrownBy(() -> t.decide("alice@acme.test", Decision.APPROVE))
                .isInstanceOf(TicketForbiddenException.class);
        assertThat(t.getLockedBy()).isEqualTo("bob@acme.test");
    }

    @Test
    void locksNeverExpireWhenNoTimeoutIsConfigured() throws InterruptedException {
        TicketWorkflow t = open();
        t.claim("bob@acme.test", NEVER);
        Thread.sleep(5);
        assertThatThrownBy(() -> t.claim("carol@acme.test", NEVER)).isInstanceOf(TicketConflictException.class);
    }

    @Test
    void staleLockCanBeTakenOver() throws InterruptedException {
        TicketWorkflow t = open();
        Duration timeout = Duration.ofMillis(1);
        t.claim("bob@acme.test", timeout);
        Thread.sleep(10);
        assertThat(t.claim("carol@acme.test", timeout)).isEqualTo("bob@acme.test");
        assertThat(t.getLockedBy()).isEqualTo("carol@acme.test");
        assertThatThrownBy(() -> t.decide("bob@acme.test", Decision.APPROVE))
                .isInstanceOf(TicketConflictException.class);
    }

    @Test
    void freshLockCannotBeTakenOver() {
        TicketWorkflow t = open();
        t.claim("bob@acme.test", Duration.ofMinutes(30));
        assertThatThrownBy(() -> t.claim("carol@acme.test", Duration.ofMinutes(30)))
                .isInstanceOf(TicketConflictException.class);
    }
}
