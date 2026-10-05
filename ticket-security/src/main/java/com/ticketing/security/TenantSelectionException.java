package com.ticketing.security;

/** The caller belongs to several tenants and did not say which one to use (HTTP 400). */
public class TenantSelectionException extends RuntimeException {
    public TenantSelectionException(String message) {
        super(message);
    }
}
