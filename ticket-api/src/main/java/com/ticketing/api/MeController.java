package com.ticketing.api;

import com.ticketing.security.Role;
import com.ticketing.security.TenantResolver;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Tells the UI who the caller is and which tenants/roles the token proves. */
@RestController
class MeController {

    public record Me(String email, Map<String, Set<Role>> tenants) {
    }

    @GetMapping("/api/me")
    Me me(@AuthenticationPrincipal Jwt jwt) {
        String email = jwt.getClaimAsString("email");
        return new Me(email == null ? null : email.toLowerCase(Locale.ROOT),
                TenantResolver.memberships(jwt.getClaimAsStringList("groups")));
    }
}
