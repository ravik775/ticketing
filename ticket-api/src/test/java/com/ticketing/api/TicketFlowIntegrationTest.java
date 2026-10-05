package com.ticketing.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketing.TicketingApplication;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * End-to-end through the real stack: Spring Security -> TicketService -> PostgreSQL + MongoDB.
 * Skipped automatically when Docker is not available.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = TicketingApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TicketFlowIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    @Container
    static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7");

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.data.mongodb.uri", () -> MONGO.getReplicaSetUrl("ticketing"));
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper json;
    @Autowired
    DataSource dataSource;
    @MockitoBean
    JwtDecoder jwtDecoder;

    private static JwtRequestPostProcessor user(String email, String... groups) {
        return jwt().jwt(j -> j.claim("email", email).claim("groups", List.of(groups)));
    }

    private String createTicket(JwtRequestPostProcessor as, String tenant, String title) throws Exception {
        String response = mvc.perform(post("/api/tickets").with(as).header("X-Tenant-ID", tenant)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"" + title + "\",\"mobile\":\"+919876543210\",\"description\":\"details\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(response).get("id").asText();
    }

    @Test
    void applicantsOnlySeeTheirOwnTicketsAndTenantsAreIsolated() throws Exception {
        var alice = user("alice@x.test", "/acme/applicant", "/globex/applicant");
        var erin = user("erin@acme.test", "/acme/applicant");
        var dave = user("dave@globex.test", "/globex/approver");
        var bob = user("bob@acme.test", "/acme/approver");

        String aliceAcme = createTicket(alice, "acme", "alice in acme");
        createTicket(alice, "globex", "alice in globex");
        createTicket(erin, "acme", "erin in acme");

        // Alice sees only her acme ticket when acting in acme
        mvc.perform(get("/api/tickets").with(alice).header("X-Tenant-ID", "acme"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].title").value("alice in acme"));

        // Erin (same tenant, different user) cannot open Alice's ticket: looks like it does not exist
        mvc.perform(get("/api/tickets/" + aliceAcme).with(erin)).andExpect(status().isNotFound());

        // Approver of acme sees both acme tickets; approver of globex sees only globex data
        mvc.perform(get("/api/approvals/tickets").with(bob))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].title", hasItems("alice in acme", "erin in acme")))
                .andExpect(jsonPath("$[*].title", not(hasItem("alice in globex"))))
                .andExpect(jsonPath("$[?(@.tenantId != 'acme')]", empty()));
        mvc.perform(get("/api/approvals/tickets").with(dave))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].title", hasItem("alice in globex")))
                .andExpect(jsonPath("$[?(@.tenantId != 'globex')]", empty()));
        mvc.perform(get("/api/approvals/tickets/" + aliceAcme).with(dave)).andExpect(status().isNotFound());
    }

    @Test
    void lockUnlockAndDecisionFlow() throws Exception {
        var alice = user("alice2@acme.test", "/acme/applicant");
        var bob = user("bob@acme.test", "/acme/approver");
        var carol = user("carol@acme.test", "/acme/approver");
        String id = createTicket(alice, "acme", "needs approval");
        String base = "/api/approvals/tickets/" + id;
        String approve = "{\"decision\":\"APPROVE\",\"comment\":\"ok\"}";

        mvc.perform(post(base + "/claim").with(bob)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("LOCKED")).andExpect(jsonPath("$.lockedBy").value("bob@acme.test"));
        // locked: carol can neither claim nor decide
        mvc.perform(post(base + "/claim").with(carol)).andExpect(status().isConflict());
        mvc.perform(post(base + "/decision").with(carol).contentType(MediaType.APPLICATION_JSON).content(approve))
                .andExpect(status().isConflict());
        // carol unlocks, then claims and approves
        mvc.perform(post(base + "/unlock").with(carol)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OPEN"));
        mvc.perform(post(base + "/claim").with(carol)).andExpect(status().isOk());
        mvc.perform(post(base + "/decision").with(carol).contentType(MediaType.APPLICATION_JSON).content(approve))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPROVED"));

        // history (MongoDB) is visible to the applicant
        mvc.perform(get("/api/tickets/" + id).with(alice)).andExpect(status().isOk())
                .andExpect(jsonPath("$.events.length()").value(5)); // CREATED, CLAIMED, UNLOCKED, CLAIMED, APPROVE
    }

    @Test
    void requestMoreInfoLoopsBackThroughTheApplicant() throws Exception {
        var alice = user("alice3@acme.test", "/acme/applicant");
        var bob = user("bob@acme.test", "/acme/approver");
        String id = createTicket(alice, "acme", "unclear");
        String base = "/api/approvals/tickets/" + id;

        mvc.perform(post(base + "/claim").with(bob)).andExpect(status().isOk());
        mvc.perform(post(base + "/decision").with(bob).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"REQUEST_INFO\",\"comment\":\"Which server?\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("MORE_INFO"));
        mvc.perform(post("/api/tickets/" + id + "/respond").with(alice).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"comment\":\"The VPN gateway\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("OPEN"));
    }

    @Test
    void approverCannotActOnATicketTheyRaised() throws Exception {
        // frank holds BOTH roles in acme: he may raise tickets and approve others', never his own
        var frank = user("frank@acme.test", "/acme/applicant", "/acme/approver");
        var bob = user("bob@acme.test", "/acme/approver");
        String id = createTicket(frank, "acme", "my own request");
        String base = "/api/approvals/tickets/" + id;

        mvc.perform(post(base + "/claim").with(frank).header("X-Tenant-ID", "acme")).andExpect(status().isForbidden());
        mvc.perform(post(base + "/claim").with(bob)).andExpect(status().isOk());
        mvc.perform(post(base + "/unlock").with(frank).header("X-Tenant-ID", "acme")).andExpect(status().isForbidden());
        mvc.perform(post(base + "/decision").with(frank).header("X-Tenant-ID", "acme")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"decision\":\"APPROVE\",\"comment\":\"self\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(get(base).with(bob)).andExpect(jsonPath("$.lockedBy").value("bob@acme.test"));
    }

    @Test
    void databaseRefusesAnOwnerHoldingTheLock() throws Exception {
        String id = createTicket(user("sod@acme.test", "/acme/applicant"), "acme", "sod probe");
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            c.setAutoCommit(false);
            s.execute("SELECT set_config('app.tenant_id', 'acme', true)");
            assertThatThrownBy(() -> s.executeUpdate("UPDATE ticket_workflow SET status='LOCKED', locked_by='SOD@acme.test', "
                    + "locked_at=now() WHERE id='" + id + "'"))
                    .hasMessageContaining("ck_owner_not_approver");
            c.rollback();
        }
    }

    @Test
    void listsArePaged() throws Exception {
        var paul = user("paul@acme.test", "/acme/applicant");
        for (int i = 0; i < 3; i++) {
            createTicket(paul, "acme", "page " + i);
        }
        mvc.perform(get("/api/tickets").param("size", "2").with(paul))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2));
        mvc.perform(get("/api/tickets").param("size", "2").param("page", "1").with(paul))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
    }

    /** Postgres itself must refuse other tenants' rows, even if the application layer were bypassed. */
    @Test
    void rowLevelSecurityFailsClosedAndIsolatesTenants() throws Exception {
        createTicket(user("rls@acme.test", "/acme/applicant"), "acme", "rls probe");

        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            // The Testcontainers user is a superuser (bypasses RLS), so probe as an ordinary role.
            s.execute("DO $$ BEGIN IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname='rls_probe') "
                    + "THEN CREATE ROLE rls_probe NOLOGIN; END IF; END $$");
            s.execute("GRANT SELECT ON ticket_workflow TO rls_probe");
            c.setAutoCommit(false);
            s.execute("SET LOCAL ROLE rls_probe");

            assertThat(count(s)).as("no tenant bound => no rows").isZero();
            s.execute("SELECT set_config('app.tenant_id', 'globex', true)");
            assertThat(count(s)).as("other tenant => no rows").isZero();
            s.execute("SELECT set_config('app.tenant_id', 'acme', true)");
            assertThat(count(s)).as("own tenant => rows").isPositive();
            c.rollback();
        }
    }

    private static long count(Statement s) throws Exception {
        try (ResultSet rs = s.executeQuery("SELECT count(*) FROM ticket_workflow")) {
            rs.next();
            return rs.getLong(1);
        }
    }
}
