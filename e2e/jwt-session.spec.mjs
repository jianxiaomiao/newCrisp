import { expect, test } from '@playwright/test';
import { createTenant } from './support/platform.mjs';

const tenant = { code: 'jwt-a', name: '令牌验收', adminEmail: 'jwt-admin@example.test', adminPassword: 'Admin-Session!2026' };
const agentEmail = 'jwt-agent@example.test';
const agentPassword = 'Agent-Session!2026';

test('two tabs rotate in order; replay and logout revoke the family and close ticket-authenticated connections', async ({ page, request }) => {
  test.setTimeout(60_000);
  await createTenant(request, tenant);
  await page.goto('/staff.html');
  await signIn(page, tenant.adminEmail, tenant.adminPassword);
  await page.getByRole('button', { name: '新增员工' }).click();
  await page.getByLabel('成员邮箱').fill(agentEmail);
  await page.getByLabel('初始密码').fill(agentPassword);
  await page.getByRole('button', { name: '创建成员' }).click();
  await expect(page.getByRole('status')).toHaveText('成员已创建。');
  await page.getByRole('button', { name: '退出登录' }).click();
  await signIn(page, agentEmail, agentPassword);
  await expect(page.locator('.chat-connection')).toHaveText('');

  const second = await page.context().newPage();
  await second.goto('/staff.html');
  await expect(second.getByRole('heading', { name: '客服工作台' })).toBeVisible();
  const before = await refreshCookies(page.context());
  let release;
  let entered;
  const held = new Promise((done) => { entered = done; });
  const gate = new Promise((done) => { release = done; });
  await page.route('**/api/v1/staff/session/refresh', async (route) => {
    entered();
    await gate;
    await route.continue();
  });

  const first = refresh(page);
  await held;
  const later = refresh(second);
  try {
    await expect.poll(() => second.evaluate(async () => {
      const locks = await navigator.locks.query();
      return locks.pending.filter((entry) => entry.name === 'newcrisp-session:staff').length;
    })).toBe(1);
  } finally {
    release();
  }
  const [one, two] = await Promise.all([first, later]);
  await page.unroute('**/api/v1/staff/session/refresh');
  expect(one.sessionId).toBe(two.sessionId);
  expect(Boolean(one.accessToken !== two.accessToken)).toBe(true);
  const after = await refreshCookies(page.context());
  expect(Boolean(before.refresh.value !== after.refresh.value)).toBe(true);
  const storageIsClean = await page.evaluate((tokens) => !Object.values(localStorage).concat(Object.values(sessionStorage))
    .some((value) => tokens.some((token) => value.includes(token))), [two.accessToken, after.refresh.value]);
  expect(storageIsClean).toBe(true);

  const oldProbe = await openSocket(page, await ticket(page));
  expect(oldProbe.ready).toBe(true);
  const replay = await request.post('/api/v1/staff/session/refresh', {
    headers: {
      Origin: process.env.NEWCRISP_PUBLIC_ORIGIN,
      Cookie: `${before.refresh.name}=${before.refresh.value}`,
      'X-NewCrisp-CSRF': before.csrf.value.split('.')[1],
    },
  });
  expect(replay.status()).toBe(401);
  expect((await replay.json()).code).toBe('REFRESH_REPLAY');
  await expect.poll(() => closed(page, oldProbe.id)).toBe(1008);
  expect((await request.get('/api/v1/staff/me', { headers: { Authorization: `Bearer ${two.accessToken}` } })).status()).toBe(401);
  const revokedCurrent = await request.post('/api/v1/staff/session/refresh', {
    headers: { Origin: process.env.NEWCRISP_PUBLIC_ORIGIN, Cookie: `${after.refresh.name}=${after.refresh.value}`,
      'X-NewCrisp-CSRF': after.csrf.value.split('.')[1] },
  });
  expect(revokedCurrent.status()).toBe(401);

  await signIn(page, agentEmail, agentPassword);
  const sharedTicket = await ticket(page);
  const candidates = await Promise.all([openSocket(page, sharedTicket), openSocket(second, sharedTicket)]);
  expect(candidates.map((result) => result.ready).sort()).toEqual([false, true]);
  const winner = candidates[0].ready ? [page, candidates[0]] : [second, candidates[1]];
  const unused = await ticket(page);
  const access = await page.evaluate(async () => (await (await import('/web-session.js')).webSession('staff').access()).accessToken);
  await page.getByRole('button', { name: '退出登录' }).click();
  await expect.poll(() => closed(winner[0], winner[1].id)).toBe(1008);
  await expect(second.getByRole('heading', { name: '登录工作台' })).toBeVisible();
  expect((await request.get('/api/v1/staff/me', { headers: { Authorization: `Bearer ${access}` } })).status()).toBe(401);
  expect((await openSocket(page, unused)).ready).toBe(false);
});

test('a lost refresh response makes both tabs stop refreshing the uncertain token', async ({ page, request }) => {
  const isolated = { ...tenant, code: 'jwt-loss', adminEmail: 'jwt-loss@example.test' };
  await createTenant(request, isolated);
  await page.goto('/staff.html');
  await signIn(page, isolated.adminEmail, isolated.adminPassword, isolated);
  const second = await page.context().newPage();
  await second.goto('/staff.html');
  await expect(second.getByRole('heading', { name: '租户管理' })).toBeVisible();
  let committed;
  let siblingRequests = 0;
  await page.route('**/api/v1/staff/session/refresh', async (route) => {
    committed = (await route.fetch()).status();
    await route.abort('connectionreset');
  });
  await second.route('**/api/v1/staff/session/refresh', (route) => {
    siblingRequests++;
    return route.continue();
  });
  expect(await refresh(page).then(() => true, () => false)).toBe(false);
  expect(committed).toBe(200);
  expect(await refresh(second).then(() => 200, () => 401)).toBe(401);
  expect(siblingRequests).toBe(0);
  await expect(page.getByRole('heading', { name: '登录工作台' })).toBeVisible();
  await expect(second.getByRole('heading', { name: '登录工作台' })).toBeVisible();
});

async function signIn(page, email, password, target = tenant) {
  await page.getByLabel('租户代码').fill(target.code);
  await page.getByLabel('邮箱', { exact: true }).fill(email);
  await page.getByLabel('密码', { exact: true }).fill(password);
  await page.getByRole('button', { name: '登录', exact: true }).click();
  await expect(page.getByRole('heading', { name: email === target.adminEmail ? '租户管理' : '客服工作台' })).toBeVisible();
}

async function refreshCookies(context) {
  const cookies = await context.cookies();
  return {
    refresh: cookies.find((row) => row.name === '__Host-newcrisp_staff_rt'),
    csrf: cookies.find((row) => row.name === '__Host-newcrisp_staff_csrf'),
  };
}

function refresh(page) {
  return page.evaluate(async () => (await import('/web-session.js')).webSession('staff').access(true));
}

function ticket(page) {
  return page.evaluate(async () => {
    const response = await (await import('/web-session.js')).webSession('staff')
      .request('/api/v1/staff/ws-ticket', { method: 'POST' });
    if (!response.ok) throw new Error(`Ticket returned ${response.status}`);
    return (await response.json()).ticket;
  });
}

function openSocket(page, ticket) {
  return page.evaluate((code) => new Promise((resolve, reject) => {
    const url = new URL('/ws/staff', location.href);
    url.protocol = location.protocol === 'https:' ? 'wss:' : 'ws:';
    const socket = new WebSocket(url);
    const id = crypto.randomUUID();
    const sample = { socket, closeCode: null };
    (window.__newcrispSocketProbes ??= {})[id] = sample;
    const timeout = setTimeout(() => { socket.close(); reject(new Error('Socket did not reach a terminal state')); }, 5_000);
    socket.onopen = () => socket.send(JSON.stringify({ type: 'auth', ticket: code }));
    socket.onmessage = (event) => {
      const frame = JSON.parse(event.data);
      if (frame.type === 'ready') { clearTimeout(timeout); resolve({ id, ready: true }); }
      if (frame.type === 'ping') socket.send(JSON.stringify({ type: 'pong', nonce: frame.data }));
    };
    socket.onclose = (event) => {
      sample.closeCode = event.code;
      clearTimeout(timeout);
      resolve({ id, ready: false });
    };
  }), ticket);
}

function closed(page, id) {
  return page.evaluate((key) => window.__newcrispSocketProbes?.[key]?.closeCode, id);
}
