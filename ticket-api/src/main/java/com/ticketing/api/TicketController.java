package com.ticketing.api;

import com.ticketing.core.CommentRequest;
import com.ticketing.core.CreateTicketRequest;
import com.ticketing.core.TicketService;
import com.ticketing.core.TicketView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Applicant API. Approvers get 403 here: they cannot raise or see tickets as applicants. */
@RestController
@RequestMapping("/api/tickets")
@PreAuthorize("hasRole('APPLICANT')")
class TicketController {

    private final TicketService tickets;

    TicketController(TicketService tickets) {
        this.tickets = tickets;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    TicketView create(@Valid @RequestBody CreateTicketRequest request) {
        return tickets.create(request);
    }

    @GetMapping
    List<TicketView> mine(@RequestParam(defaultValue = "0") @Min(0) int page,
                          @RequestParam(defaultValue = "50") @Min(1) @Max(TicketService.MAX_PAGE_SIZE) int size) {
        return tickets.myTickets(page, size);
    }

    @GetMapping("/{id}")
    TicketView one(@PathVariable UUID id) {
        return tickets.myTicket(id);
    }

    @PostMapping("/{id}/respond")
    TicketView respond(@PathVariable UUID id, @Valid @RequestBody CommentRequest request) {
        return tickets.respond(id, request);
    }
}
