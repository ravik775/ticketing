package com.ticketing.core.internal;

import com.ticketing.core.TicketStatus;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/** Tenant filtering is applied by Hibernate (@TenantId) and Postgres RLS; no method takes a tenant. */
interface WorkflowRepository extends JpaRepository<TicketWorkflow, UUID> {

    List<TicketWorkflow> findByOwnerEmailOrderByCreatedAtDescIdAsc(String ownerEmail, Pageable page);

    List<TicketWorkflow> findAllByOrderByCreatedAtDescIdAsc(Pageable page);

    List<TicketWorkflow> findByStatusOrderByCreatedAtDescIdAsc(TicketStatus status, Pageable page);
}
