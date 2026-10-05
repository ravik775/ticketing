// k6 load test through the full path: WAF -> Kong -> ticket-service -> PostgreSQL + MongoDB.
// Run with scripts/load-test.sh (creates the `loadtest` tenant and users, runs k6 in Docker, cleans up).
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Trend, Counter } from 'k6/metrics';

const BASE = __ENV.BASE || 'https://ticketing.localtest.me:8443';
const USERS = parseInt(__ENV.USERS || '10', 10);          // concurrent VUs per role
// k6 numbers VUs across BOTH scenarios (1..2*USERS), so each role gets a pool of 2*USERS identities and VU n
// always uses identity n: no two VUs share a user (sharing would trip Kong's per-user rate limit).
const POOL = 2 * USERS;
const PASSWORD = __ENV.LOAD_PASSWORD;
const TENANT = 'loadtest';

const createLatency = new Trend('ticket_create_ms', true);
const listLatency = new Trend('approver_list_ms', true);
const decideLatency = new Trend('claim_and_decide_ms', true);
const conflicts = new Counter('lock_conflicts_409');

export const options = {
  insecureSkipTLSVerify: true,           // local private CA
  setupTimeout: '120s',
  scenarios: {
    applicants: { executor: 'ramping-vus', exec: 'applicant', startVUs: 0,
      stages: [{ duration: '30s', target: USERS }, { duration: '2m', target: USERS }, { duration: '15s', target: 0 }] },
    approvers:  { executor: 'ramping-vus', exec: 'approver', startVUs: 0,
      stages: [{ duration: '30s', target: USERS }, { duration: '2m', target: USERS }, { duration: '15s', target: 0 }] },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],                      // < 1 % errors (409 lock conflicts are expected, not failures)
    'http_req_duration{kind:api}': ['p(95)<1000', 'p(99)<2000'],
  },
};
http.setResponseCallback(http.expectedStatuses({ min: 200, max: 299 }, 409));

function token(user) {
  const r = http.post(`${BASE}/auth/realms/ticketing/protocol/openid-connect/token`,
    { grant_type: 'password', client_id: 'ticketing-ui', username: user, password: PASSWORD, scope: 'openid' },
    { tags: { kind: 'login' } });
  return r.json('access_token');
}

export function setup() {
  const t = { applicants: [], approvers: [] };
  for (let i = 1; i <= POOL; i++) {
    t.applicants.push(token(`lt-applicant-${i}`));
    t.approvers.push(token(`lt-approver-${i}`));
  }
  return t;
}

const hdr = (tok) => ({ headers: { Authorization: `Bearer ${tok}`, 'X-Tenant-ID': TENANT, 'Content-Type': 'application/json' },
                       tags: { kind: 'api' } });

export function applicant(data) {
  const tok = data.applicants[(__VU - 1) % POOL];
  const r = http.post(`${BASE}/api/tickets`, JSON.stringify({
    title: `Load ${__VU}-${__ITER}`, mobile: '+919876543210', description: 'k6 load test ticket' }), hdr(tok));
  check(r, { 'created 201': (x) => x.status === 201 });
  createLatency.add(r.timings.duration);
  const l = http.get(`${BASE}/api/tickets?size=20`, hdr(tok));
  check(l, { 'my list 200': (x) => x.status === 200 });
  sleep(1);
}

export function approver(data) {
  const tok = data.approvers[(__VU - 1) % POOL];
  const l = http.get(`${BASE}/api/approvals/tickets?status=OPEN&size=20`, hdr(tok));
  check(l, { 'queue 200': (x) => x.status === 200 });
  listLatency.add(l.timings.duration);
  const open = l.status === 200 ? l.json() : [];
  if (open.length > 0) {
    const t = open[Math.floor(Math.random() * open.length)];
    const start = Date.now();
    const c = http.post(`${BASE}/api/approvals/tickets/${t.id}/claim`, null, hdr(tok));
    if (c.status === 409) { conflicts.add(1); }
    if (c.status === 200) {
      const d = http.post(`${BASE}/api/approvals/tickets/${t.id}/decision`,
        JSON.stringify({ decision: 'APPROVE', comment: 'approved by k6' }), hdr(tok));
      check(d, { 'decided 200': (x) => x.status === 200 });
      decideLatency.add(Date.now() - start);
    }
  }
  sleep(1);
}
