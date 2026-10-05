package com.ticketing.security;

import java.util.Optional;

/** Request-scoped holder for the {@link TenantContext}. Set by the web layer, read by the core. */
public final class TenantContextHolder {

    private static final ThreadLocal<TenantContext> HOLDER = new ThreadLocal<>();

    private TenantContextHolder() {
    }

    public static void set(TenantContext context) {
        HOLDER.set(context);
    }

    public static void clear() {
        HOLDER.remove();
    }

    public static Optional<TenantContext> current() {
        return Optional.ofNullable(HOLDER.get());
    }

    /** @throws TenantAccessException if no tenant context is bound (fail closed). */
    public static TenantContext require() {
        TenantContext ctx = HOLDER.get();
        if (ctx == null) {
            throw new TenantAccessException("No tenant context bound to the current request");
        }
        return ctx;
    }
}
