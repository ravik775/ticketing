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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketing.TicketingApplication;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.annotation.DirtiesContext;
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
@SpringBootTest(classes = TicketingApplication.class,
        properties = {"ticketing.outbox.relay-interval=PT1S", "ticketing.outbox.relay-initial-delay=PT1S"})
@AutoConfigureMockMvc
// Close the context (and its background jobs: outbox relay, metering) with this class, BEFORE Testcontainers
// stops the databases; otherwise the cached context polls dead databases at JVM exit.
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
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
    @Autowired
    MongoTemplate mongo;
    @MockitoBean
    JwtDecoder jwtDecoder;

    private long outboxCount(String ticketId, String condition) throws Exception {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT count(*) FROM ticket_outbox WHERE ticket_id = '" + ticketId + "' AND " + condition)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private void sql(String statement) throws Exception {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.execute(statement);
        }
    }

    private static void eventually(java.util.concurrent.Callable<Boolean> condition) throws Exception {
        for (int i = 0; i < 100; i++) {
            if (condition.call()) {
                return;
            }
            Thread.sleep(200);
        }
        throw new AssertionError("condition not met within 20 s");
    }

    @Test
    void everyChangeIsCommittedToTheOutboxAndProjectedToMongo() throws Exception {
        var alice = user("outbox1@acme.test", "/acme/applicant");
        var bob = user("bob@acme.test", "/acme/approver");
        String id = createTicket(alice, "acme", "outbox happy path");
        mvc.perform(post("/api/approvals/tickets/" + id + "/claim").with(bob)).andExpect(status().isOk());

        assertThat(outboxCount(id, "true")).isEqualTo(2);                         // CREATED + CLAIMED
        assertThat(outboxCount(id, "published_at IS NULL")).isZero();             // both projected immediately
        mvc.perform(get("/api/tickets/" + id).with(alice))
                .andExpect(jsonPath("$.events[*].type", hasItems("CREATED", "CLAIMED")));
    }

    /** The old silent slip: appending history to a missing Mongo document was ignored. Now it is retried and visible. */
    @Test
    void lostProjectionIsDetectedAndRebuiltFromTheOutbox() throws Exception {
        var alice = user("outbox2@acme.test", "/acme/applicant");
        var bob = user("bob@acme.test", "/acme/approver");
        String id = createTicket(alice, "acme", "outbox recovery");
        mvc.perform(post("/api/approvals/tickets/" + id + "/claim").with(bob)).andExpect(status().isOk());

        // Simulate losing the MongoDB document. The change itself still succeeds (PostgreSQL is the source of truth) ...
        mongo.remove(Query.query(Criteria.where("_id").is(id)), "ticket_details");
        mvc.perform(post("/api/approvals/tickets/" + id + "/unlock").with(bob)).andExpect(status().isOk());
        // ... and the event is NOT silently dropped: it stays pending with the reason recorded.
        assertThat(outboxCount(id, "event_type = 'UNLOCKED' AND published_at IS NULL AND attempts >= 1 AND last_error LIKE '%does not exist%'"))
                .isEqualTo(1);

        // Rebuild the projection: mark this ticket's events for replay; the relay re-creates the document in order.
        sql("SET app.outbox_relay = 'on'; UPDATE ticket_outbox SET published_at = NULL, attempts = 0 WHERE ticket_id = '" + id + "'");
        eventually(() -> outboxCount(id, "published_at IS NULL") == 0);
        mvc.perform(get("/api/tickets/" + id).with(alice))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("outbox recovery"))
                .andExpect(jsonPath("$.events[*].type", hasItems("CREATED", "CLAIMED", "UNLOCKED")))
                .andExpect(jsonPath("$.events.length()").value(3));                // replay is idempotent: no duplicates
    }

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

    // ---------------------------------------------------------------- MCP endpoint (/api/mcp)

    /** One stateless MCP JSON-RPC call (tools/call) through the full security chain. */
    private JsonNode mcp(JwtRequestPostProcessor as, String tenant, String tool, String arguments) throws Exception {
        String body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"" + tool
                + "\",\"arguments\":" + arguments + "}}";
        var request = post("/api/mcp").with(as).contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM).content(body);
        if (tenant != null) {
            request.header("X-Tenant-ID", tenant);
        }
        String response = mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(response).get("result");
    }

    /** The ticket returned by a successful tool call (JSON text content, the same JSON as the REST response). */
    private JsonNode ticket(JsonNode result) throws Exception {
        assertThat(result.get("isError").asBoolean()).as("tool call failed: %s", result).isFalse();
        return json.readTree(result.get("content").get(0).get("text").asText());
    }

    private static String errorText(JsonNode result) {
        assertThat(result.get("isError").asBoolean()).as("tool result should be an error: %s", result).isTrue();
        return result.get("content").get(0).get("text").asText();
    }

    private List<String> outboxColumn(String ticketId, String column) throws Exception {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT " + column + " FROM ticket_outbox WHERE ticket_id = '" + ticketId
                     + "' ORDER BY occurred_at")) {
            List<String> values = new ArrayList<>();
            while (rs.next()) {
                values.add(rs.getString(1));
            }
            return values;
        }
    }

    @Test
    void mcpListsExactlyTheThreeTools() throws Exception {
        String response = mvc.perform(post("/api/mcp").with(user("list@acme.test", "/acme/approver"))
                        .contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
                        .content("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode tools = json.readTree(response).get("result").get("tools");
        List<String> names = new ArrayList<>();
        tools.forEach(t -> names.add(t.get("name").asText()));
        assertThat(names).containsExactlyInAnyOrder("create_ticket", "decide_ticket", "get_ticket");
        for (JsonNode tool : tools) {
            if ("decide_ticket".equals(tool.get("name").asText())) {
                assertThat(tool.get("annotations").get("destructiveHint").asBoolean()).isTrue();
                assertThat(tool.get("inputSchema").toString()).contains("APPROVE", "REJECT", "REQUEST_INFO");
            }
        }
    }

    @Test
    void mcpCreateTicketUsesTheSameRulesAsRestAndRecordsTheChannel() throws Exception {
        var alice = user("mcp-alice@acme.test", "/acme/applicant");
        JsonNode created = mcp(alice, "acme", "create_ticket",
                "{\"title\":\"Laptop\",\"mobile\":\"+919876543210\",\"description\":\"New laptop needed\"}");
        assertThat(created.get("isError").asBoolean()).as(created.toString()).isFalse();
        JsonNode ticket = ticket(created);
        assertThat(ticket.get("status").asText()).isEqualTo("OPEN");
        assertThat(ticket.get("createdBy").asText()).isEqualTo("mcp-alice@acme.test");   // from the token
        String id = ticket.get("id").asText();
        assertThat(outboxColumn(id, "channel")).containsExactly("MCP");

        // Same validation and the same wording as POST /api/tickets.
        assertThat(errorText(mcp(alice, "acme", "create_ticket",
                "{\"title\":\"\",\"mobile\":\"abc\",\"description\":\"x\"}")))
                .startsWith("400 Bad Request:").contains("mobile must be 7-15 digits").contains("title must not be blank");
        // RBAC: approvers cannot raise tickets, as over REST.
        assertThat(errorText(mcp(user("mcp-bob@acme.test", "/acme/approver"), "acme", "create_ticket",
                "{\"title\":\"x\",\"mobile\":\"+919876543210\",\"description\":\"x\"}"))).startsWith("403 Forbidden");
    }

    @Test
    void mcpDecideTakesOverAnotherApproversLockAtomicallyAndRecordsWhoAndHow() throws Exception {
        var alice = user("mcp2-alice@acme.test", "/acme/applicant");
        var bob = user("mcp2-bob@acme.test", "/acme/approver");
        var carol = user("mcp2-carol@acme.test", "/acme/approver");
        String id = createTicket(alice, "acme", "mcp decide");
        mvc.perform(post("/api/approvals/tickets/" + id + "/claim").with(bob)).andExpect(status().isOk());

        JsonNode decided = mcp(carol, "acme", "decide_ticket",
                "{\"ticketId\":\"" + id + "\",\"decision\":\"APPROVE\",\"comment\":\"Approved by policy\"}");
        assertThat(decided.get("isError").asBoolean()).as(decided.toString()).isFalse();
        assertThat(ticket(decided).get("status").asText()).isEqualTo("APPROVED");

        // One transaction, every step recorded: who did it and through which path.
        assertThat(outboxColumn(id, "event_type")).containsExactly("CREATED", "CLAIMED", "UNLOCKED", "CLAIMED", "APPROVE");
        assertThat(outboxColumn(id, "actor")).containsExactly("mcp2-alice@acme.test", "mcp2-bob@acme.test",
                "mcp2-carol@acme.test", "mcp2-carol@acme.test", "mcp2-carol@acme.test");
        assertThat(outboxColumn(id, "channel")).containsExactly("REST", "REST", "MCP", "MCP", "MCP");
        mvc.perform(get("/api/tickets/" + id).with(alice))
                .andExpect(jsonPath("$.events[4].type").value("APPROVE"))
                .andExpect(jsonPath("$.events[4].actor").value("mcp2-carol@acme.test"))
                .andExpect(jsonPath("$.events[4].channel").value("MCP"))
                .andExpect(jsonPath("$.events[4].comment").value("Approved by policy"));

        // A decided ticket cannot be decided again: 409, and nothing was written (all or nothing).
        assertThat(errorText(mcp(bob, "acme", "decide_ticket",
                "{\"ticketId\":\"" + id + "\",\"decision\":\"REJECT\",\"comment\":\"too late\"}"))).startsWith("409 Conflict");
        assertThat(outboxCount(id, "true")).isEqualTo(5);
    }

    @Test
    void mcpDecideOnAnOpenTicketClaimsThenDecides() throws Exception {
        String id = createTicket(user("mcp3-alice@acme.test", "/acme/applicant"), "acme", "mcp more info");
        JsonNode decided = mcp(user("mcp3-bob@acme.test", "/acme/approver"), "acme", "decide_ticket",
                "{\"ticketId\":\"" + id + "\",\"decision\":\"REQUEST_INFO\",\"comment\":\"Which model?\"}");
        assertThat(ticket(decided).get("status").asText()).isEqualTo("MORE_INFO");
        assertThat(outboxColumn(id, "event_type")).containsExactly("CREATED", "CLAIMED", "REQUEST_INFO");
    }

    @Test
    void mcpDecideKeepsSeparationOfDutiesAndRoles() throws Exception {
        var both = user("mcp4-dual@acme.test", "/acme/applicant", "/acme/approver");
        String own = createTicket(both, "acme", "my own ticket");
        assertThat(errorText(mcp(both, "acme", "decide_ticket",
                "{\"ticketId\":\"" + own + "\",\"decision\":\"APPROVE\",\"comment\":\"self\"}"))).startsWith("403 Forbidden");
        assertThat(errorText(mcp(user("mcp4-app@acme.test", "/acme/applicant"), "acme", "decide_ticket",
                "{\"ticketId\":\"" + own + "\",\"decision\":\"APPROVE\",\"comment\":\"x\"}"))).startsWith("403 Forbidden");
        assertThat(errorText(mcp(user("mcp4-bob@acme.test", "/acme/approver"), "acme", "decide_ticket",
                "{\"ticketId\":\"" + own + "\",\"decision\":\"APPROVE\",\"comment\":\"\"}")))
                .isEqualTo("400 Bad Request: comment must not be blank");
        assertThat(outboxCount(own, "true")).isEqualTo(1);                          // only CREATED: nothing changed
    }

    @Test
    void mcpGetTicketReturnsOnlyTicketsTheCallerMayApprove() throws Exception {
        var dual = user("mcp5-dual@acme.test", "/acme/applicant", "/acme/approver");
        String own = createTicket(dual, "acme", "dual ticket");
        String other = createTicket(user("mcp5-erin@acme.test", "/acme/applicant"), "acme", "erin ticket");

        JsonNode found = mcp(dual, "acme", "get_ticket", "{\"ticketId\":\"" + other + "\"}");
        assertThat(ticket(found).get("title").asText()).isEqualTo("erin ticket");
        // Own ticket (separation of duties) and another tenant's ticket look like they do not exist.
        assertThat(errorText(mcp(dual, "acme", "get_ticket", "{\"ticketId\":\"" + own + "\"}"))).startsWith("404 Not Found");
        assertThat(errorText(mcp(user("mcp5-dave@globex.test", "/globex/approver"), "globex", "get_ticket",
                "{\"ticketId\":\"" + other + "\"}"))).startsWith("404 Not Found");
        // Applicants have no approval rights at all.
        assertThat(errorText(mcp(user("mcp5-erin@acme.test", "/acme/applicant"), "acme", "get_ticket",
                "{\"ticketId\":\"" + other + "\"}"))).startsWith("403 Forbidden");
        assertThat(errorText(mcp(dual, "acme", "get_ticket", "{\"ticketId\":\"not-a-uuid\"}")))
                .isEqualTo("400 Bad Request: ticketId must be a UUID");
    }
}
