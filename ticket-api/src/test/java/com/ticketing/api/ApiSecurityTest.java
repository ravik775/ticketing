package com.ticketing.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ticketing.core.TenantPolicy;
import com.ticketing.core.TenantPolicyService;
import com.ticketing.core.TenantUsageMeter;
import com.ticketing.core.TicketForbiddenException;
import com.ticketing.core.TicketService;
import com.ticketing.core.TicketStatus;
import com.ticketing.core.TicketView;
import com.ticketing.security.TenantContext;
import com.ticketing.security.TenantContextHolder;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** Role, tenant and identity rules of the REST layer. TicketService is mocked: no databases needed. */
@WebMvcTest(controllers = {TicketController.class, ApprovalController.class, MeController.class})
@Import({SecurityConfig.class, ApiExceptionHandler.class})
@ActiveProfiles("test")
class ApiSecurityTest {

    private static final String BODY = """
            {"title":"VPN broken","mobile":"+919876543210","description":"Cannot connect","email":"evil@x.com","tenantId":"globex"}""";

    @Autowired
    MockMvc mvc;
    @MockitoBean
    TicketService service;
    @MockitoBean
    JwtDecoder jwtDecoder;
    @MockitoBean
    TenantPolicyService tenantPolicies;
    @MockitoBean
    TenantUsageMeter tenantUsage;

    @BeforeEach
    void generousQuotas() {
        when(tenantPolicies.policyFor(anyString()))
                .thenAnswer(inv -> new TenantPolicy(inv.getArgument(0), "standard", 1000, 20));
    }

    private static JwtRequestPostProcessor user(String email, String... groups) {
        return jwt().jwt(j -> j.claim("email", email).claim("groups", List.of(groups)));
    }

    private static TicketView view() {
        return new TicketView(UUID.randomUUID(), "acme", "VPN broken", "+919876543210", "Cannot connect",
                "alice@acme.test", TicketStatus.OPEN, null, null, Instant.now(), List.of());
    }

    @Test
    void anonymousIsRejected() throws Exception {
        mvc.perform(get("/api/tickets")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/mcp").contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @Test
    void tenantAndEmailComeFromTheTokenNotTheBody() throws Exception {
        AtomicReference<TenantContext> seen = new AtomicReference<>();
        when(service.create(any())).thenAnswer(inv -> {
            seen.set(TenantContextHolder.require());
            return view();
        });

        mvc.perform(post("/api/tickets").with(user("Alice@Acme.test", "/acme/applicant"))
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isCreated());

        assertThat(seen.get().tenantId()).isEqualTo("acme");          // body said "globex"
        assertThat(seen.get().email()).isEqualTo("alice@acme.test");  // body said "evil@x.com"
    }

    @Test
    void approverCannotRaiseTickets() throws Exception {
        mvc.perform(post("/api/tickets").with(user("bob@acme.test", "/acme/approver"))
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isForbidden());
        verify(service, never()).create(any());
    }

    @Test
    void applicantCannotUseApproverEndpoints() throws Exception {
        mvc.perform(get("/api/approvals/tickets").with(user("alice@acme.test", "/acme/applicant")))
                .andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Test
    void approverCanListTenantTickets() throws Exception {
        when(service.tenantTickets(null, 0, 50)).thenReturn(List.of(view()));
        mvc.perform(get("/api/approvals/tickets").with(user("bob@acme.test", "/acme/approver")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].title").value("VPN broken"));
    }

    @Test
    void rolesAreEvaluatedPerTenant() throws Exception {
        // alice is an applicant in acme but an approver in globex
        var alice = user("alice@x.test", "/acme/applicant", "/globex/approver");
        when(service.create(any())).thenReturn(view());

        mvc.perform(post("/api/tickets").with(alice).header("X-Tenant-ID", "acme")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/tickets").with(alice).header("X-Tenant-ID", "globex")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isForbidden());
    }

    @Test
    void multiTenantUserMustSelectATenant() throws Exception {
        mvc.perform(get("/api/tickets").with(user("alice@x.test", "/acme/applicant", "/globex/applicant")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void headerForATenantTheTokenDoesNotProveIsForbidden() throws Exception {
        mvc.perform(get("/api/tickets").with(user("alice@acme.test", "/acme/applicant")).header("X-Tenant-ID", "globex"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Test
    void meWorksWithoutChoosingATenant() throws Exception {
        mvc.perform(get("/api/me").with(user("alice@x.test", "/acme/applicant", "/globex/approver")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("alice@x.test"))
                .andExpect(jsonPath("$.tenants.acme[0]").value("APPLICANT"))
                .andExpect(jsonPath("$.tenants.globex[0]").value("APPROVER"));
    }

    @Test
    void pageSizeIsBounded() throws Exception {
        mvc.perform(get("/api/approvals/tickets").param("size", "10000").with(user("bob@acme.test", "/acme/approver")))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/tickets").param("page", "-1").with(user("alice@acme.test", "/acme/applicant")))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test
    void tenantQuotaIsEnforcedAcrossUsersOfTheSameTenant() throws Exception {
        // tenant "tiny" allows 2 requests per minute in total, regardless of which user sends them
        when(tenantPolicies.policyFor("tiny")).thenReturn(new TenantPolicy("tiny", "standard", 2, 20));
        when(service.myTickets(0, 50)).thenReturn(List.of());

        mvc.perform(get("/api/tickets").with(user("a@tiny.test", "/tiny/applicant")))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .string("X-Tenant-RateLimit-Remaining", "1"));
        mvc.perform(get("/api/tickets").with(user("b@tiny.test", "/tiny/applicant"))).andExpect(status().isOk());
        mvc.perform(get("/api/tickets").with(user("c@tiny.test", "/tiny/applicant")))
                .andExpect(status().isTooManyRequests())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().exists("Retry-After"));

        verify(tenantUsage, atLeastOnce()).recordThrottled("tiny");
        // another tenant is unaffected (noisy-neighbour isolation)
        mvc.perform(get("/api/tickets").with(user("x@acme.test", "/acme/applicant"))).andExpect(status().isOk());
    }

    @Test
    void selfApprovalIsForbidden() throws Exception {
        UUID id = UUID.randomUUID();
        when(service.claim(id)).thenThrow(new TicketForbiddenException("Approvers cannot act on tickets they raised themselves"));
        mvc.perform(post("/api/approvals/tickets/" + id + "/claim").with(user("alice@x.test", "/acme/applicant", "/acme/approver")))
                .andExpect(status().isForbidden());
    }

    @Test
    void invalidMobileNumberIsRejected() throws Exception {
        mvc.perform(post("/api/tickets").with(user("alice@acme.test", "/acme/applicant"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"t\",\"mobile\":\"abc\",\"description\":\"d\"}"))
                .andExpect(status().isBadRequest());
        verify(service, never()).create(any());
    }
}
