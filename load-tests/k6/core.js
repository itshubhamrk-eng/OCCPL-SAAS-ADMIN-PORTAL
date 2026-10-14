// k6 load test for the Core Platform (BACKLOG H-3). Prepare data and tokens with load-tests/setup.py.
//   k6 run -e RATE=1 load-tests/k6/core.js
// RATE scales every scenario's request rate (RATE=1 ≈ 105 requests/s in total); DURATION per run.
// Thresholds are the provisional SLOs from PROJECT.md §2 (decision D-6).
import http from 'k6/http';
import { check } from 'k6';
import { SharedArray } from 'k6/data';

const CORE = __ENV.CORE_URL || 'http://localhost:8080';
const KC = __ENV.KC_URL || 'http://localhost:8180';
const RATE = Number(__ENV.RATE || 1);
const DURATION = __ENV.DURATION || '3m';
const data = JSON.parse(open('../tokens.json'));
const users = new SharedArray('users', () => data.users);
const admins = new SharedArray('admins', () => data.users.filter((u) => u.role === 'ADMIN'));
const largest = new SharedArray('largest', () => data.users.filter((u) => u.role === 'ADMIN').slice(0, 20));

function scenario(exec, rate, vus) {
  return {
    executor: 'constant-arrival-rate',
    exec,
    rate: Math.max(1, Math.round(rate * RATE)),
    timeUnit: '1s',
    duration: DURATION,
    preAllocatedVUs: Math.ceil(vus * RATE),
    maxVUs: Math.ceil(vus * RATE * 4),
  };
}

export const options = {
  discardResponseBodies: false,
  scenarios: {
    me: scenario('me', 20, 10),
    company_reads: scenario('companyReads', 25, 15),
    member_search: scenario('memberSearch', 4, 6),
    entitlement_check: scenario('entitlementCheck', 40, 15),
    usage_consume: scenario('usageConsume', 10, 8),
    admin_writes: scenario('adminWrites', 3, 4),
    platform_lists: scenario('platformLists', 3, 4),
  },
  thresholds: {
    'http_req_failed{kind:read}': ['rate<0.001'],
    'http_req_failed{kind:entitlement}': ['rate<0.001'],
    'http_req_failed{kind:write}': ['rate<0.001'],
    'http_req_failed{kind:search}': ['rate<0.001'],
    'http_req_failed{kind:platform}': ['rate<0.001'],
    'http_req_duration{kind:read}': ['p(95)<300'],
    'http_req_duration{kind:entitlement}': ['p(95)<50'],
    'http_req_duration{kind:write}': ['p(95)<800'],
    'http_req_duration{kind:platform}': ['p(95)<800'],
    'http_req_duration{name:member_search}': ['p(95)<300'],
  },
  summaryTrendStats: ['avg', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

const pick = (list) => list[Math.floor(Math.random() * list.length)];
const auth = (token) => ({ headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' } });

function get(url, token, name, kind) {
  const r = http.get(url, Object.assign(auth(token), { tags: { name, kind } }));
  check(r, { [`${name} 200`]: (x) => x.status === 200 });
  return r;
}

export function me() {
  get(`${CORE}/api/v1/me`, pick(users).token, 'me', 'read');
}

export function companyReads() {
  const u = pick(users);
  const c = `${CORE}/api/v1/companies/${u.companyId}`;
  switch (Math.floor(Math.random() * 4)) {
    case 0: return get(c, u.token, 'company', 'read');
    case 1: return get(`${c}/entitlements`, u.token, 'entitlements', 'read');
    case 2: return get(`${c}/products`, u.token, 'products', 'read');
    default: {
      const a = pick(admins); // member list needs company.users.read
      return get(`${CORE}/api/v1/companies/${a.companyId}/memberships?size=20&page=${Math.floor(Math.random() * 5)}`,
        a.token, 'memberships', 'read');
    }
  }
}

export function memberSearch() {
  const a = pick(largest);
  const q = `u${Math.floor(Math.random() * 900) + 100}`;
  get(`${CORE}/api/v1/companies/${a.companyId}/memberships?q=${q}&size=20`, a.token, 'member_search', 'search');
}

// Service token per VU, renewed before it expires (client credentials of the product service).
let serviceToken = null;
let serviceTokenUntil = 0;
function service() {
  if (!serviceToken || Date.now() > serviceTokenUntil) {
    const r = http.post(`${KC}/realms/occpl/protocol/openid-connect/token`, {
      grant_type: 'client_credentials', client_id: data.serviceClientId, client_secret: data.serviceClientSecret,
    }, { tags: { name: 'service_token', kind: 'auth' } });
    check(r, { 'service token': (x) => x.status === 200 });
    serviceToken = r.json('access_token');
    serviceTokenUntil = Date.now() + (r.json('expires_in') - 30) * 1000;
  }
  return serviceToken;
}

export function entitlementCheck() {
  const u = pick(users);
  const feature = Math.random() < 0.5 ? '&featureCode=export' : '';
  const r = http.get(`${CORE}/api/v1/service/access?companyId=${u.companyId}&userSubject=${u.subject}${feature}`,
    Object.assign(auth(service()), { tags: { name: 'access_check', kind: 'entitlement' } }));
  check(r, { 'access allowed': (x) => x.status === 200 && x.json('allowed') === true });
}

export function usageConsume() {
  const u = pick(users);
  const r = http.post(`${CORE}/api/v1/service/usage/consume`, JSON.stringify({
    companyId: u.companyId, featureCode: 'api-calls', quantity: 1, idempotencyKey: `lt-${__VU}-${__ITER}-${Date.now()}-${Math.floor(Math.random() * 1e9)}`,
  }), Object.assign(auth(service()), { tags: { name: 'usage_consume', kind: 'write' } }));
  check(r, { 'consume 200': (x) => x.status === 200 });
}

export function adminWrites() {
  const a = pick(admins);
  const r = http.put(`${CORE}/api/v1/companies/${a.companyId}/contacts/TECHNICAL`, JSON.stringify({
    name: `Tech ${Math.floor(Math.random() * 1e6)}`, email: `tech-${Math.floor(Math.random() * 1e6)}@lt.loadtest`,
  }), Object.assign(auth(a.token), { tags: { name: 'contact_upsert', kind: 'write' } }));
  check(r, { 'contact 200': (x) => x.status === 200 });
}

export function platformLists() {
  const t = data.staff;
  switch (Math.floor(Math.random() * 4)) {
    case 0: return get(`${CORE}/api/v1/companies?size=20&page=${Math.floor(Math.random() * 50)}`, t, 'platform_companies', 'platform');
    case 1: return get(`${CORE}/api/v1/companies?q=Company%20${Math.floor(Math.random() * 9999) + 1}&size=20`, t, 'platform_company_search', 'platform');
    case 2: return get(`${CORE}/api/v1/platform/subscriptions?size=20`, t, 'platform_subscriptions', 'platform');
    default: return get(`${CORE}/api/v1/audit-logs?size=20`, t, 'platform_audit', 'platform');
  }
}
