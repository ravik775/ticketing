package com.ticketing.api;

import com.ticketing.core.DecisionRequest;
import com.ticketing.core.TicketService;
import com.ticketing.core.TicketStatus;
import com.ticketing.core.TicketView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Approver API: tenant-wide view, pick up (lock), unlock, and decide. No ticket creation. */
@RestController
@RequestMapping("/api/approvals/tickets")
@PreAuthorize("hasRole('APPROVER')")
class ApprovalController {

    private final TicketService tickets;

    ApprovalController(TicketService tickets) {
        this.tickets = tickets;
    }

    @GetMapping
    List<TicketView> list(@RequestParam(required = false) TicketStatus status,
                          @RequestParam(defaultValue = "0") @Min(0) int page,
                          @RequestParam(defaultValue = "50") @Min(1) @Max(TicketService.MAX_PAGE_SIZE) int size) {
        return tickets.tenantTickets(status, page, size);
    }

    @GetMapping("/{id}")
    TicketView one(@PathVariable UUID id) {
        return tickets.tenantTicket(id);
    }

    @PostMapping("/{id}/claim")
    TicketView claim(@PathVariable UUID id) {
        return tickets.claim(id);
    }

    @PostMapping("/{id}/unlock")
    TicketView unlock(@PathVariable UUID id) {
        return tickets.unlock(id);
    }

    @PostMapping("/{id}/decision")
    TicketView decide(@PathVariable UUID id, @Valid @RequestBody DecisionRequest request) {
        return tickets.decide(id, request);
    }
}
