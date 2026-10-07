# 10. MCP endpoint for AI agents

The ticketing service exposes its main use cases as **MCP tools** (Model Context Protocol), so an AI
assistant such as Claude or an IDE agent can raise and decide tickets **on behalf of a signed-in
user**, with exactly that user's permissions.

| | |
|---|---|
| Endpoint | `https://ticketing.localtest.me:8443/api/mcp` |
| Transport | MCP **Streamable HTTP, stateless** (JSON-RPC over `POST`, JSON responses, no MCP session) |
| Implementation | Spring AI 1.1.8 MCP server (`spring-ai-starter-mcp-server-webmvc`), tools in `ticket-api/.../TicketMcpTools.java` |
| Authentication | OAuth 2.0 bearer token from Keycloak (client `ticketing-mcp`, Authorization Code + PKCE, user consent) |
| Authorization | the user's per-tenant roles, enforced by `TicketService` (identical to REST) |

---

## 10.1 Tools

| Tool | REST equivalent | Arguments (same names and limits as the REST body) | Who may call it |
|---|---|---|---|
| `create_ticket` | `POST /api/tickets` | `title` (1–120), `mobile` (7–15 digits, optional `+`), `description` (1–4000) | applicant in the active tenant |
| `decide_ticket` | `POST /api/approvals/tickets/{id}/decision` (plus claim, see below) | `ticketId` (UUID), `decision` (`APPROVE` \| `REJECT` \| `REQUEST_INFO`), `comment` (1–2000) | approver in the active tenant, never on a ticket they raised |
| `get_ticket` | `GET /api/approvals/tickets/{id}` | `ticketId` | approver; only tickets the caller is **permitted to approve** (own tickets and other tenants' tickets are reported as not found) |

**Results** are the ticket as JSON text, byte-for-byte the REST response. **Errors** come back as MCP
tool errors (`isError: true`) with the same status and wording as REST, e.g.
`409 Conflict: Ticket cannot be picked up in status APPROVED` or
`400 Bad Request: mobile must be 7-15 digits, optional leading +`.

### `decide_ticket` is atomic

One call performs, in **one database transaction** (all or nothing):

1. if another approver holds the lock, **release** it (`UNLOCKED`, comment "Lock held by X released to decide"),
2. **pick up** the ticket (`CLAIMED`), unless the caller already holds the lock,
3. apply the **decision** (`APPROVE`, `REJECT` or `REQUEST_INFO`) with the caller's comment.

The same method (`TicketService.claimAndDecide`) is available to any adapter; the REST API keeps its
separate claim / unlock / decision endpoints. A ticket that is already decided, or waiting for the
applicant (`MORE_INFO`), cannot be decided (409) and nothing is written.

### Who did it, and through which path

Every history event now records the **channel** next to the actor: `REST` (web UI, scripts) or `MCP`
(AI agents). The channel is decided by the server from the endpoint that received the request, never
from client input. It is stored in the outbox (`ticket_outbox.channel`, migration `V6`), projected into
MongoDB, returned by the API and shown in the UI history, e.g.:

```
10/7/2026, 09:14 · UNLOCKED by bob@ticketing.test via MCP: Lock held by carol@ticketing.test released to decide
10/7/2026, 09:14 · CLAIMED by bob@ticketing.test via MCP
10/7/2026, 09:14 · APPROVE by bob@ticketing.test via MCP: Approved via agent
```

---

## 10.2 How a request is secured (nothing new to bypass)

```
AI client ──HTTPS──► WAF ──TLS──► Kong route /api ──mTLS──► ticket-service ──► TicketService
                     │            │                         │
                     │            │                         ├ JWT re-validated (iss, exp, typ, azp, aud)
                     │            │                         ├ mTLS caller pinned to kong-gateway
                     │            │                         ├ tenant from token groups (+ X-Tenant-ID selector)
                     │            │                         ├ per-tenant quota
                     │            │                         └ role + separation of duties in TicketService
                     │            └ JWT signature/expiry, 300 req/min per user, 1 MB limit, correlation ID
                     └ OWASP CRS inspects the JSON-RPC body (e.g. SQL injection in a tool argument → 403)
```

Because the endpoint lives **under `/api`**, every control above applies to MCP without new
configuration. The only additions are for OAuth discovery and the MCP client:

| Where | Addition | Why |
|---|---|---|
| Kong (`scripts/render-kong.sh`) | `401` on `/api/mcp` carries `WWW-Authenticate: Bearer resource_metadata="…/.well-known/oauth-protected-resource/api/mcp"` | MCP authorization spec: tells the client where to start |
| Kong | route `/.well-known/oauth-protected-resource` returns the protected-resource metadata (RFC 9728) as static JSON | names Keycloak as the authorization server |
| WAF (`k8s/80-waf.yaml`, rule 1000110) | `/.well-known/oauth-protected-resource` added to the path allow-list | the discovery document must be reachable |
| WAF (rule 1000210, FP-2) | CRS rules 931100/934110 ignore `redirect_uri` on Keycloak's authorize and token endpoints only | MCP clients sign in with a loopback redirect `http://127.0.0.1:<port>/…` (RFC 8252); Keycloak never fetches it ([guide 9](09-waf-firewall.md)) |
| Keycloak (`scripts/configure-keycloak-mcp.sh`) | public client `ticketing-mcp`: Authorization Code + PKCE, consent screen, no password grant, loopback redirect URIs, **audience mapper `aud=ticketing-api`** | MCP clients are a different application from the UI; the user approves the delegation |
| Service (`JwtDecoderConfig`) | accepts `azp` ∈ {`ticketing-ui`, `ticketing-mcp`}; **`ticketing-mcp` tokens must carry `aud=ticketing-api`** | a token issued for another API cannot be replayed here (RFC 8707 / MCP) |

The UI's tokens are exempt from the audience rule because they have never carried `aud` (documented gap
in [guide 8](08-security-zero-trust.md)).

---

## 10.3 Design decisions

| Decision | Choice | Reason |
|---|---|---|
| Architecture | MCP is a **second inbound adapter** on the existing application port (`TicketService`) | ports and adapters: one implementation of every rule, two ways in |
| Reuse | same request records and Bean Validation, same error classifier (`ApiErrors`) for REST problem details and MCP tool errors | one source of truth; both channels report the same outcome in the same words |
| Transport | stateless Streamable HTTP | no MCP session to pin to a pod: scales like REST, no sticky routing in Kong |
| Path | `/api/mcp` (see 10.4) | inherits the WAF, Kong and service controls with no new route |
| Identity | the **user's** token (delegation), never a service account | the agent can do exactly what the user can, and the audit trail names the user |
| Output schema | not generated; the result is the REST JSON as text | the generator cannot express nullable fields (`lockedBy`, …), and a hand-made MCP view would duplicate the REST model |
| Threading | tools run on the request thread (Spring AI sets `immediateExecution` for servlet apps) | the tenant and security context bound by the filters are available to the tools |

### 10.4 `/api/mcp` versus `/mcp`

| Aspect | `/api/mcp` (chosen) | `/mcp` |
|---|---|---|
| Kong | covered by the existing `/api` route: JWT, per-user rate limit, size limit, correlation ID, mTLS | needs a **new route** with a copy of every plugin; two places to keep in sync |
| WAF | covered by the existing allow-list entry `api/` and the JSON-only body rule | new allow-list entry and content-type rule |
| Service security chain | `/api/**` is already authenticated; tenant filter and quotas run | `SecurityConfig` matcher must be extended |
| Separate limits for agents | possible later with a more specific Kong route for `/api/mcp` | built in from the start |
| Discoverability / convention | slightly unusual (many servers use `/mcp`) | the common convention |
| Risk of drift | none: one route | a control added to `/api` can be forgotten on `/mcp` |

`/api/mcp` is **simpler and easier to maintain**: one gateway route, one WAF rule set, one security chain.
`/mcp` only pays off if agents need very different gateway treatment (own rate limits, own plugins),
and even then a dedicated route for `/api/mcp` gives that without moving the path.

### 10.5 A2A and UCP

* **A2A (Agent2Agent)** is for one *agent* delegating a task to another agent. This system exposes
  tools, not an agent, so MCP is the right protocol. If an "approval agent" is built later, A2A would be
  a third adapter on the same `TicketService` (its task states map well: `input-required` ≈ `MORE_INFO`).
* **UCP (Universal Commerce Protocol)** standardises agentic *commerce* (catalog, checkout, payment). It
  does not fit an approval workflow.

---

## 10.6 Connecting an MCP client

**1. Trust the cluster's private CA** (the certificate is not publicly trusted on the laptop):

```bash
kubectl -n gateway get secret kong-client-tls -o jsonpath='{.data.ca\.crt}' | base64 -d > ticketing-ca.crt
export NODE_EXTRA_CA_CERTS="$PWD/ticketing-ca.crt"   # for Node.js based MCP clients
```

**2. Add the server.** The URL is `https://ticketing.localtest.me:8443/api/mcp`. Users who belong to
several tenants add the header `X-Tenant-ID: <tenant>` (single-tenant users need none). For example,
with Claude Code:

```bash
claude mcp add --transport http ticketing https://ticketing.localtest.me:8443/api/mcp --header "X-Tenant-ID: acme"
```

**3. Sign in.** The client receives a `401` with the metadata link, discovers Keycloak, and opens the
browser for sign-in and the consent screen. The client must use the pre-registered client id
`ticketing-mcp` with PKCE. Clients that insist on **Dynamic Client Registration** are not supported yet:
Keycloak's DCR is not enabled (an open decision, because anonymous registration needs its own policy).

**Testing without OAuth**: send a bearer token yourself, for example one copied from the browser's
developer tools after signing in to the web UI (`--header "Authorization: Bearer <token>"`).

### Try it with curl

```bash
TOKEN=...   # an access token of an approver (e.g. bob)
curl -sk https://ticketing.localtest.me:8443/api/mcp \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -H 'Accept: application/json, text/event-stream' \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"get_ticket","arguments":{"ticketId":"<id>"}}}'
```

---

## 10.7 Operating it

| Need | Where |
|---|---|
| Tool calls per tool and outcome | metric `ticketing_mcp_tool_calls_total{tool, outcome=success\|rejected\|error}` (Prometheus) |
| Who decided via an agent | ticket history (`channel = MCP`), or `select actor, event_type, occurred_at from ticket_outbox where channel = 'MCP'` |
| Request latency and errors | `http_server_requests_seconds{uri="/api/mcp"}` and the existing SLO alerts |
| Abuse | Kong per-user limit and per-tenant quota apply; investigate with the correlation ID in the tool error |

### Risks specific to AI clients

| Risk | Mitigation in place |
|---|---|
| **Prompt injection**: ticket text is written by applicants and read by the model | tool descriptions and server instructions tell the model to treat ticket text as data; `decide_ticket` is annotated **destructive** so clients ask the human to confirm; separation of duties; comment required |
| Bulk decisions by a runaway agent | Kong per-user rate limit, per-tenant quota, history per channel; an alert on the MCP decision rate is a recommended next step |
| Token replay from another application | `aud=ticketing-api` required for `ticketing-mcp` tokens; short token lifetime (5 min) |
| Over-broad delegation | tokens are the user's own; consent screen; no service account with wider rights |

### Tests

* `TicketFlowIntegrationTest` (Testcontainers, real PostgreSQL and MongoDB): tool list and annotations,
  create with validation and RBAC, atomic decide with lock takeover and the recorded actor/channel,
  REQUEST_INFO from an open ticket, separation of duties, `get_ticket` visibility rules.
* `JwtValidatorTest`: `ticketing-mcp` tokens need `aud=ticketing-api`.
* `scripts/e2e.sh`, section "MCP endpoint" (21 checks): through the real WAF and Kong, including the 401
  discovery header, the metadata document, the rate-limit headers, and the WAF blocking SQL injection
  inside a tool argument; plus two WAF checks for the FP-2 exclusion (loopback sign-in passes, a metadata
  URL in any other argument is still blocked).
* A real MCP-client sign-in was verified with a headless browser: discovery from the 401 → Keycloak
  sign-in and consent screen → PKCE code exchange → token with `azp=ticketing-mcp`, `aud=ticketing-api`
  → `tools/list` succeeds.
