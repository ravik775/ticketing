package com.ticketing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketing.security.TenantAccessException;
import com.ticketing.security.TenantContext;
import com.ticketing.security.TenantContextHolder;
import com.ticketing.security.TenantResolver;
import com.ticketing.security.TenantSelectionException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Runs after the JWT has been validated. Derives the {@link TenantContext} (email, active tenant,
 * roles IN THAT TENANT) from the token's claims and the X-Tenant-ID header, binds it for the
 * request, and replaces the granted authorities with ROLE_APPLICANT / ROLE_APPROVER so that
 * {@code @PreAuthorize("hasRole('APPROVER')")} is evaluated against the active tenant only.
 * The header is a selector, never a source of trust: it must be proven by the token.
 */
class TenantContextFilter extends OncePerRequestFilter {

    static final String TENANT_HEADER = "X-Tenant-ID";

    private final ObjectMapper mapper;

    TenantContextFilter(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /** /api/me lists the caller's tenants, so it must work before a tenant has been chosen. */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return "/api/me".equals(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        try {
            if (authentication instanceof JwtAuthenticationToken jwtAuth) {
                String email = jwtAuth.getToken().getClaimAsString("email");
                if (email == null || email.isBlank()) {
                    Problems.write(mapper, response, HttpStatus.FORBIDDEN, "Token has no email claim");
                    return;
                }
                email = email.toLowerCase(Locale.ROOT);
                List<String> groups = jwtAuth.getToken().getClaimAsStringList("groups");
                TenantContext context;
                try {
                    context = TenantResolver.resolve(email, groups, request.getHeader(TENANT_HEADER));
                } catch (TenantSelectionException e) {
                    Problems.write(mapper, response, HttpStatus.BAD_REQUEST, e.getMessage());
                    return;
                } catch (TenantAccessException e) {
                    Problems.write(mapper, response, HttpStatus.FORBIDDEN, e.getMessage());
                    return;
                }
                TenantContextHolder.set(context);
                SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(
                        jwtAuth.getToken(),
                        context.roles().stream().map(r -> new SimpleGrantedAuthority("ROLE_" + r.name())).toList(),
                        email));
            }
            chain.doFilter(request, response);
        } finally {
            TenantContextHolder.clear();
        }
    }
}
