package com.ticketing.security;

/**
 * The path through which the caller reached the service. It is decided by the server from the
 * endpoint that received the request (never from a client-supplied value) and recorded with every
 * ticket history event, so the audit trail shows both WHO acted and THROUGH WHICH PATH.
 */
public enum AccessChannel {
    /** The REST API used by the web UI and scripts ({@code /api/...}). */
    REST,
    /** The Model Context Protocol endpoint used by AI agents ({@code /api/mcp}). */
    MCP;

    private static final ThreadLocal<AccessChannel> CURRENT = new ThreadLocal<>();

    /** Bound per request by the web layer, next to the {@link TenantContext}. */
    public static void set(AccessChannel channel) {
        CURRENT.set(channel);
    }

    public static void clear() {
        CURRENT.remove();
    }

    /** {@link #REST} when nothing was bound (e.g. code calling the service directly in tests). */
    public static AccessChannel current() {
        AccessChannel channel = CURRENT.get();
        return channel == null ? REST : channel;
    }
}
