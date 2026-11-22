// Page switching + real login (talks to WebServer.java).
const NAV = {
  pub:   [['home','Home'],['how','How it works'],['about','About'],['login-admin','Admin Log In']],
  donor: [['donor-home','Dashboard'],['donor-browse','Browse Requests'],['donor-match','Find a Match'],['donor-donations','My Donations']],
  org:   [['org-home','Dashboard'],['org-requests','My Requests'],['org-confirm','Pending Confirmation']],
  admin: [['admin-home','Dashboard'],['admin-verify','Verifications'],['admin-accounts','Accounts']]
};
const LOGO = '<a class="brand" href="#/home"><img data-img="/images/Logo-top" alt="Damayan"></a>';
const NEED = { donor: 'DONOR', org: 'ORGANIZATION', admin: 'ADMIN' };               // which account a section needs
const LOGIN_PAGE = { DONOR: 'login-donor', ORGANIZATION: 'login-org', ADMIN: 'login-admin' };
const LOGIN_VIEW = { 'login-donor': 'DONOR', 'login-org': 'ORGANIZATION', 'login-admin': 'ADMIN' };

let ME = null;   // the logged-in user sent by the server, or null

const esc = s => String(s).replace(/[&<>"']/g, c => ({ '&':'&amp;', '<':'&lt;', '>':'&gt;', '"':'&quot;', "'":'&#39;' }[c]));
const link = ([id, t], cur) => `<a href="#/${id}" class="${id === cur ? 'on' : ''}">${t}</a>`;

async function api(path, data) {
  const res = await fetch(path, data ? { method: 'POST', body: new URLSearchParams(data) } : {});
  return res.json();
}

// Where each kind of user lands after logging in
function homeFor(u) {
  if (u.role === 'ADMIN') return 'admin-home';
  if (u.role === 'DONOR') return 'donor-home';
  return u.status === 'VERIFIED' ? 'org-home' : 'org-verify';
}

// Images are written as data-img="/images/name" (no extension). We try each extension until one loads.
const EXTS = ['png', 'jpg', 'jpeg', 'webp', 'svg'];
function loadImgs() {
  document.querySelectorAll('img[data-img]').forEach(img => {
    if (img.dataset.done) return;
    img.dataset.done = '1';
    let i = 0;
    img.onerror = () => {
      i++;
      if (i < EXTS.length) img.src = `${img.dataset.img}.${EXTS[i]}`;
      else img.onerror = null;
    };
    img.src = `${img.dataset.img}.${EXTS[0]}`;
  });
}

function go(name) { location.hash = '#/' + name; }

function render() {
  const [name, query] = (location.hash.replace('#/', '') || 'home').split('?');
  reviewId = new URLSearchParams(query || '').get('id');
  const view = document.querySelector(`.view[data-v="${name}"]`) || document.querySelector('.view[data-v="home"]');
  const role = view.dataset.role;           // pub | auth | donor | org | admin

  // Guards: dashboards need the right login
  const need = NEED[role];
  if (role === 'shared') {                                   // Request Details: donors and organizations
    if (!ME) return go('login-donor');
    if (ME.role === 'ADMIN') return go('admin-home');
    if (ME.role === 'ORGANIZATION' && ME.status !== 'VERIFIED') return go('org-verify');
  }
  if (need) {
    if (!ME || ME.role !== need) return go(LOGIN_PAGE[need]);
    if (need === 'ORGANIZATION') {
      const verified = ME.status === 'VERIFIED';
      if (!verified && view.dataset.v !== 'org-verify') return go('org-verify');
      if (verified && view.dataset.v === 'org-verify') return go('org-home');
    }
  }
  // Already logged in? Skip the login pages
  if (ME && LOGIN_VIEW[name]) return go(homeFor(ME));

  document.querySelectorAll('.view').forEach(v => v.classList.toggle('on', v === view));

  const navRole = role === 'shared' ? (ME.role === 'ORGANIZATION' ? 'org' : 'donor') : role;
  let cur = view.dataset.nav || view.dataset.v;
  if (role === 'shared') cur = ME.role === 'ORGANIZATION' ? 'org-requests' : 'donor-donations';
  let right = '';
  if (role === 'auth') right = '<a href="#/">Back to home</a>';
  else if (role === 'pub') right = `<nav>${NAV.pub.map(l => link(l, cur)).join('')}</nav>`;
  else right = `<nav>${NAV[navRole].map(l => link(l, cur)).join('')}</nav>
    <div class="who">Logged in as: ${esc(ME.name)} <a class="btn sm" href="#/home" data-logout>Log Out</a></div>`;

  document.getElementById('top').innerHTML = LOGO + right;
  document.getElementById('foot').hidden = role !== 'pub';

  if (ME) fillPage(view);
  if (role === 'admin') loadAdmin(view);
  loadPortal(view);
  loadImgs();
  window.scrollTo(0, 0);
}

// Put the real name / status into the page
function fillPage(view) {
  view.querySelectorAll('[data-bind="orgName"],[data-bind="donorName"]').forEach(n => n.textContent = ME.name);
  if (view.dataset.v === 'org-verify') {
    const rejected = ME.status === 'REJECTED';
    const cards = view.querySelectorAll('.g2 > .card');       // 1st = pending, 2nd = rejected
    if (cards.length === 2) { cards[0].hidden = rejected; cards[1].hidden = !rejected; }
    view.querySelectorAll('[data-bind="reason"]').forEach(n => n.textContent = ME.reason || '');
    // timeline dates: only the submit date is known; the other steps say where things stand
    view.querySelectorAll('[data-bind="submitted"]').forEach(n => n.textContent = ME.submitted || '-');
    view.querySelectorAll('.tl').forEach((tl, i) => {
      const spans = tl.querySelectorAll('li > span');
      if (spans.length === 3) {
        spans[0].textContent = ME.submitted || '-';
        spans[1].textContent = i === 0 ? 'In progress' : 'Done';
        spans[2].textContent = i === 0 ? 'Not yet' : 'Not approved';
      }
    });
  }
}

function showError(form, message) {
  let box = form.querySelector('.err');
  if (!box) {
    box = document.createElement('p');
    box.className = 'err';
    form.querySelector('button:not([type=button])').before(box);
  }
  box.textContent = message;
  box.hidden = !message;
}

// ----- clicks: filter pills + log out -----
document.addEventListener('click', async e => {
  const pill = e.target.closest('.pill');
  if (pill) {
    pill.parentElement.querySelectorAll('.pill').forEach(x => x.classList.remove('on'));
    pill.classList.add('on');
  }
  if (e.target.closest('[data-logout]')) {
    e.preventDefault();
    try { await api('/api/logout', {}); } catch (err) { /* ignore */ }
    ME = null;
    go('home');
    render();
  }
});

// ----- sign up (donor + organization) -----
const SIGNUP_PATH = { 'signup-donor': '/api/signup-donor', 'signup-org': '/api/signup-org' };
const MAX_PROOF = 5 * 1024 * 1024;

const readBase64 = file => new Promise((resolve, reject) => {
  const r = new FileReader();
  r.onload = () => resolve(String(r.result).split(',')[1] || '');
  r.onerror = () => reject(new Error('Could not read the file.'));
  r.readAsDataURL(file);
});

async function signUp(form, viewName) {
  const val = id => form.querySelector('#' + id).value;
  const button = form.querySelector('button:not([type=button])');
  showError(form, '');
  button.disabled = true;
  try {
    let data;
    if (viewName === 'signup-org') {
      const file = form.querySelector('#so-f').files[0];
      if (!file) { showError(form, 'Please upload a proof document.'); return; }
      if (file.size > MAX_PROOF) { showError(form, 'The proof file is bigger than 5 MB.'); return; }
      data = { name: val('so-n'), type: val('so-t'), email: val('so-e'),
               password: val('so-p'), confirm: val('so-c'), proofName: file.name, proof: await readBase64(file) };
    } else {
      data = { name: val('sd-n'), type: val('sd-t'), email: val('sd-e'),
               password: val('sd-p'), confirm: val('sd-c') };
    }
    const res = await api(SIGNUP_PATH[viewName], data);
    if (!res.ok) { showError(form, res.error); return; }
    ME = res;                       // the new account is logged in already
    form.reset();
    setProofName();
    go(homeFor(ME));                // donor -> donor dashboard, organization -> "Account Verification"
  } catch (err) {
    showError(form, err.message && !/fetch|JSON/i.test(err.message) ? err.message
      : 'Cannot reach the server. Run the Java program, then open http://localhost:8080');
  } finally {
    button.disabled = false;
  }
}

// ----- forms: the three log in forms talk to the server, the rest just don't reload the page -----
document.addEventListener('submit', async e => {
  e.preventDefault();
  const form = e.target;
  const viewName = form.closest('.view')?.dataset.v;
  if (SIGNUP_PATH[viewName]) return signUp(form, viewName);     // the two sign up forms
  if (form.id === 'post-form') return postRequest(form);       // organization: Post a Request
  const role = LOGIN_VIEW[viewName];
  if (!role) return;                                           // other forms: TODO later

  const button = form.querySelector('button:not([type=button])');
  showError(form, '');
  button.disabled = true;
  try {
    const data = await api('/api/login', {
      email: form.querySelector('input[type=email]').value,
      password: form.querySelector('input[data-pw]').value,
      role
    });
    if (!data.ok) { showError(form, data.error); return; }
    ME = data;
    go(homeFor(ME));
  } catch (err) {
    showError(form, 'Cannot reach the server. Run the Java program, then open http://localhost:8080');
  } finally {
    button.disabled = false;
  }
});

// ----- admin tables: chips, dropdown, Filter button, search -----
function filterRows(view) {
  const f = view.querySelector('.pill.on')?.dataset.f || 'all';
  const type = view.querySelector('select')?.value || '';
  const q = (view.querySelector('input.search')?.value || '').toLowerCase().trim();
  const isVerify = view.dataset.v === 'admin-verify';
  let shown = 0;
  view.querySelectorAll('tbody tr[data-status]').forEach(tr => {
    const d = tr.dataset;
    const ok = isVerify
      ? (f === 'all' || d.status === f) && (!type || d.type === type)
      : (f === 'all' || (f === 'org' && d.kind === 'org') || (f === 'donor' && d.kind === 'donor') || (f === 'suspended' && d.status === 'suspended'))
        && (!q || tr.textContent.toLowerCase().includes(q));
    tr.hidden = !ok;
    if (ok) shown++;
  });
  const none = view.querySelector('tr.none');
  if (none) none.hidden = shown > 0;
}

// ----- admin pages: real data from the server -----
let reviewId = null;
const setText = (id, v) => { const el = document.getElementById(id); if (el) el.textContent = v; };
const STATUS = { PENDING: ['pending', 'pen', 'Pending'], VERIFIED: ['approved', '', 'Approved'], REJECTED: ['rejected', 'rej', 'Rejected'] };
const pill = st => `<span class="b ${STATUS[st][1]}">${STATUS[st][2]}</span>`;
const none = (cols, text) => `<tr class="none"><td colspan="${cols}">${text}</td></tr>`;

async function loadAdmin(view) {
  try {
    const v = view.dataset.v;
    if (v === 'admin-home') await loadDashboard();
    else if (v === 'admin-verify') { await loadVerifications(); filterRows(view); }
    else if (v === 'admin-accounts') { await loadAccounts(); filterRows(view); }
    else if (v === 'admin-review') await loadReview();
  } catch (err) { /* server not reachable: leave the page as it is */ }
}

async function loadDashboard() {
  const [s, o] = await Promise.all([api('/api/admin/summary'), api('/api/admin/organizations')]);
  if (!s.ok || !o.ok) return;
  setText('dash-pending', s.pending); setText('dash-approved', s.verified); setText('dash-rejected', s.rejected);
  setText('ov-waiting', s.waiting); setText('ov-donated', s.donated); setText('ov-delivered', s.delivered); setText('ov-received', s.received);
  const pending = o.items.filter(x => x.status === 'PENDING');
  document.getElementById('dash-review').innerHTML = pending.length
    ? pending.slice(0, 8).map(x => `<tr><td>${esc(x.name)}</td><td>${esc(x.orgType)}</td><td>${esc(x.date)}</td><td><a class="btn solid" href="#/admin-review?id=${esc(x.id)}">Review</a></td></tr>`).join('')
    : none(4, 'Nothing is waiting for review.');
}

async function loadVerifications() {
  const o = await api('/api/admin/organizations');
  if (!o.ok) return;
  document.getElementById('vf-body').innerHTML = o.items.map(x => {
    const [key] = STATUS[x.status];
    return `<tr data-status="${key}" data-type="${esc(x.orgType)}"><td>${esc(x.name)}</td><td>${esc(x.orgType)}</td><td>${esc(x.proof)}</td><td>${esc(x.date)}</td><td>${pill(x.status)}</td>`
      + `<td><a class="btn${x.status === 'PENDING' ? ' solid' : ''}" href="#/admin-review?id=${esc(x.id)}">${x.status === 'PENDING' ? 'Review' : 'View'}</a></td></tr>`;
  }).join('') + none(6, 'No verifications match.').replace('<tr class="none">', '<tr class="none" hidden>');
}

async function loadAccounts() {
  const a = await api('/api/admin/accounts');
  if (!a.ok) return;
  const kind = { ORGANIZATION: 'org', DONOR: 'donor', ADMIN: 'admin' };
  const label = { ORGANIZATION: 'Organization', DONOR: 'Donor', ADMIN: 'Admin' };
  document.getElementById('ac-body').innerHTML = a.items.map(x => {
    const view = x.role === 'ORGANIZATION' ? `<a class="btn" href="#/admin-review?id=${esc(x.id)}">View</a> ` : '';
    const act = x.role === 'ADMIN' ? '' : `<button class="btn" type="button" data-suspend="${esc(x.id)}" data-act="${x.suspended ? 'reinstate' : 'suspend'}">${x.suspended ? 'Unsuspend' : 'Suspend'}</button>`;
    return `<tr data-kind="${kind[x.role]}" data-status="${x.suspended ? 'suspended' : 'active'}"><td>${esc(x.name)}</td><td><span class="tag">${label[x.role]}</span></td>`
      + `<td>${esc(x.email)}</td><td>${esc(x.date)}</td><td>${x.suspended ? '<span class="b sus">Suspended</span>' : '<span class="b">Active</span>'}</td><td>${view}${act}</td></tr>`;
  }).join('') + none(6, 'No accounts match.').replace('<tr class="none">', '<tr class="none" hidden>');
}

async function loadReview() {
  const o = await api('/api/admin/organizations');
  const x = o.ok && o.items.find(i => i.id === reviewId);
  setText('rv-name', x ? x.name : 'Organization not found');
  if (!x) return;
  document.getElementById('rv-status').innerHTML = pill(x.status);
  document.getElementById('rv-meta').innerHTML = `Email: ${esc(x.email)}<br>Org Type: ${esc(x.orgType)}<br>Date Submitted: ${esc(x.date)}<br>Proof file: ${esc(x.proof)}`;
  const url = `/api/admin/proof?id=${encodeURIComponent(x.id)}`;
  const isPdf = /\.pdf$/i.test(x.proof);
  document.getElementById('rv-proof').innerHTML = isPdf
    ? `<iframe src="${url}" title="Submitted proof" style="width:100%;height:340px;border:0"></iframe>`
    : `<img src="${url}" alt="Submitted proof" style="max-width:100%;max-height:340px;border-radius:12px">`;
  document.getElementById('rv-open').href = url;
  const pending = x.status === 'PENDING';
  document.getElementById('rv-decision').hidden = !pending;
  document.getElementById('rv-done').hidden = pending;
  document.getElementById('rv-err').hidden = true;
  document.getElementById('r-r').value = '';
  setText('rv-done-text', pending ? '' : `This organization is already ${STATUS[x.status][2].toLowerCase()}.` + (x.reason ? ` Reason: ${x.reason}` : ''));
}

async function decide(action) {
  const err = document.getElementById('rv-err');
  err.hidden = true;
  const reason = document.getElementById('r-r').value.trim();
  if (action === 'reject' && !reason) { err.textContent = 'Please give a reason when rejecting.'; err.hidden = false; return; }
  try {
    const res = await api('/api/admin/review', { id: reviewId, action, reason });
    if (!res.ok) { err.textContent = res.error; err.hidden = false; return; }
    go('admin-verify');
  } catch (e) {
    err.textContent = 'Cannot reach the server.'; err.hidden = false;
  }
}

document.addEventListener('click', async e => {
  if (e.target.id === 'rv-approve') decide('approve');
  if (e.target.id === 'rv-reject') decide('reject');
  const btn = e.target.closest('[data-suspend]');
  if (btn) {
    btn.disabled = true;
    try {
      await api('/api/admin/suspend', { id: btn.dataset.suspend, action: btn.dataset.act });
      const view = document.querySelector('.view[data-v="admin-accounts"]');
      await loadAccounts(); filterRows(view);
    } catch (err) { btn.disabled = false; }
  }
});

document.addEventListener('click', e => {
  const view = e.target.closest('.view[data-v="admin-verify"], .view[data-v="admin-accounts"]');
  if (view && (e.target.closest('.pill') || e.target.closest('[data-filter]'))) filterRows(view);
});
document.addEventListener('input', e => {
  const view = e.target.closest('.view[data-v="admin-accounts"]');
  if (view && e.target.matches('input.search')) filterRows(view);
});
document.addEventListener('change', e => {
  const view = e.target.closest('.view[data-v="admin-verify"]');
  if (view && e.target.matches('select')) filterRows(view);
});

// ----- organization sign up: proof file chooser (Browse, Remove, drag and drop) -----
function setProofName() {
  const file = document.getElementById('so-f'), label = document.getElementById('so-fn');
  if (file && label) label.textContent = file.files[0] ? file.files[0].name : 'No file chosen';
}
document.addEventListener('change', e => { if (e.target.id === 'so-f') setProofName(); });
document.addEventListener('click', e => {
  if (e.target.id !== 'so-rm') return;
  document.getElementById('so-f').value = '';
  setProofName();
});
document.addEventListener('dragover', e => { if (e.target.closest('#so-drop')) e.preventDefault(); });
document.addEventListener('drop', e => {
  if (!e.target.closest('#so-drop')) return;
  e.preventDefault();
  if (e.dataTransfer.files.length) { document.getElementById('so-f').files = e.dataTransfer.files; setProofName(); }
});

// ----- Post a Request: live preview -----
function updatePreview(e) {
  if (!e.target.closest || !e.target.closest('#post-form')) return;
  const v = id => document.getElementById(id).value.trim();
  document.getElementById('pv-cat').textContent = v('p-c') || 'Category';
  document.getElementById('pv-item').textContent = [v('p-q'), v('p-u'), v('p-i')].filter(Boolean).join(' ') || 'Your request';
}
document.addEventListener('input', updatePreview);
document.addEventListener('change', updatePreview);

// ----- show / hide password -----
function addPasswordToggles() {
  document.querySelectorAll('input[type=password]').forEach(input => {
    input.dataset.pw = '1';
    const wrap = document.createElement('div');
    wrap.className = 'pw';
    input.before(wrap);
    wrap.appendChild(input);
    const btn = document.createElement('button');
    btn.type = 'button';
    btn.className = 'pw-toggle';
    btn.textContent = 'Show';
    btn.setAttribute('aria-label', 'Show password');
    wrap.appendChild(btn);
  });
}
document.addEventListener('click', e => {
  const btn = e.target.closest('.pw-toggle');
  if (!btn) return;
  const input = btn.parentElement.querySelector('input');
  const show = input.type === 'password';
  input.type = show ? 'text' : 'password';
  btn.textContent = show ? 'Hide' : 'Show';
  btn.setAttribute('aria-label', show ? 'Hide password' : 'Show password');
});
addPasswordToggles();


// ===================== donor + organization pages =====================
const CATS = ['Food', 'Clothing', 'School Supplies', 'Clean Water and Sanitation', 'Hygiene Supplies'];
const CAT_IMG = { 'Food': 'food', 'Clothing': 'clothes', 'School Supplies': 'school_supplies', 'Clean Water and Sanitation': 'clean_water', 'Hygiene Supplies': 'hygiene' };
const STATUS_TEXT = { WAITING: 'Waiting', DONATED: 'Donated', DELIVERED: 'Delivered', RECEIVED: 'Received' };
const PREF = { browse: null, match: null };       // what the donor picked on the dashboard before pressing Browse / Find a Match

const $ = id => document.getElementById(id);
const badge = (st, dark) => `<span class="${st === 'WAITING' ? 'b wait' : st === 'DONATED' ? (dark ? 'b dark' : 'b') : st === 'DELIVERED' ? 'b del' : 'b'}">${STATUS_TEXT[st]}</span>`;
const noRow = (cols, text) => `<tr class="none"><td colspan="${cols}">${text}</td></tr>`;
const days = n => n < 1 ? 'Today' : n === 1 ? '1 day' : n + ' days';
function title(r) {                                // "50 packs of rice" (does not repeat the unit if the item already starts with it)
  const item = String(r.item), unit = String(r.unit);
  return item.toLowerCase().startsWith(unit.toLowerCase()) ? `${r.quantity} ${item}` : `${r.quantity} ${unit} ${item}`;
}
const detailLink = (id, text) => `<a href="#/request-detail?id=${encodeURIComponent(id)}">${text}</a>`;

const ORG_TYPES = ['LGU', 'NGO', 'Barangay', 'Company'];
async function fillOrgSelect(id, first) {          // the Organization dropdowns list the organization types
  const sel = $(id);
  if (!sel) return;
  const keep = sel.value;
  sel.innerHTML = `<option value="">${first}</option>` + ORG_TYPES.map(t => `<option value="${t}">${t}</option>`).join('');
  if (ORG_TYPES.includes(keep)) sel.value = keep;
}

function loadPortal(view) {
  const v = view.dataset.v;
  const run = {
    'home': loadStats, 'donor-home': loadDonorHome, 'donor-browse': loadBrowse, 'donor-match': loadMatch,
    'donor-donations': loadDonations, 'org-requests': loadOrgRequests, 'org-confirm': loadConfirm, 'request-detail': loadDetail
  }[v];
  if (run) run().catch(() => {});
}

async function loadStats() {
  const d = await api('/api/stats');
  if (d.ok) setText('stat-received', d.received);
}

// ----- donor dashboard -----
let donations = [];
async function loadDonorHome() {
  fillOrgSelect('h-o', 'Organization'); fillOrgSelect('h-mo', 'Organization');
  const d = await api('/api/my');
  const rows = d.ok ? d.items : [];
  $('dh-body').innerHTML = rows.length ? rows.slice(0, 6).map(r => `<tr><td>${esc(title(r))}</td><td>${esc(r.orgName)}</td><td>${badge(r.status, true)}</td><td>${
    r.status === 'DONATED' ? `<button class="btn solid sm" type="button" data-deliver="${esc(r.id)}">Mark Delivered</button>`
    : r.status === 'DELIVERED' ? '<span class="mute">Waiting for Confirmation</span>' : '<span class="mute">DONE</span>'}</td></tr>`).join('')
    : noRow(4, 'No donations yet. Browse requests or find a match to start.');
}

// ----- Browse Requests -----
let bCat = '', bOrg = '';
async function loadBrowse() {
  if (PREF.browse) { bCat = PREF.browse.category || ''; bOrg = PREF.browse.org || ''; PREF.browse = null; } else { bCat = ''; bOrg = ''; }
  $('f-c').innerHTML = '<option value="">All</option>' + CATS.map(c => `<option>${c}</option>`).join('');
  $('f-c').value = bCat;
  await fillOrgSelect('f-o', 'All');
  $('f-o').value = bOrg;
  syncBrowsePills();
  await refreshBrowse();
}
function syncBrowsePills() {
  document.querySelectorAll('#b-pills .pill').forEach(p => p.classList.toggle('on', (bCat || 'All') === p.textContent.trim()));
}
async function refreshBrowse() {
  const d = await api('/api/requests?' + new URLSearchParams({ category: bCat, orgType: bOrg }));
  const rows = d.ok ? d.items : [];
  $('br-body').innerHTML = rows.length ? rows.map(r => `<tr><td>${esc(title(r))}</td><td>${esc(r.category)}</td><td>${esc(r.orgName)}</td><td>${days(r.daysWaiting)}</td><td><a class="btn solid" href="#/request-detail?id=${encodeURIComponent(r.id)}">Donate</a></td></tr>`).join('')
    : noRow(5, d.ok ? 'No waiting requests match.' : esc(d.error || 'Could not load requests.'));
}

// ----- Find a Match -----
let mCat = 'Food', mOrg = '', mSeen = [], mCurrent = null;
async function loadMatch() {
  if (PREF.match) { mCat = PREF.match.category || ''; mOrg = PREF.match.org || ''; PREF.match = null; } else { mCat = 'Food'; mOrg = ''; }
  mSeen = [];
  syncMatchPills();
  await nextMatch();
}
function syncMatchPills() {
  const c = mCat.toLowerCase();
  document.querySelectorAll('#m-c .pill').forEach(p => {
    const t = p.textContent.trim().toLowerCase();
    p.classList.toggle('on', !!c && (c === t || c.includes(t) || t.includes(c)));
  });
}
async function nextMatch() {
  const d = await api('/api/match?' + new URLSearchParams({ category: mCat, orgType: mOrg, seen: mSeen.join(',') }));
  const none = !d.ok || d.none;
  $('mt-content').hidden = none;
  $('mt-none').hidden = !none;
  mCurrent = none ? null : d.item;
  if (none) return;
  const r = d.item;
  setText('mt-strategy', d.strategy);
  setText('mt-cat', r.category);
  setText('mt-title', title(r));
  setText('mt-by', 'Posted by ' + r.orgName);
  setText('mt-org', r.orgName);
  setText('mt-since', r.posted);
  setText('mt-qty', r.quantity + ' ' + r.unit);
  setText('mt-details', r.details || 'No extra details.');
}

// ----- My Donations -----
let ddRows = [], ddFilter = 'all', ddPage = 1;
const PAGE_SIZE = 8;
async function loadDonations() {
  const d = await api('/api/my');
  ddRows = d.ok ? d.items : [];
  ddFilter = 'all'; ddPage = 1;
  document.querySelectorAll('#dd-pills .pill').forEach(p => p.classList.toggle('on', p.dataset.f === 'all'));
  const n = st => ddRows.filter(r => r.status === st).length;
  setText('dd-donated', n('DONATED')); setText('dd-delivered', n('DELIVERED')); setText('dd-received', n('RECEIVED'));
  drawDonations();
}
function drawDonations() {
  const rows = ddRows.filter(r => ddFilter === 'all' || r.status === ddFilter);
  const pages = Math.max(1, Math.ceil(rows.length / PAGE_SIZE));
  if (ddPage > pages) ddPage = pages;
  const shown = rows.slice((ddPage - 1) * PAGE_SIZE, ddPage * PAGE_SIZE);
  $('dd-body').innerHTML = shown.length ? shown.map(r => `<tr><td>${esc(title(r))}</td><td>${esc(r.orgName)}</td><td>${esc(r.donated)}</td><td>${badge(r.status)}</td><td>${
    r.status === 'DONATED' ? `<button class="btn solid" type="button" data-deliver="${esc(r.id)}">Mark Delivered</button>`
    : r.status === 'DELIVERED' ? 'Waiting for confirmation' : 'Done'}</td><td>${detailLink(r.id, 'Details')}</td></tr>`).join('')
    : noRow(6, 'No donations here yet.');
  const pager = $('dd-pager');
  pager.hidden = pages <= 1;
  pager.innerHTML = pages <= 1 ? '' : `<button class="pg" type="button" data-pg="prev">&lt;</button>`
    + Array.from({ length: pages }, (_, i) => `<button class="pg${i + 1 === ddPage ? ' on' : ''}" type="button" data-pg="${i + 1}">${i + 1}</button>`).join('')
    + `<button class="pg" type="button" data-pg="next">&gt;</button>`;
}

// ----- My Requests (organization) -----
let orRows = [], orFilter = 'all';
async function loadOrgRequests() {
  const d = await api('/api/my');
  orRows = d.ok ? d.items : [];
  orFilter = 'all';
  document.querySelectorAll('#or-pills .pill').forEach(p => p.classList.toggle('on', p.dataset.f === 'all'));
  drawOrgRequests();
}
function drawOrgRequests() {
  const rows = orRows.filter(r => orFilter === 'all' || r.status === orFilter);
  $('or-body').innerHTML = rows.length ? rows.map(r => `<tr><td>${esc(title(r))}</td><td>${esc(r.category)}</td><td>${esc(r.posted)}</td><td>${esc(r.donorName || 'No donor yet')}</td><td>${badge(r.status)}</td><td>${
    r.status === 'WAITING' ? 'No action yet' : r.status === 'DONATED' ? 'Donor is on it'
    : r.status === 'DELIVERED' ? '<a class="btn solid sm" href="#/org-confirm">Confirm Received</a>' : 'Done'}</td><td>${detailLink(r.id, 'Details')}</td></tr>`).join('')
    : noRow(7, orRows.length ? 'No requests here.' : 'You have not posted a request yet. Use Dashboard to post one.');
}

// ----- Confirm Receipts (organization) -----
async function loadConfirm() {
  const d = await api('/api/my');
  const rows = (d.ok ? d.items : []).filter(r => r.status === 'DELIVERED');
  setText('cf-count', rows.length + ' Waiting for Confirmation');
  $('cf-empty').hidden = rows.length > 0;
  $('cf-list').innerHTML = rows.map(r => `<div class="reqrow">
    <div class="icon"><img data-img="/images/${CAT_IMG[r.category] || 'food'}" alt=""></div>
    <div><span class="b">${esc(r.category.toUpperCase())}</span><h3>${esc(title(r))}</h3><p class="mute">${esc(r.details || 'No extra details.')}</p></div>
    <div><div class="mute">Delivered by</div><b>${esc(r.donorName || 'A donor')}</b><br><span class="b del">Delivered</span></div>
    <div><div class="mute">Marked Delivered on</div><b>${esc(r.delivered)}</b></div>
    <div class="right"><button class="btn solid" type="button" data-receive="${esc(r.id)}">Confirm Received</button><button class="btn" type="button" data-problem="${esc(r.id)}">Report a Problem</button></div></div>`).join('');
  loadImgs();
}

// ----- Request Details -----
let detail = null;
async function loadDetail() {
  const id = new URLSearchParams((location.hash.split('?')[1]) || '').get('id') || '';
  const d = await api('/api/request?id=' + encodeURIComponent(id));
  const back = $('rd-back');
  if (!d.ok) { setText('rd-title', d.error || 'Request not found.'); setText('rd-info', ''); $('rd-donate').hidden = true; return; }
  const r = detail = d.item;
  const isOrg = ME.role === 'ORGANIZATION';
  back.href = isOrg ? '#/org-requests' : (r.status === 'WAITING' ? '#/donor-browse' : '#/donor-donations');
  back.textContent = isOrg ? '< Back to my Requests' : (r.status === 'WAITING' ? '< Back to Browse Requests' : '< Back to my Donations');
  setText('rd-cat', r.category);
  const st = $('rd-status'); st.textContent = STATUS_TEXT[r.status]; st.className = badge(r.status).match(/class="([^"]+)"/)[1];
  setText('rd-title', title(r));
  $('rd-info').innerHTML = [`Organization: ${esc(r.orgName)}`, `Quantity: ${esc(r.quantity + ' ' + r.unit)}`, `Date Posted: ${esc(r.posted)}`,
    r.donorName ? `Donor: ${esc(r.donorName)}` : '', r.status !== 'WAITING' ? `Date Donated: ${esc(r.donated)}` : '',
    ['DELIVERED', 'RECEIVED'].includes(r.status) ? `Date Delivered: ${esc(r.delivered)}` : '', `Details: ${esc(r.details || 'None')}`].filter(Boolean).join('<br>');
  $('rd-donate').hidden = !(ME.role === 'DONOR' && r.status === 'WAITING');
  setText('rd-err', ''); $('rd-err').hidden = true;
  const order = ['WAITING', 'DONATED', 'DELIVERED', 'RECEIVED'], at = order.indexOf(r.status);
  const notes = [`Posted by the organization ${r.posted}`, r.status === 'WAITING' ? 'Waiting for a donor' : `A donor picked it ${r.donated}`,
    at >= 2 ? `Donor sent the items ${r.delivered}` : 'Not yet', at >= 3 ? `Organization confirmed ${r.received}` : 'Organization confirms: Pending'];
  document.querySelectorAll('#rd-track li').forEach((li, i) => {
    li.className = i <= at ? 'done' : '';
    li.querySelector('span').textContent = notes[i];
  });
}

// ----- Post a Request (organization) -----
async function postRequest(form) {
  const val = id => form.querySelector('#' + id).value.trim();
  const button = form.querySelector('button:not([type=button])');
  showError(form, '');
  button.disabled = true;
  try {
    const d = await api('/api/post', { category: val('p-c'), item: val('p-i'), quantity: val('p-q'), unit: val('p-u'), details: val('p-d') });
    if (!d.ok) { showError(form, d.error); return; }
    form.reset();
    form.dispatchEvent(new Event('input', { bubbles: true }));
    go('org-requests');
  } catch (err) {
    showError(form, 'Cannot reach the server. Run the Java program, then open http://localhost:8080');
  } finally {
    button.disabled = false;
  }
}

// ----- clicks on the donor + organization pages -----
async function act(path, data, done) {
  try {
    const d = await api(path, data);
    if (!d.ok) { alert(d.error || 'Something went wrong.'); return; }
    if (done) done(d);
  } catch (err) { alert('Cannot reach the server. Run the Java program, then open http://localhost:8080'); }
}
const reload = () => loadPortal(document.querySelector('.view.on'));

// Browse Requests: the dropdowns apply as soon as they are changed
document.addEventListener('change', e => {
  if (e.target.id !== 'f-c' && e.target.id !== 'f-o') return;
  bCat = $('f-c').value; bOrg = $('f-o').value;
  syncBrowsePills(); refreshBrowse();
});

// Account Verification: open the organization's own uploaded proof in a new tab (shows a message if it cannot)
document.addEventListener('click', async e => {
  const link = e.target.closest('a[href="/api/my-proof"]');
  if (!link) return;
  e.preventDefault();
  const tab = window.open('', '_blank');
  try {
    const res = await fetch('/api/my-proof');
    if (!res.ok) throw new Error(await res.text());
    const url = URL.createObjectURL(await res.blob());
    if (tab) tab.location = url; else window.location = url;
  } catch (err) {
    if (tab) tab.close();
    alert('Could not open the proof: ' + (err.message || 'unknown error'));
  }
});

document.addEventListener('click', e => {
  const t = e.target;
  // dashboard: carry the chosen category / organization to Browse and Find a Match
  const jump = t.closest('.view[data-v="donor-home"] a[href="#/donor-browse"]');
  if (jump) PREF.browse = { category: $('h-c').value, org: $('h-o').value };
  const jump2 = t.closest('.view[data-v="donor-home"] a[href="#/donor-match"]');
  if (jump2) PREF.match = { category: $('h-mc').value, org: $('h-mo').value };

  const bp = t.closest('#b-pills .pill');
  if (bp) { bCat = bp.textContent.trim() === 'All' ? '' : bp.textContent.trim(); $('f-c').value = bCat; refreshBrowse(); }
  if (t.closest('#b-filter')) { bCat = $('f-c').value; bOrg = $('f-o').value; syncBrowsePills(); refreshBrowse(); }

  const mp = t.closest('#m-c .pill');
  if (mp) { mCat = mp.textContent.trim(); mSeen = []; nextMatch(); }
  if (t.closest('#mt-next') && mCurrent) { mSeen.push(mCurrent.id); nextMatch(); }
  if (t.closest('#mt-reset')) { e.preventDefault(); mCat = ''; mOrg = ''; mSeen = []; syncMatchPills(); nextMatch(); window.scrollTo(0, 0); }
  if (t.closest('#mt-donate') && mCurrent) act('/api/donate', { id: mCurrent.id }, () => go('donor-donations'));

  const dp = t.closest('#dd-pills .pill');
  if (dp) { ddFilter = dp.dataset.f; ddPage = 1; drawDonations(); }
  const pg = t.closest('[data-pg]');
  if (pg) { const v = pg.dataset.pg; ddPage = v === 'prev' ? Math.max(1, ddPage - 1) : v === 'next' ? ddPage + 1 : Number(v); drawDonations(); }
  const op = t.closest('#or-pills .pill');
  if (op) { orFilter = op.dataset.f; drawOrgRequests(); }

  const del = t.closest('[data-deliver]');
  if (del) act('/api/deliver', { id: del.dataset.deliver }, reload);
  const rec = t.closest('[data-receive]');
  if (rec) act('/api/receive', { id: rec.dataset.receive }, reload);
  const prob = t.closest('[data-problem]');
  if (prob) {
    const message = prompt('Describe the problem. The admin will see your report.');
    if (message && message.trim()) act('/api/problem', { id: prob.dataset.problem, message }, () => alert('Thank you. Your report was sent to the admin.'));
  }
  if (t.closest('#rd-donate') && detail) {
    api('/api/donate', { id: detail.id }).then(d => {
      if (d.ok) go('donor-donations');
      else { const box = $('rd-err'); box.textContent = d.error || 'Something went wrong.'; box.hidden = false; }
    }).catch(() => {});
  }
});

addEventListener('hashchange', render);
api('/api/me').then(d => { ME = d.ok ? d : null; }).catch(() => {}).finally(render);