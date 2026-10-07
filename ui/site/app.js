'use strict';
// Minimal OIDC (authorization code + PKCE) client and ticket UI. No dependencies.
// Tokens live only in memory; the tenant is a selector the server re-validates.

const AUTH = '/auth/realms/ticketing/protocol/openid-connect';
const CLIENT_ID = 'ticketing-ui';
const REDIRECT = location.origin + '/';
const PAGE_SIZE = 50;
const REFRESH_MARGIN_MS = 30_000;   // refresh the access token this long before it expires

let accessToken = null;
let refreshToken = null;
let idToken = null;
let expiresAt = 0;
let refreshing = null;  // in-flight refresh, shared by concurrent API calls
let me = null;          // { email, tenants: { acme: ['APPLICANT'], ... } }
let tenant = null;      // active tenant id
let renderSeq = 0;      // only the latest renderApp() may put its result on screen

const $ = (id) => document.getElementById(id);

// ---------- tiny DOM helper (always textContent: ticket text can never inject markup) ----------
function h(tag, attrs = {}, ...kids) {
  const el = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs)) {
    if (k === 'class') el.className = v;
    else if (k.startsWith('on')) el.addEventListener(k.slice(2), v);
    else el.setAttribute(k, v);
  }
  for (const kid of kids.flat()) {
    if (kid != null) el.append(kid.nodeType ? kid : document.createTextNode(String(kid)));
  }
  return el;
}

let errorTimer = null;
function showError(e) {
  const box = $('error');
  box.textContent = e instanceof Error ? e.message : String(e);
  box.classList.add('show');
  clearTimeout(errorTimer);
  errorTimer = setTimeout(() => box.classList.remove('show'), 6000);
}

// ---------- drafts: unsent text survives a forced re-login (sessionStorage, this tab only) ----------
const draftFields = new Map();   // key -> input/textarea currently on screen
let restoredDrafts = {};

function draft(key, el) {
  draftFields.set(key, el);
  if (restoredDrafts[key]) {
    el.value = restoredDrafts[key];
    delete restoredDrafts[key];
  }
  return el;
}

function saveDrafts() {
  const fields = {};
  for (const [key, el] of draftFields) if (el.isConnected && el.value) fields[key] = el.value;
  try { sessionStorage.setItem('drafts', JSON.stringify({ tenant, fields })); } catch { /* storage unavailable */ }
}

function loadDrafts() {
  let saved = null;
  try {
    saved = JSON.parse(sessionStorage.getItem('drafts') || 'null');
    sessionStorage.removeItem('drafts');
  } catch { /* storage unavailable */ }
  restoredDrafts = (saved && saved.fields) || {};
  return saved && saved.tenant;
}

// ---------- OIDC / PKCE ----------
const b64url = (buf) => btoa(String.fromCharCode(...new Uint8Array(buf)))
  .replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
const rand = (n) => b64url(crypto.getRandomValues(new Uint8Array(n)));

async function login(idpHint) {
  const verifier = rand(48);
  const state = rand(16);
  sessionStorage.setItem('pkce', JSON.stringify({ verifier, state }));
  const challenge = b64url(await crypto.subtle.digest('SHA-256', new TextEncoder().encode(verifier)));
  const p = new URLSearchParams({
    client_id: CLIENT_ID, redirect_uri: REDIRECT, response_type: 'code', scope: 'openid email profile',
    state, code_challenge: challenge, code_challenge_method: 'S256',
  });
  if (idpHint) p.set('kc_idp_hint', idpHint);
  location.href = `${AUTH}/auth?${p}`;
}

function storeTokens(t) {
  accessToken = t.access_token;
  refreshToken = t.refresh_token || null;
  idToken = t.id_token || idToken;
  expiresAt = Date.now() + (t.expires_in || 0) * 1000;
}

async function tokenRequest(params) {
  const r = await fetch(`${AUTH}/token`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({ client_id: CLIENT_ID, ...params }),
  });
  if (!r.ok) return false;
  storeTokens(await r.json());
  return true;
}

async function completeLoginIfCallback() {
  const q = new URLSearchParams(location.search);
  if (!q.get('code')) return;
  const saved = JSON.parse(sessionStorage.getItem('pkce') || 'null');
  sessionStorage.removeItem('pkce');
  history.replaceState({}, '', REDIRECT);
  if (!saved || saved.state !== q.get('state')) throw new Error('Login state mismatch, please sign in again');
  const ok = await tokenRequest({
    grant_type: 'authorization_code', code: q.get('code'), redirect_uri: REDIRECT, code_verifier: saved.verifier,
  });
  if (!ok) throw new Error('Sign-in failed');
}

/** Short-lived access tokens are renewed with the refresh token instead of a full redirect. */
function refreshTokens() {
  if (!refreshToken) return Promise.resolve(false);
  refreshing ??= tokenRequest({ grant_type: 'refresh_token', refresh_token: refreshToken })
    .catch(() => false)
    .finally(() => { refreshing = null; });
  return refreshing;
}

function logout() {
  const p = new URLSearchParams({ client_id: CLIENT_ID, post_logout_redirect_uri: REDIRECT });
  if (idToken) p.set('id_token_hint', idToken);
  location.href = `${AUTH}/logout?${p}`;
}

// ---------- API ----------
async function api(path, { method = 'GET', body } = {}) {
  if (Date.now() > expiresAt - REFRESH_MARGIN_MS) await refreshTokens();
  const send = () => {
    const headers = { Authorization: `Bearer ${accessToken}` };
    if (tenant) headers['X-Tenant-ID'] = tenant;
    if (body) headers['Content-Type'] = 'application/json';
    return fetch(`/api${path}`, { method, headers, body: body ? JSON.stringify(body) : undefined });
  };
  let r = await send();
  if (r.status === 401 && await refreshTokens()) r = await send();
  if (r.status === 401) {
    saveDrafts();   // the SSO session is gone: keep what the user typed across the sign-in redirect
    login();
    throw new Error('Session expired, signing in again...');
  }
  const data = await r.json().catch(() => null);
  if (!r.ok) throw new Error((data && data.detail) || `${r.status} ${r.statusText}`);
  return data;
}

/** A list that loads PAGE_SIZE items at a time; reload() re-fetches every page shown so far. */
function pagedList(fetchPage, renderItem, emptyText) {
  const el = h('div');
  let pages = 1;
  async function reload() {
    const items = [];
    let last = [];
    for (let p = 0; p < pages; p++) {
      last = await fetchPage(p, PAGE_SIZE);
      items.push(...last);
      if (last.length < PAGE_SIZE) break;
    }
    const more = last.length === PAGE_SIZE
      ? h('div', { class: 'row' }, h('button', { class: 'ghost', onclick: () => { pages++; reload().catch(showError); } }, 'Load more'))
      : null;
    el.replaceChildren(...(items.length ? items.map(renderItem) : [h('p', { class: 'meta' }, emptyText)]), ...(more ? [more] : []));
  }
  return { el, reload, reset: () => { pages = 1; } };
}

/**
 * A list shown one numbered page at a time (Previous / Next). The API returns plain lists without a
 * total, so "is there a next page?" is answered by asking for the single item just after this page.
 */
function pagerList(fetchPage, renderItem, emptyText, sizes = [10, 20, 50]) {
  const el = h('div');
  const items = h('div');
  let page = 0;
  let size = sizes[0];

  // The same controls above and below the list, so long pages need no scrolling to navigate.
  const bars = ['top', 'bottom'].map((where) => {
    const bar = {
      status: h('span', { class: 'meta', 'aria-live': where === 'top' ? 'polite' : 'off' }),
      prev: h('button', { class: 'ghost', onclick: () => go(page - 1) }, '‹ Previous'),
      next: h('button', { class: 'ghost', onclick: () => go(page + 1) }, 'Next ›'),
      size: h('select', { 'aria-label': 'Tickets per page' },
        ...sizes.map((s) => h('option', { value: String(s) }, `${s} per page`))),
    };
    bar.size.addEventListener('change', () => { size = Number(bar.size.value); go(0); });
    bar.el = h('div', { class: `pager ${where}` }, bar.prev, bar.status, bar.next, bar.size);
    return bar;
  });
  el.append(bars[0].el, items, bars[1].el);

  async function hasItemAt(offset) {   // page=offset with size=1 is exactly the item at that offset
    return (await fetchPage(offset, 1)).length > 0;
  }

  async function reload() {
    let rows = await fetchPage(page, size);
    while (!rows.length && page > 0) {   // the last item of a page went away (e.g. after a decision)
      page--;
      rows = await fetchPage(page, size);
    }
    const more = rows.length === size && await hasItemAt((page + 1) * size);
    items.replaceChildren(...(rows.length ? rows.map(renderItem) : [h('p', { class: 'meta' }, emptyText)]));
    const first = page * size + 1;
    for (const bar of bars) {
      bar.status.textContent = rows.length ? `Page ${page + 1} · tickets ${first}–${first + rows.length - 1}` : '';
      bar.prev.disabled = page === 0;
      bar.next.disabled = !more;
      bar.size.value = String(size);
    }
  }

  function go(p) {
    page = Math.max(0, p);
    reload().catch(showError);
  }

  return { el, reload, reset: () => { page = 0; } };
}

// ---------- views ----------
const when = (iso) => (iso ? new Date(iso).toLocaleString() : '');

function ticketCard(t, actions) {
  return h('div', { class: 'ticket' },
    h('h3', {}, t.title, ' ', h('span', { class: `badge ${t.status}` }, t.status)),
    h('div', { class: 'meta' }, `${t.createdBy} · ${when(t.createdAt)} · mobile ${t.mobile ?? '-'}`),
    h('p', {}, t.description ?? ''),
    t.lockedBy ? h('div', { class: 'meta' }, `Locked by ${t.lockedBy} since ${when(t.lockedAt)}`) : null,
    h('ul', { class: 'events' }, (t.events || []).map((e) =>
      h('li', {}, `${when(e.at)} · ${e.type} by ${e.actor}${e.channel ? ' via ' + e.channel : ''}${e.comment ? ': ' + e.comment : ''}`))),
    actions);
}

async function renderApplicant(root) {
  const mobile = draft('ticket.mobile', h('input', { placeholder: '+919876543210', inputmode: 'tel' }));
  const title = draft('ticket.title', h('input', { maxlength: 120 }));
  const desc = draft('ticket.description', h('textarea'));

  const list = pagedList((page, size) => api(`/tickets?page=${page}&size=${size}`), (t) => {
    let actions = null;
    if (t.status === 'MORE_INFO') {
      const reply = draft(`reply.${t.id}`, h('textarea', { placeholder: 'Your answer' }));
      actions = h('div', {}, reply, h('div', { class: 'row' }, h('button', { onclick: async () => {
        try { await api(`/tickets/${t.id}/respond`, { method: 'POST', body: { comment: reply.value } }); await list.reload(); }
        catch (e) { showError(e); }
      } }, 'Send details')));
    }
    return ticketCard(t, actions);
  }, 'You have not raised any tickets in this tenant.');

  const submit = h('button', { onclick: async () => {
    try {
      await api('/tickets', { method: 'POST', body: { title: title.value, mobile: mobile.value, description: desc.value } });
      title.value = mobile.value = desc.value = '';
      list.reset();
      await list.reload();
    } catch (e) { showError(e); }
  } }, 'Raise ticket');

  root.append(
    h('section', {}, h('h2', {}, 'Raise a ticket'),
      h('label', {}, 'Title'), title, h('label', {}, 'Mobile number'), mobile,
      h('label', {}, 'Description'), desc, h('div', { class: 'row' }, submit),
      h('p', { class: 'meta' }, 'Your email and tenant are taken from your sign-in, not from this form.')),
    h('section', {}, h('h2', {}, 'My tickets'), list.el));
  await list.reload();
  return list;
}

async function renderApprover(root) {
  const filter = h('select', {},
    h('option', { value: '' }, 'All statuses'),
    ...['OPEN', 'LOCKED', 'MORE_INFO', 'APPROVED', 'REJECTED'].map((s) => h('option', { value: s }, s)));

  async function act(id, action, body) {
    try { await api(`/approvals/tickets/${id}/${action}`, { method: 'POST', body }); }
    catch (e) { showError(e); }
    await list.reload().catch(showError);
  }

  const list = pagerList((page, size) => api(`/approvals/tickets?page=${page}&size=${size}`
      + (filter.value ? `&status=${encodeURIComponent(filter.value)}` : '')), (t) => {
    // Separation of duties (also enforced by the server): no approver actions on your own ticket.
    if (t.createdBy === me.email) {
      return ticketCard(t, h('p', { class: 'meta' }, 'You raised this ticket, so another approver must handle it.'));
    }
    const buttons = [];
    if (t.status === 'OPEN') buttons.push(h('button', { onclick: () => act(t.id, 'claim') }, 'Pick up'));
    if (t.status === 'LOCKED') buttons.push(h('button', { class: 'warn', onclick: () => act(t.id, 'unlock') }, 'Unlock'));
    let decision = null;
    if (t.status === 'LOCKED' && t.lockedBy === me.email) {
      const comment = draft(`comment.${t.id}`, h('textarea', { placeholder: 'Comment (required)' }));
      const decide = (d) => act(t.id, 'decision', { decision: d, comment: comment.value });
      decision = h('div', {}, comment, h('div', { class: 'row' },
        h('button', { class: 'ok', onclick: () => decide('APPROVE') }, 'Approve'),
        h('button', { class: 'bad', onclick: () => decide('REJECT') }, 'Reject'),
        h('button', { class: 'ghost', onclick: () => decide('REQUEST_INFO') }, 'Request more details')));
    }
    return ticketCard(t, h('div', {}, h('div', { class: 'row' }, buttons), decision));
  }, 'No tickets match this filter.');

  filter.addEventListener('change', () => { list.reset(); list.reload().catch(showError); });
  root.append(h('section', {}, h('h2', {}, 'Approval queue'),
    h('label', {}, 'Status'), filter, list.el));
  await list.reload();
  return list;
}

// ---------- tabs ----------
const TABS = [
  { id: 'raise', label: 'Raise ticket', role: 'APPLICANT', render: renderApplicant,
    noRole: 'You are not an applicant in this tenant, so you cannot raise tickets here.' },
  { id: 'approve', label: 'Approvals', role: 'APPROVER', render: renderApprover,
    noRole: 'You are not an approver in this tenant, so there is no approval queue for you here.' },
];

function savedTab() {
  try { return localStorage.getItem('tab'); } catch { return null; }
}

/** Builds the view off-screen; a render started earlier (e.g. before a tenant switch) is discarded. */
async function renderApp() {
  const seq = ++renderSeq;
  const roles = (me.tenants[tenant] || []);
  if (!roles.length) {
    $('app').replaceChildren(h('section', {}, 'You have no role in this tenant.'));
    return;
  }

  const tabBar = h('div', { class: 'tabs', role: 'tablist', 'aria-label': 'Views' });
  const panels = {};
  const buttons = {};
  const reloaders = {};
  const allowed = TABS.filter((t) => roles.includes(t.role)).map((t) => t.id);
  let active = allowed.includes(savedTab()) ? savedTab() : allowed[0];

  function select(id, focus = false, reload = true) {
    active = id;
    try { localStorage.setItem('tab', id); } catch { /* storage unavailable */ }
    for (const t of TABS) {
      const on = t.id === id;
      buttons[t.id].setAttribute('aria-selected', String(on));
      buttons[t.id].tabIndex = on ? 0 : -1;
      panels[t.id].hidden = !on;
    }
    if (focus) buttons[id].focus();
    if (reload && reloaders[id]) reloaders[id]().catch(showError);   // fresh data each time a tab is opened
  }

  for (const t of TABS) {
    buttons[t.id] = h('button', {
      class: 'tab', role: 'tab', id: `tab-${t.id}`, 'aria-controls': `panel-${t.id}`,
      onclick: () => select(t.id),
      onkeydown: (e) => {   // arrow keys move between tabs (WAI-ARIA tabs pattern)
        if (e.key !== 'ArrowLeft' && e.key !== 'ArrowRight') return;
        const i = TABS.findIndex((x) => x.id === t.id);
        select(TABS[(i + (e.key === 'ArrowRight' ? 1 : TABS.length - 1)) % TABS.length].id, true);
      },
    }, t.label, roles.includes(t.role) ? null : h('span', { class: 'meta' }, ' (no access)'));
    panels[t.id] = h('div', { class: 'panel', role: 'tabpanel', id: `panel-${t.id}`, 'aria-labelledby': `tab-${t.id}` });
    tabBar.append(buttons[t.id]);
  }

  const view = document.createDocumentFragment();
  view.append(tabBar, ...TABS.map((t) => panels[t.id]));
  try {
    for (const t of TABS) {
      if (!roles.includes(t.role)) {
        panels[t.id].append(h('section', {}, h('p', { class: 'meta' }, t.noRole)));
        continue;
      }
      const list = await t.render(panels[t.id]);
      if (list) reloaders[t.id] = list.reload;
    }
  } catch (e) {
    if (seq === renderSeq) showError(e);
  }
  if (seq !== renderSeq) return;
  select(active, false, false);   // just loaded; no need to fetch it twice
  $('app').replaceChildren(view);
}

async function start() {
  $('login').addEventListener('click', () => login());
  $('login-google').addEventListener('click', () => login('google'));
  $('logout').addEventListener('click', logout);
  try {
    await completeLoginIfCallback();
  } catch (e) { showError(e); }

  if (!accessToken) { $('welcome').hidden = false; return; }
  const draftTenant = loadDrafts();

  try {
    me = await api('/me');
    const tenants = Object.keys(me.tenants);
    $('who').textContent = me.email;
    if (!tenants.length) {
      $('bar').hidden = false;
      $('app').hidden = false;
      $('app').append(h('section', {}, 'Your account is not assigned to any tenant yet. Ask an administrator to add you.'));
      return;
    }
    tenant = tenants.includes(draftTenant) ? draftTenant : tenants[0];
    const select = $('tenant');
    select.replaceChildren(...tenants.map((t) => h('option', { value: t }, `${t} (${me.tenants[t].join(', ').toLowerCase()})`)));
    select.value = tenant;
    select.addEventListener('change', () => { tenant = select.value; renderApp(); });
    $('bar').hidden = false;
    $('app').hidden = false;
    await renderApp();
  } catch (e) { showError(e); }
}

start();
