package com.ticketing.core;

public enum TicketStatus {
    /** Waiting for an approver. */
    OPEN,
    /** Picked up (locked) by one approver. */
    LOCKED,
    APPROVED,
    REJECTED,
    /** Approver asked the applicant for more details. */
    MORE_INFO
}
