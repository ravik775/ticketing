package com.ticketing.security;

import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Turns the JWT {@code groups} claim (Keycloak group paths such as {@code /acme/approver})
 * into tenant memberships, and selects the active tenant for a request.
 *
 * <p>The tenant is never taken on trust: a requested tenant (header) is honoured only if the
 * token proves membership in it.
 */
public final class TenantResolver {

    private TenantResolver() {
    }

    /** Parses {@code /<tenant>/<role>} group paths. Unknown roles and malformed paths are ignored. */
    public static Map<String, Set<Role>> memberships(Collection<String> groups) {
        Map<String, Set<Role>> result = new LinkedHashMap<>();
        if (groups == null) {
            return result;
        }
        for (String group : groups) {
            if (group == null) {
                continue;
            }
            String[] parts = group.split("/");
            // "/acme/approver" -> ["", "acme", "approver"]
            if (parts.length != 3 || !parts[0].isEmpty() || parts[1].isBlank()) {
                continue;
            }
            Role.parse(parts[2]).ifPresent(role ->
                    result.computeIfAbsent(parts[1], t -> EnumSet.noneOf(Role.class)).add(role));
        }
        return result;
    }

    /**
     * @param requestedTenant value of the {@code X-Tenant-ID} header, may be null/blank
     * @throws TenantSelectionException no tenant requested and the user has several (or none)
     * @throws TenantAccessException    the user is not a member of the requested tenant
     */
    public static TenantContext resolve(String email, Collection<String> groups, String requestedTenant) {
        Map<String, Set<Role>> memberships = memberships(groups);
        if (memberships.isEmpty()) {
            throw new TenantAccessException("User is not a member of any tenant");
        }
        String tenant;
        if (requestedTenant == null || requestedTenant.isBlank()) {
            if (memberships.size() > 1) {
                throw new TenantSelectionException(
                        "User belongs to several tenants " + memberships.keySet() + "; send the X-Tenant-ID header");
            }
            tenant = memberships.keySet().iterator().next();
        } else {
            tenant = requestedTenant.trim();
            if (!memberships.containsKey(tenant)) {
                throw new TenantAccessException("User is not a member of tenant '" + tenant + "'");
            }
        }
        return new TenantContext(email, tenant, memberships.get(tenant));
    }
}
