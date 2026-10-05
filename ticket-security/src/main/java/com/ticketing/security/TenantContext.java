package com.ticketing.security;

import java.util.Set;

/**
 * The authenticated caller for the current request: who they are, which tenant they are
 * acting in, and the roles they hold in THAT tenant. Built only from the validated JWT.
 */
public record TenantContext(String email, String tenantId, Set<Role> roles) {

    public TenantContext {
        roles = Set.copyOf(roles);
    }

    public boolean hasRole(Role role) {
        return roles.contains(role);
    }
}
