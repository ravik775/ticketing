package com.ticketing.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class TenantResolverTest {

    private static final List<String> GROUPS = List.of("/acme/applicant", "/globex/approver", "/globex/applicant", "junk", "/x/unknown");

    @Test
    void parsesMembershipsAndIgnoresJunk() {
        var m = TenantResolver.memberships(GROUPS);
        assertThat(m).containsOnlyKeys("acme", "globex");
        assertThat(m.get("acme")).containsExactly(Role.APPLICANT);
        assertThat(m.get("globex")).containsExactlyInAnyOrder(Role.APPLICANT, Role.APPROVER);
    }

    @Test
    void rolesAreScopedToTheActiveTenant() {
        TenantContext ctx = TenantResolver.resolve("a@x.com", GROUPS, "acme");
        assertThat(ctx.tenantId()).isEqualTo("acme");
        assertThat(ctx.hasRole(Role.APPLICANT)).isTrue();
        assertThat(ctx.hasRole(Role.APPROVER)).isFalse();
    }

    @Test
    void rejectsTenantTheTokenDoesNotProve() {
        assertThatThrownBy(() -> TenantResolver.resolve("a@x.com", GROUPS, "initech"))
                .isInstanceOf(TenantAccessException.class);
    }

    @Test
    void requiresExplicitTenantWhenAmbiguous() {
        assertThatThrownBy(() -> TenantResolver.resolve("a@x.com", GROUPS, null))
                .isInstanceOf(TenantSelectionException.class);
    }

    @Test
    void infersTenantWhenOnlyOne() {
        TenantContext ctx = TenantResolver.resolve("a@x.com", List.of("/acme/approver"), " ");
        assertThat(ctx.tenantId()).isEqualTo("acme");
        assertThat(ctx.roles()).containsExactly(Role.APPROVER);
    }

    @Test
    void noMembershipMeansNoAccess() {
        assertThatThrownBy(() -> TenantResolver.resolve("a@x.com", List.of(), "acme"))
                .isInstanceOf(TenantAccessException.class);
    }

    @Test
    void holderFailsClosedWhenUnset() {
        TenantContextHolder.clear();
        assertThatThrownBy(TenantContextHolder::require).isInstanceOf(TenantAccessException.class);
    }
}
