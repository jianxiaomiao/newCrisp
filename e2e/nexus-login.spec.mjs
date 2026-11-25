import { expect, test } from '@playwright/test';
import { randomUUID } from 'node:crypto';
import { configureNexus, createTenant } from './support/platform.mjs';
import { nexusApi, nexusOrigin, startNexus } from './support/nexus.mjs';
import { browserApi } from './support/browser-api.mjs';

const first = { code: 'nexus-a', name: 'Nexus 华东', adminEmail: 'nexus-a@example.test', adminPassword: 'Provisioned-Login!2026' };
const second = { code: 'nexus-b', name: 'Nexus 华南', adminEmail: 'nexus-b@example.test', adminPassword: 'Provisioned-Login!2026' };
const customer = { email: 'customer@example.test', password: 'Nexus-Browser!2026' };
let stopNexus;
let keyB;

test.describe('Nexus login against isolated MySQL', () => {
  test.skip(!process.env.NEWCRISP_E2E_NEXUS_WORK, 'Set NEWCRISP_E2E_NEXUS_ROOT to build a real Nexus fixture.');

  test.beforeAll(async ({ request }, testInfo) => {
    testInfo.setTimeout(90_000);
    await createTenant(request, first);
    await createTenant(request, second);
    const keyA = await configureNexus(request, first.code, nexusOrigin);
    keyB = await configureNexus(request, second.code, nexusOrigin);
    stopNexus = await startNexus(keyA);
    const registration = await request.post(`${nexusApi}/api/auth/register`, { data: { ...customer, displayName: '验收客户' } });
    expect(registration.ok()).toBe(true);
  });

  test.afterAll(async () => { if (stopNexus) await stopNexus(); });

  test('Nexus browser login launches customer chat and isolates the same customer between tenants', async ({ page, browser, request }) => {
    await page.goto(`${nexusOrigin}/login`);
    await page.getByPlaceholder('请输入邮箱').fill(customer.email);
    await page.getByPlaceholder('请输入密码').fill(customer.password);
    await page.getByRole('button', { name: '登录', exact: true }).click();
    const launch = page.waitForRequest((entry) => entry.url() === `${process.env.NEWCRISP_PUBLIC_ORIGIN}/launch/nexus`);
    await page.getByRole('button', { name: '在线客服' }).click();
    const grant = new URLSearchParams((await launch).postData()).get('grant');
    await expect(page).toHaveURL(`${process.env.NEWCRISP_PUBLIC_ORIGIN}/customer.html`);
    await expect(page.getByRole('status')).toHaveText('已通过 Nexus 登录');
    await expect(page.getByText(first.name)).toBeVisible();
    const profileA = (await browserApi(page, '/api/v1/customer/me')).data;
    expect(profileA.tenantCode).toBe(first.code);
    expect((await browserApi(page, `/api/v1/customer/tenants/${second.code}/me`)).status).toBe(404);
    expect((await browserApi(page, '/api/v1/staff/me')).status).toBe(401);
    expect(await redeem(request, grant)).toBe(401);

    const login = await request.post(`${nexusApi}/api/auth/login`, { data: customer });
    expect(login.ok()).toBe(true);
    const { accessToken } = (await login.json()).data;
    const { sub, sid } = JSON.parse(Buffer.from(accessToken.split('.')[1], 'base64url').toString('utf8'));
    const issue = () => request.post('/integrations/nexus/launch-grants', {
      headers: { Authorization: `Bearer ${keyB}` },
      data: { userId: Number(sub), sessionId: Number(sid) },
    });
    const issued = await issue();
    expect(issued.status()).toBe(201);
    const { grant: tokenB } = await issued.json();
    expect(await redeem(request, tokenB, 'https://another.example.test')).toBe(403);

    const tenantB = await browser.newContext({ baseURL: process.env.NEWCRISP_PUBLIC_ORIGIN });
    try {
      const other = await tenantB.newPage();
      await other.goto(`${nexusOrigin}/login`);
      await other.evaluate(({ action, grant }) => {
        const form = document.createElement('form');
        form.method = 'POST';
        form.action = action;
        const input = document.createElement('input');
        input.type = 'hidden';
        input.name = 'grant';
        input.value = grant;
        form.append(input);
        document.body.append(form);
        form.submit();
      }, { action: `${process.env.NEWCRISP_PUBLIC_ORIGIN}/launch/nexus`, grant: tokenB });
      await expect(other.getByText(second.name)).toBeVisible();
      const profileB = (await browserApi(other, '/api/v1/customer/me')).data;
      expect(profileB.tenantCode).toBe(second.code);
      expect(profileB.customerId).not.toBe(profileA.customerId);
      expect((await browserApi(other, `/api/v1/customer/tenants/${first.code}/me`)).status).toBe(404);
    } finally {
      await tenantB.close();
    }

    const raceGrant = (await (await issue()).json()).grant;
    const outcomes = await Promise.all([redeem(request, raceGrant), redeem(request, raceGrant)]);
    expect(outcomes.sort()).toEqual([303, 401]);

    const nexusTab = await page.context().newPage();
    await nexusTab.goto(nexusOrigin);
    await nexusTab.getByRole('button', { name: '退出登录' }).click();
    await expect(nexusTab).toHaveURL(/\/login$/);
    expect((await browserApi(page, '/api/v1/customer/me')).status).toBe(401);
    await page.reload();
    await expect(page.getByRole('status')).toHaveText('请从 Nexus 控制台重新进入在线客服。');
  });

  test('WebSocket conversation survives lost ACK, concurrent claim and an offline client', async ({ browser, page }) => {
    test.setTimeout(90_000);
    const opened = [];
    async function staff(tenant, email = tenant.adminEmail, password = tenant.adminPassword) {
      const context = await browser.newContext({ baseURL: process.env.NEWCRISP_PUBLIC_ORIGIN });
      opened.push(context);
      const tab = await context.newPage();
      await tab.goto('/staff.html');
      await tab.getByLabel('租户代码').fill(tenant.code);
      await tab.getByLabel('邮箱', { exact: true }).fill(email);
      await tab.getByLabel('密码', { exact: true }).fill(password);
      await tab.getByRole('button', { name: '登录', exact: true }).click();
      await expect(tab.getByRole('heading', { name: email === tenant.adminEmail ? '租户管理' : '客服工作台' })).toBeVisible();
      return tab;
    }
    const firstAgent = 'one-agent@example.test';
    const secondAgent = 'two-agent@example.test';
    const supervisor = 'supervisor@example.test';
    const otherAgent = 'other-tenant@example.test';
    const password = 'Agent-Test-Password!2026';
    const admin = await staff(first);
    await createMember(admin, firstAgent, password, 'AGENT');
    await createMember(admin, secondAgent, password, 'AGENT');
    await createMember(admin, supervisor, password, 'SUPERVISOR');
    const otherAdmin = await staff(second);
    await createMember(otherAdmin, otherAgent, password, 'AGENT');

    try {
      const [agentA, agentB, manager, outsider] = await Promise.all([
        staff(first, firstAgent, password), staff(first, secondAgent, password),
        staff(first, supervisor, password), staff(second, otherAgent, password),
      ]);
      const starts = [];
      let dropAck = true, offline = false, route;
      await page.routeWebSocket('**/ws/customer', (client) => {
        route = client;
        if (offline) { client.close(); return; }
        const server = client.connectToServer();
        client.onMessage((body) => {
          const frame = JSON.parse(body.toString());
          if (frame.type === 'conversation.start') starts.push(frame.clientMessageId);
          server.send(body);
        });
        server.onMessage((body) => {
          if (dropAck && JSON.parse(body.toString()).type === 'message.ack') {
            dropAck = false;
            client.close();
          } else client.send(body);
        });
      });
      await page.goto(`${nexusOrigin}/login`);
      await page.getByPlaceholder('请输入邮箱').fill(customer.email);
      await page.getByPlaceholder('请输入密码').fill(customer.password);
      await page.getByRole('button', { name: '登录', exact: true }).click();
      await page.getByRole('button', { name: '在线客服' }).click();
      await expect(page.getByRole('status')).toHaveText('已通过 Nexus 登录');
      await expect(page.locator('.chat-connection')).toHaveText('');

      const seeded = '首条消息已保存，发送确认被丢弃';
      await page.getByRole('textbox', { name: '消息' }).fill(seeded);
      await page.getByRole('button', { name: '发送', exact: true }).click();
      await expect(page.getByText('发送结果待确认，重试会沿用本次发送。')).toBeVisible();
      await expect(page.getByRole('heading', { name: '等待客服' })).toBeVisible();
      await expect(page.locator('.chat-connection')).toHaveText('');
      await page.getByRole('button', { name: '发送', exact: true }).click();
      await expect(page.getByRole('textbox', { name: '消息' })).toHaveValue('');
      expect(starts).toHaveLength(2);
      expect(starts[0]).toBe(starts[1]);

      const pending = await pollQueue(agentA);
      const id = pending.id;
      const path = `/api/v1/staff/conversations/${id}`;
      await expect(agentB.getByRole('button', { name: '领取', exact: true })).toBeVisible();
      expect((await browserApi(agentB, `${path}/messages`)).status).toBe(404);
      expect((await browserApi(outsider, '/api/v1/staff/conversations?view=PENDING')).data.items).toEqual([]);
      expect((await browserApi(outsider, `${path}/messages`)).status).toBe(404);
      expect((await write(outsider, `${path}/claim`, { actionId: randomUUID(), stateVersion: pending.stateVersion })).status).toBe(404);
      const contenders = await Promise.all([agentA, agentB].map((agent) => write(agent, `${path}/claim`, {
        actionId: randomUUID(), stateVersion: pending.stateVersion,
      })));
      expect(contenders.map((response) => response.status).sort()).toEqual([200, 409]);
      const winner = contenders[0].status === 200 ? agentA : agentB;
      const loser = winner === agentA ? agentB : agentA;
      await winner.getByRole('button', { name: '我负责' }).click();
      await winner.getByRole('button', { name: new RegExp(seeded) }).click();
      await expect(winner.getByText('客户在线', { exact: true })).toBeVisible();
      expect((await browserApi(winner, `${path}/messages`)).data.items.map((message) => message.text)).toEqual([seeded]);
      expect((await browserApi(loser, `${path}/messages`)).status).toBe(404);

      offline = true;
      await page.context().setOffline(true);
      await route.close();
      await expect(winner.getByText('客户离线', { exact: true })).toBeVisible({ timeout: 10_000 });
      const reply = '客户离线时已持久化的回复';
      await winner.getByRole('textbox', { name: '消息' }).fill(reply);
      await winner.getByRole('button', { name: '发送', exact: true }).click();
      await expect(winner.getByRole('list', { name: '消息' }).getByText(reply)).toBeVisible();
      offline = false;
      await page.context().setOffline(false);
      await expect(page.getByRole('list', { name: '消息' }).getByText(reply)).toHaveCount(1, { timeout: 15_000 });

      const original = (await browserApi(winner, path)).data;
      expect((await write(manager, `${path}/return`, { actionId: randomUUID(), stateVersion: original.stateVersion })).status).toBe(200);
      expect((await browserApi(winner, `${path}/messages`)).status).toBe(404);
      const denied = await socketReply(winner, {
        type: 'message.send', conversationId: id, text: '旧负责人不应发出', stateVersion: original.stateVersion,
      });
      expect(denied).toMatchObject({ type: 'error', data: { status: 404 } });
      const returned = await pollQueue(loser);
      expect(returned.id).toBe(id);
      expect((await write(loser, `${path}/claim`, { actionId: randomUUID(), stateVersion: returned.stateVersion })).status).toBe(200);
      const current = (await browserApi(loser, path)).data;
      await page.getByRole('textbox', { name: '消息' }).fill('结束前还有一条客户留言');
      await page.getByRole('button', { name: '发送', exact: true }).click();
      await expect(page.getByRole('list', { name: '消息' }).getByText('结束前还有一条客户留言')).toBeVisible();
      const stale = await write(loser, `${path}/close`, {
        actionId: randomUUID(), stateVersion: current.stateVersion, observedCustomerSeq: current.lastCustomerSeq,
      });
      expect(stale).toMatchObject({ status: 409, data: { code: 'NEW_CUSTOMER_MESSAGES' } });
      const latest = (await browserApi(loser, path)).data;
      expect((await write(loser, `${path}/read-position`, { stateVersion: latest.stateVersion, upToSeq: latest.lastSeq }, 'PUT')).status).toBe(204);
      expect((await write(loser, `${path}/close`, {
        actionId: randomUUID(), stateVersion: latest.stateVersion, observedCustomerSeq: latest.lastCustomerSeq,
      })).status).toBe(200);
      await expect(page.getByRole('heading', { name: '已结束' })).toBeVisible();
      expect((await browserApi(page, '/api/v1/customer/conversations?view=HISTORY')).data.items[0].id).toBe(id);
    } finally {
      await Promise.all(opened.map((context) => context.close()));
    }
  });
});

async function redeem(request, grant, origin = nexusOrigin) {
  const response = await request.post('/launch/nexus', {
    headers: { Origin: origin }, form: { grant }, maxRedirects: 0,
  });
  return response.status();
}

async function createMember(page, email, password, role) {
  await page.getByLabel('成员邮箱').fill(email);
  await page.getByLabel('初始密码').fill(password);
  await page.getByLabel('职责').selectOption(role);
  const created = page.waitForResponse((response) => response.url().endsWith('/api/v1/staff/members') && response.request().method() === 'POST');
  await page.getByRole('button', { name: '创建成员' }).click();
  expect((await created).status()).toBe(201);
  await expect(page.getByRole('status')).toHaveText('成员已创建。');
}

function write(page, path, body, method = 'POST') {
  return page.evaluate(async ({ path, body, method }) => {
    const current = await fetch('/api/v1/staff/me');
    const response = await fetch(path, { method, body: JSON.stringify(body), headers: {
      'Content-Type': 'application/json', 'X-NewCrisp-CSRF': current.headers.get('X-NewCrisp-CSRF'),
    } });
    return { status: response.status, authenticated: current.status, data: await response.json().catch(() => null) };
  }, { path, body, method });
}

async function pollQueue(page) {
  let pending;
  await expect.poll(async () => {
    pending = (await browserApi(page, '/api/v1/staff/conversations?view=PENDING')).data?.items?.[0];
    return Boolean(pending);
  }).toBe(true);
  return pending;
}

function socketReply(page, payload) {
  return page.evaluate((message) => new Promise((resolve, reject) => {
    const url = new URL('/ws/staff', location.href);
    url.protocol = location.protocol === 'https:' ? 'wss:' : 'ws:';
    const connection = new WebSocket(url);
    const requestId = crypto.randomUUID();
    const timer = setTimeout(() => { connection.close(); reject(new Error('Socket command timed out')); }, 5_000);
    connection.onmessage = (event) => {
      const row = JSON.parse(event.data);
      if (row.type === 'ready') connection.send(JSON.stringify({ ...message, requestId, clientMessageId: crypto.randomUUID() }));
      if (row.type === 'ping') connection.send(JSON.stringify({ type: 'pong', nonce: row.data }));
      if (row.requestId !== requestId) return;
      clearTimeout(timer);
      connection.close();
      resolve(row);
    };
  }), payload);
}
