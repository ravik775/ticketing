package com.ticketing.security;

/** The caller is authenticated but not allowed to act in the requested tenant (HTTP 403). */
public class TenantAccessException extends RuntimeException {
    public TenantAccessException(String message) {
        super(message);
    }
}
