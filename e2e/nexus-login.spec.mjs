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
      return openStaff(context, tenant, email, password);
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
      const [manager, outsider] = await Promise.all([
        staff(first, supervisor, password), staff(second, otherAgent, password),
      ]);
      const starts = [], usedTickets = new Set();
      let dropAck = true, offline = false, route;
      await page.routeWebSocket('**/ws/customer', (client) => {
        route = client;
        if (offline) { client.close(); return; }
        const server = client.connectToServer();
        client.onMessage((body) => {
          const frame = JSON.parse(body.toString());
          if (frame.type === 'conversation.start') starts.push(frame.clientMessageId);
          if (frame.type === 'auth') usedTickets.add(frame.ticket);
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

      const [agentA, agentB] = await Promise.all([staff(first, firstAgent, password), staff(first, secondAgent, password)]);
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
      expect(usedTickets.size).toBeGreaterThanOrEqual(3);

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

  test('tenant management isolates administrators and revokes open staff tabs and sockets', async ({ page, browser, request }) => {
    test.setTimeout(100_000);
    const contexts = [];
    const open = async (tenant, email = tenant.adminEmail, password = tenant.adminPassword) => {
      const context = await browser.newContext({ baseURL: process.env.NEWCRISP_PUBLIC_ORIGIN });
      contexts.push(context);
      return openStaff(context, tenant, email, password);
    };
    const password = 'Management-Test!2026';
    const agentEmail = 'management-agent@example.test';
    const otherEmail = 'management-other@example.test';
    const account = { email: 'management-customer@example.test', password: 'Nexus-Management!2026' };
    const staff = '/api/v1/staff';
    const confidential = '仅客户支持部门可见的会话';

    try {
      const [admin, otherAdmin] = await Promise.all([open(first), open(second)]);
      await admin.getByRole('button', { name: '新增部门' }).click();
      await admin.getByLabel('新部门代码').fill('billing');
      await admin.getByLabel('部门名称', { exact: true }).fill('账单组');
      await admin.getByRole('button', { name: '创建部门' }).click();
      await expect(admin.getByRole('status')).toHaveText('部门已创建。');
      await admin.getByText('调整部门', { exact: true }).click();
      const billingRow = admin.getByRole('list', { name: '部门', exact: true }).getByRole('listitem').filter({ hasText: '账单组（billing）' });
      await billingRow.getByText('调整 账单组（billing）', { exact: true }).click();
      await billingRow.getByLabel('部门新名称').fill('账单支持');
      await billingRow.getByRole('button', { name: '保存部门' }).click();
      await expect(admin.getByText('调整 账单支持（billing）', { exact: true })).toBeVisible();
      expect((await write(otherAdmin, `${staff}/departments`, { code: 'foreign', name: '其他租户部门' })).status).toBe(201);

      await createMember(admin, agentEmail, password, 'AGENT');
      await createMember(otherAdmin, otherEmail, password, 'AGENT');
      const managedRow = admin.getByRole('row').filter({ hasText: agentEmail });
      const roleEditor = admin.getByRole('form', { name: '调整部门角色' });
      await managedRow.getByText('调整角色', { exact: true }).click();
      await roleEditor.getByText('账单支持（billing） · 客服', { exact: true }).click();
      await expect(roleEditor.getByRole('checkbox', { name: '账单支持（billing） · 客服', exact: true })).toBeChecked();
      await roleEditor.getByText('账单支持（billing） · 主管', { exact: true }).click();
      await expect(roleEditor.getByRole('checkbox', { name: '账单支持（billing） · 主管', exact: true })).toBeChecked();
      await roleEditor.getByRole('button', { name: '保存角色' }).click();
      await expect(admin.getByRole('status')).toHaveText('部门角色已更新。');
      const departments = (await browserApi(admin, `${staff}/departments`)).data;
      const billing = departments.find(({ code }) => code === 'billing');
      expect(billing).toMatchObject({ name: '账单支持', version: '1' });
      const outsiderDepartment = (await browserApi(otherAdmin, `${staff}/departments`)).data.find(({ code }) => code === 'foreign');
      const memberList = (await browserApi(admin, `${staff}/members`)).data;
      const member = memberList.find(({ email }) => email === agentEmail);
      const outsider = (await browserApi(otherAdmin, `${staff}/members`)).data.find(({ email }) => email === otherEmail);
      expect(departments.map(({ code }) => code)).toEqual(['billing', 'support']);
      expect(memberList.map(({ email }) => email)).not.toContain(otherEmail);
      expect(member.roles).toEqual(expect.arrayContaining([
        { departmentId: billing.id, role: 'AGENT' }, { departmentId: billing.id, role: 'SUPERVISOR' },
      ]));
      const denied = await Promise.all([
        write(admin, `${staff}/departments/${outsiderDepartment.id}`, { name: '禁止修改', version: outsiderDepartment.version }, 'PUT'),
        write(admin, `${staff}/members/${outsider.id}/roles`, { roles: [], version: outsider.version }, 'PUT'),
        write(admin, `${staff}/members/${outsider.id}/disable`, { version: outsider.version }),
        write(admin, `${staff}/members/${member.id}/roles`, {
          roles: [{ departmentId: outsiderDepartment.id, role: 'AGENT' }], version: member.version,
        }, 'PUT'),
      ]);
      expect(denied.map(({ status }) => status)).toEqual([404, 404, 404, 404]);
      expect((await browserApi(otherAdmin, `${staff}/members`)).data.find(({ id }) => id === outsider.id)).toEqual(outsider);
      expect((await browserApi(otherAdmin, `${staff}/departments`)).data.find(({ id }) => id === outsiderDepartment.id)).toEqual(outsiderDepartment);

      const registration = await request.post(`${nexusApi}/api/auth/register`, { data: { ...account, displayName: '部门隔离客户' } });
      expect(registration.ok()).toBe(true);
      await page.goto(`${nexusOrigin}/login`);
      await signInNexus(page, account);
      await page.getByRole('button', { name: '在线客服' }).click();
      await expect(page.getByRole('status')).toHaveText('已通过 Nexus 登录');
      const id = await startConversation(page, confidential);
      const path = `${staff}/conversations/${id}`;
      const agent = await open(first, agentEmail, password);
      const secondTab = await agent.context().newPage();
      await secondTab.goto('/staff.html');
      expect((await browserApi(agent, `${staff}/members`)).status).toBe(403);
      expect((await write(agent, `${staff}/departments`, { code: 'denied', name: '无管理权限' })).status).toBe(403);
      const pending = (await browserApi(agent, `${staff}/conversations?view=PENDING`)).data.items.find((row) => row.id === id);
      const claimed = await write(agent, `${path}/claim`, { stateVersion: pending.stateVersion, actionId: randomUUID() });
      expect(claimed.status).toBe(200);
      for (const tab of [agent, secondTab]) {
        await tab.getByRole('button', { name: '我负责' }).click();
        await tab.getByRole('button', { name: new RegExp(confidential) }).click();
        await expect(tab.locator('.chat-log')).toContainText(confidential);
        await expect(tab.getByRole('button', { name: '发送', exact: true })).toBeVisible();
      }
      const oldTicket = (await browserApi(agent, `${staff}/ws-ticket`, { method: 'POST' })).data.ticket;
      const openProbe = await watchStaffSocket(agent);
      await expect.poll(() => socketState(agent, openProbe)).toMatchObject({ ready: true, closed: null });

      await managedRow.getByText('调整角色', { exact: true }).click();
      await roleEditor.getByText('客户支持（support） · 客服', { exact: true }).click();
      await expect(roleEditor.getByRole('checkbox', { name: '客户支持（support） · 客服', exact: true })).not.toBeChecked();
      await roleEditor.getByRole('button', { name: '保存角色' }).click();
      await expect(admin.getByRole('status')).toHaveText('部门角色已更新。');
      await expect.poll(() => socketState(agent, openProbe)).toEqual({ ready: true, closed: 4003 });
      const staleProbe = await watchStaffSocket(agent, oldTicket);
      await expect.poll(() => socketState(agent, staleProbe)).toEqual({ ready: false, closed: 4003 });
      for (const tab of [agent, secondTab]) {
        await expect(tab.getByRole('heading', { name: '客服工作台' })).toBeVisible();
        await expect(tab.getByRole('button', { name: '部门会话' })).toBeVisible();
        await expect(tab.getByRole('button', { name: '待领取' })).toBeVisible();
        await expect(tab.getByText(confidential, { exact: true })).toHaveCount(0);
        await expect(tab.getByRole('button', { name: '发送', exact: true })).not.toBeVisible();
        await expect(tab.locator('.chat-connection')).toHaveText('');
      }
      expect(await browserApi(agent, `${staff}/ws-ticket`, {
        method: 'POST', headers: { 'X-NewCrisp-Staff-Access': member.version },
      })).toMatchObject({ status: 409, data: { code: 'STAFF_ACCESS_CHANGED' } });
      expect((await browserApi(agent, `${path}/messages`)).status).toBe(404);
      expect((await write(agent, `${path}/messages`, {
        stateVersion: claimed.data.stateVersion, clientMessageId: randomUUID(), text: '已失去权限',
      })).status).toBe(404);
      expect(await socketReply(agent, {
        type: 'message.send', conversationId: id, stateVersion: claimed.data.stateVersion, text: '已失去权限',
      })).toMatchObject({ type: 'error', data: { status: 404 } });

      const currentProbe = await watchStaffSocket(agent);
      const oldAccess = await agent.evaluate(async () => (await (await import('/web-session.js')).webSession('staff').access()).accessToken);
      await expect.poll(() => socketState(agent, currentProbe)).toMatchObject({ ready: true, closed: null });
      const independentLogin = await open(first, agentEmail, password);
      const independentProbe = await watchStaffSocket(independentLogin);
      await expect.poll(() => socketState(independentLogin, independentProbe)).toMatchObject({ ready: true, closed: null });
      await managedRow.getByRole('button', { name: '停用账号' }).click();
      await admin.getByRole('dialog', { name: '停用账号' }).getByRole('button', { name: '确定', exact: true }).click();
      await expect(managedRow.getByText('已停用', { exact: true })).toBeVisible();
      for (const [tab, probe] of [[agent, currentProbe], [independentLogin, independentProbe]]) {
        await expect.poll(async () => [4004, 1008].includes((await socketState(tab, probe)).closed)).toBe(true);
      }
      for (const tab of [agent, secondTab, independentLogin]) {
        await expect(tab.getByRole('heading', { name: '登录工作台' })).toBeVisible();
        await expect(tab.getByRole('button', { name: '待领取' })).toHaveCount(0);
      }
      expect((await request.get(`${staff}/me`, { headers: { Authorization: `Bearer ${oldAccess}` } })).status()).toBe(401);
      expect((await request.post(`${staff}/session`, {
        headers: { Origin: process.env.NEWCRISP_PUBLIC_ORIGIN }, data: { tenantCode: first.code, email: agentEmail, password },
      })).status()).toBe(401);
      expect((await browserApi(otherAdmin, `${staff}/members`)).data.find(({ id }) => id === outsider.id)).toEqual(outsider);
    } finally {
      await Promise.all(contexts.map((context) => context.close()));
    }
  });

  test('Nexus unread badge follows read receipts, the active account and the integration tenant', async ({ page, browser, request }) => {
    test.setTimeout(100_000);
    const primary = { email: 'unread-customer@example.test', password: 'Nexus-Unread!2026' };
    const alternate = { email: 'unread-other@example.test', password: 'Nexus-Unread!2026' };
    for (const person of [primary, alternate]) {
      expect((await request.post(`${nexusApi}/api/auth/register`, { data: { ...person, displayName: '未读验收' } })).ok()).toBe(true);
    }
    const login = await request.post(`${nexusApi}/api/auth/login`, { data: primary });
    expect(login.ok()).toBe(true);
    const { accessToken } = (await login.json()).data;
    const { sub, sid } = JSON.parse(Buffer.from(accessToken.split('.')[1], 'base64url').toString('utf8'));
    expect((await request.get(`${nexusApi}/api/support/chat/unread`)).status()).toBe(401);

    const openContexts = [];
    const fixtureContext = async () => {
      const context = await browser.newContext({ baseURL: process.env.NEWCRISP_PUBLIC_ORIGIN });
      openContexts.push(context);
      return context;
    };
    let releaseOld = () => {};
    try {
      const agent = async (tenant, email) => {
        const context = await fixtureContext();
        const admin = await openStaff(context, tenant, tenant.adminEmail, tenant.adminPassword);
        const password = 'Unread-Agent!2026';
        await createMember(admin, email, password, 'AGENT');
        await admin.getByRole('button', { name: '退出登录' }).click();
        return openStaff(context, tenant, email, password);
      };
      const [agentA, agentB] = await Promise.all([agent(first, 'unread-agent-a@example.test'), agent(second, 'unread-agent-b@example.test')]);

      await page.goto(`${nexusOrigin}/login`);
      await page.bringToFront();
      const empty = nextUnread(page);
      await signInNexus(page, primary);
      expect((await (await empty).json()).data.unread).toBe(0);
      await expect(page.getByRole('button', { name: '在线客服', exact: true })).toBeVisible();
      await page.getByRole('button', { name: '在线客服', exact: true }).click();
      await expect(page.getByRole('status')).toHaveText('已通过 Nexus 登录');
      const chatA = await startConversation(page, '租户 A 的未读验收');
      const back = nextUnread(page);
      await page.goBack();
      expect((await (await back).json()).data.unread).toBe(0);

      const otherContext = await fixtureContext();
      const other = await otherContext.newPage();
      const issue = await request.post('/integrations/nexus/launch-grants', {
        headers: { Authorization: `Bearer ${keyB}` }, data: { userId: Number(sub), sessionId: Number(sid) },
      });
      expect(issue.status()).toBe(201);
      const { grant } = await issue.json();
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
      }, { action: `${process.env.NEWCRISP_PUBLIC_ORIGIN}/launch/nexus`, grant });
      await expect(other.getByText(second.name)).toBeVisible();
      const chatB = await startConversation(other, '租户 B 的未读验收');
      await otherContext.close();
      await staffReplies(agentB, chatB, ['B 的第一条回复', 'B 的第二条回复']);
      const fromB = () => request.post('/integrations/nexus/unread-summary', {
        headers: { Authorization: `Bearer ${keyB}` }, data: { userId: Number(sub) },
      });
      expect(await (await fromB()).json()).toEqual({ unread: 2 });
      expect((await request.post('/integrations/nexus/unread-summary', { data: { userId: Number(sub) } })).status()).toBe(401);
      const tenantInjection = await request.post('/integrations/nexus/unread-summary', {
        headers: { Authorization: `Bearer ${keyB}` }, data: { userId: Number(sub), tenantId: 1 },
      });
      expect(tenantInjection.status()).toBe(400);

      const aOnly = await refreshNexusBadge(page);
      expect(aOnly.unread).toBe(0);
      await staffReplies(agentA, chatA, ['A 的一条客服回复']);
      expect((await refreshNexusBadge(page)).unread).toBe(1);
      const badge = page.getByRole('button', { name: '在线客服，1 条未读回复' });
      await expect(badge.locator('.chat-unread-count')).toHaveText('1');
      await page.getByRole('button', { name: '收起导航' }).click();
      await expect(badge.locator('.chat-unread-count')).toBeVisible();
      await page.getByRole('button', { name: '展开导航' }).click();

      let captured;
      const oldUnread = new Promise((resolve) => { captured = resolve; });
      const gate = new Promise((resolve) => { releaseOld = resolve; });
      let firstRequest = true;
      await page.route('**/api/support/chat/unread', async (route) => {
        if (!firstRequest) return route.continue();
        firstRequest = false;
        const response = await route.fetch();
        captured((await response.json()).data.unread);
        await gate;
        // The old XHR may have been aborted by the account switch.
        await route.fulfill({ response }).catch(() => {});
      });
      await page.evaluate(() => window.dispatchEvent(new Event('focus')));
      expect(await oldUnread).toBe(1);
      await page.getByRole('button', { name: '退出登录' }).click();
      await expect(page).toHaveURL(/\/login$/);
      const different = nextUnread(page);
      await signInNexus(page, alternate);
      const alternativeSummary = await different;
      expect((await alternativeSummary.json()).data.unread).toBe(0);
      const authorization = await alternativeSummary.request().headerValue('authorization');
      const spoofed = await request.get(`${nexusApi}/api/support/chat/unread?userId=${sub}&tenantCode=${second.code}`, {
        headers: { Authorization: authorization },
      });
      expect(spoofed.status()).toBe(200);
      expect((await spoofed.json()).data.unread).toBe(0);
      releaseOld();
      await page.unroute('**/api/support/chat/unread');
      expect((await refreshNexusBadge(page)).unread).toBe(0);
      await expect(page.locator('.chat-unread-count')).toHaveCount(0);

      await page.getByRole('button', { name: '退出登录' }).click();
      await expect(page).toHaveURL(/\/login$/);
      await signInNexus(page, primary);
      await badge.click();
      await expect(page.getByRole('list', { name: '消息' }).getByText('A 的一条客服回复')).toBeVisible();
      await expect.poll(async () => (await browserApi(page, '/api/v1/customer/summary')).data?.unread).toBe(0);
      const read = nextUnread(page);
      await page.goBack();
      expect((await (await read).json()).data.unread).toBe(0);
      await expect(page.getByRole('button', { name: '在线客服', exact: true })).toBeVisible();
      await expect(page.locator('.chat-unread-count')).toHaveCount(0);
      expect(await (await fromB()).json()).toEqual({ unread: 2 });
    } finally {
      releaseOld();
      await Promise.all(openContexts.map((context) => context.close()));
    }
  });

  test('a supervisor hands off an active conversation and the existing sockets switch ownership', async ({ page, browser, request }) => {
    test.setTimeout(100_000);
    const contexts = [];
    const open = async (tenant, email = tenant.adminEmail, password = tenant.adminPassword) => {
      const context = await browser.newContext({ baseURL: process.env.NEWCRISP_PUBLIC_ORIGIN });
      contexts.push(context);
      return openStaff(context, tenant, email, password);
    };
    const staff = '/api/v1/staff';
    const password = 'Transfer-Staff!2026';
    const initial = '转派前的客户原话';
    const firstReply = '原客服已处理的历史回复';
    const followUp = '转派后的客户补充';
    const secondReply = '新客服在原会话继续回复';
    const customerAccount = { email: 'transfer-customer@example.test', password: 'Nexus-Transfer!2026' };

    async function member(admin, address, displayName, role, departmentCode = 'support') {
      const email = `transfer-${address}@example.test`;
      const result = await write(admin, `${staff}/members`, { email, password, departmentCode, displayName, role });
      expect(result.status).toBe(201);
      return { email, id: result.data.staffId, displayName };
    }

    try {
      const [admin, otherAdmin] = await Promise.all([open(first), open(second)]);
      expect(await write(admin, `${staff}/departments`, { code: 'transfer-billing', name: '转派验收账单部门' }))
        .toMatchObject({ status: 201 });
      const [origin, target, manager, wrongDepartment, wrongSupervisor, wrongTenant] = await Promise.all([
        member(admin, 'original', '原负责人', 'AGENT'),
        member(admin, 'next', '接手负责人', 'AGENT'),
        member(admin, 'manager', '执行转派的主管', 'SUPERVISOR'),
        member(admin, 'billing', '外部门客服', 'AGENT', 'transfer-billing'),
        member(admin, 'billing-manager', '外部门主管', 'SUPERVISOR', 'transfer-billing'),
        member(otherAdmin, 'outsider', '外租户客服', 'AGENT'),
      ]);
      const registration = await request.post(`${nexusApi}/api/auth/register`, {
        data: { ...customerAccount, displayName: '会话转派验收客户' },
      });
      expect(registration.ok()).toBe(true);
      await page.goto(`${nexusOrigin}/login`);
      await signInNexus(page, customerAccount);
      await page.getByRole('button', { name: '在线客服' }).click();
      await expect(page.getByRole('status')).toHaveText('已通过 Nexus 登录');
      const id = await startConversation(page, initial);
      const path = `${staff}/conversations/${id}`;
      const customerPath = `/api/v1/customer/conversations/${id}`;
      const [original, incoming, supervisor, otherSupervisor, outsider] = await Promise.all([
        open(first, origin.email, password), open(first, target.email, password),
        open(first, manager.email, password), open(first, wrongSupervisor.email, password), open(second, wrongTenant.email, password),
      ]);
      await staffReplies(original, id, [firstReply]);
      const previous = (await browserApi(original, path)).data;

      await original.getByRole('button', { name: '我负责' }).click();
      await original.getByRole('button', { name: firstReply }).click();
      await expect(original.locator('.chat-log')).toContainText(initial);
      await expect(original.getByRole('button', { name: '发送', exact: true })).toBeVisible();
      await expect(original.getByRole('button', { name: '转派会话' })).toHaveCount(0);
      await incoming.getByRole('button', { name: '我负责' }).click();
      await expect(incoming.locator('.chat-connection')).toHaveText('');
      await expect(incoming.getByRole('button', { name: firstReply })).toHaveCount(0);
      expect((await browserApi(incoming, `${path}/messages`)).status).toBe(404);

      await supervisor.getByRole('button', { name: '部门会话' }).click();
      await supervisor.getByRole('button', { name: firstReply }).click();
      await expect(supervisor.getByRole('button', { name: '转派会话' })).toBeVisible();
      const candidatesPath = `${path}/transfer-candidates`;
      const candidates = (await browserApi(supervisor, candidatesPath)).data.map(({ id }) => id);
      expect(candidates).toContain(target.id);
      for (const excluded of [origin, manager, wrongDepartment, wrongTenant]) expect(candidates).not.toContain(excluded.id);

      const attempt = (targetStaffId) => ({ actionId: randomUUID(), stateVersion: previous.stateVersion, targetStaffId });
      const rejected = await Promise.all([
        write(supervisor, `${path}/transfer`, attempt(wrongDepartment.id)),
        write(supervisor, `${path}/transfer`, attempt(wrongTenant.id)),
        write(otherSupervisor, `${path}/transfer`, attempt(target.id)),
        write(outsider, `${path}/transfer`, attempt(wrongTenant.id)),
        write(original, `${path}/transfer`, attempt(target.id)),
        browserApi(otherSupervisor, candidatesPath),
        browserApi(outsider, candidatesPath),
      ]);
      expect(rejected.map(({ status }) => status)).toEqual([404, 404, 404, 404, 404, 404, 404]);
      expect((await browserApi(original, path)).data).toMatchObject({ assignee: origin.displayName, stateVersion: previous.stateVersion });

      const [oldSocket, newSocket] = await Promise.all([watchStaffSocket(original), watchStaffSocket(incoming)]);
      await expect.poll(() => socketState(original, oldSocket)).toEqual({ ready: true, closed: null });
      await expect.poll(() => socketState(incoming, newSocket)).toEqual({ ready: true, closed: null });
      const transferred = supervisor.waitForResponse((response) => response.url().endsWith(`${path}/transfer`));
      await supervisor.getByRole('button', { name: '转派会话' }).click();
      await supervisor.getByLabel('接手客服').selectOption(target.id);
      await supervisor.getByRole('button', { name: '确认转派' }).click();
      const response = await transferred;
      expect(response.status()).toBe(200);
      const next = await response.json();
      expect(next).toEqual({ conversationId: id, status: 'ACTIVE', stateVersion: (BigInt(previous.stateVersion) + 1n).toString() });
      await expect(supervisor.getByText('会话已转派。')).toBeVisible();
      expect(await write(supervisor, `${path}/transfer`, response.request().postDataJSON())).toEqual({ status: 200, data: next });

      await expect(incoming.getByRole('button', { name: firstReply })).toBeVisible();
      await incoming.getByRole('button', { name: firstReply }).click();
      await expect(incoming.locator('.chat-log')).toContainText(initial);
      await expect(incoming.locator('.chat-log')).toContainText(firstReply);
      await expect(incoming.getByRole('button', { name: '发送', exact: true })).toBeVisible();
      await expect(original.getByRole('button', { name: firstReply })).toHaveCount(0);
      await expect(original.locator('.chat-log')).toBeEmpty();
      await expect(original.getByRole('button', { name: '发送', exact: true })).toHaveCount(0);
      await expect(page.locator('.chat-assignee')).toHaveText(`负责人 ${target.displayName}`);

      await incoming.getByText('操作记录', { exact: true }).click();
      const history = incoming.locator('.chat-history');
      await expect(history.getByRole('listitem').filter({ hasText: '转派' })).toContainText(
        `${origin.displayName}（${origin.email}） → ${target.displayName}（${target.email}）`);
      const actions = (await browserApi(incoming, `${path}/actions`)).data;
      expect(actions.map(({ action }) => action)).toEqual(['TRANSFER', 'CLAIM']);
      expect(actions[0]).toMatchObject({ operator: manager.displayName, previousAssignee: origin, newAssignee: target });
      for (const suffix of ['', '/messages', '/actions']) expect((await browserApi(original, path + suffix)).status).toBe(404);
      expect(await sendOnStaffSocket(original, oldSocket, {
        type: 'message.send', conversationId: id, stateVersion: next.stateVersion, text: '原客服越权回复',
      })).toMatchObject({ type: 'error', data: { code: 'NOT_FOUND', status: 404 } });

      expect((await write(page, `${customerPath}/messages`, { clientMessageId: randomUUID(), text: followUp })).status).toBe(201);
      await expect.poll(() => staffSocketMessages(incoming, newSocket)).toContain(followUp);
      await expect(incoming.locator('.chat-log')).toContainText(followUp);
      await incoming.getByRole('textbox', { name: '消息' }).fill(secondReply);
      await incoming.getByRole('button', { name: '发送', exact: true }).click();
      await expect(page.locator('.chat-log')).toContainText(secondReply);
      expect(await staffSocketMessages(original, oldSocket)).not.toContain(followUp);
      expect(await staffSocketMessages(original, oldSocket)).not.toContain(secondReply);
      expect(await socketState(original, oldSocket)).toEqual({ ready: true, closed: null });
      const transcript = (await browserApi(page, `${customerPath}/messages`)).data.items;
      expect(transcript.map(({ text }) => text)).toEqual([initial, firstReply, followUp, secondReply]);
    } finally {
      await Promise.all(contexts.map((context) => context.close()));
    }
  });
  test('new conversations balance concurrent routing and wait locally when nobody eligible is online', async ({ browser, request }) => {
    test.setTimeout(150_000);
    const tenantA = { ...first, code: 'route-a', name: '自动分配甲租户' };
    const tenantB = { ...second, code: 'route-b', name: '自动分配乙租户' };
    const staff = '/api/v1/staff';
    const chats = '/api/v1/customer/conversations';
    const password = 'Route-Agent!2026';
    const agentOne = 'route-one@example.test';
    const agentTwo = 'route-two@example.test';
    const otherDepartment = 'route-department@example.test';
    const contexts = [];
    async function context() {
      const value = await browser.newContext({ baseURL: process.env.NEWCRISP_PUBLIC_ORIGIN });
      contexts.push(value);
      return value;
    }
    async function staffPage(tenant, email = tenant.adminEmail, credential = tenant.adminPassword) {
      return openStaff(await context(), tenant, email, credential);
    }
    async function addAgent(admin, email, displayName, departmentCode = 'support') {
      const result = await write(admin, `${staff}/members`, { departmentCode, email, password, role: 'AGENT', displayName });
      expect(result).toMatchObject({ status: 201 });
      return result.data.staffId;
    }
    async function launch(account, key, name) {
      const login = await request.post(`${nexusApi}/api/auth/login`, { data: account });
      expect(login.ok()).toBe(true);
      const { accessToken } = (await login.json()).data;
      const { sub, sid } = JSON.parse(Buffer.from(accessToken.split('.')[1], 'base64url').toString('utf8'));
      const tab = await (await context()).newPage();
      await tab.goto(`${nexusOrigin}/login`, { waitUntil: 'domcontentloaded' });
      const issued = await request.post('/integrations/nexus/launch-grants', {
        headers: { Authorization: `Bearer ${key}` },
        data: { userId: Number(sub), sessionId: Number(sid) },
      });
      expect(issued.status()).toBe(201);
      const { grant } = await issued.json();
      await tab.evaluate(({ action, grant }) => {
        const form = document.createElement('form');
        form.method = 'POST';
        form.action = action;
        const field = document.createElement('input');
        field.type = 'hidden';
        field.name = 'grant';
        field.value = grant;
        form.append(field);
        document.body.append(form);
        form.submit();
      }, { action: `${process.env.NEWCRISP_PUBLIC_ORIGIN}/launch/nexus`, grant });
      await expect(tab.getByRole('status')).toHaveText('已通过 Nexus 登录');
      await expect(tab.getByText(name, { exact: true })).toBeVisible();
      return tab;
    }
    const assigned = async (tab) => {
      const response = await browserApi(tab, `${staff}/conversations?view=MINE`);
      expect(response.status).toBe(200);
      return response.data.items.map(({ id }) => id);
    };
    function detail(tab, id) { return browserApi(tab, `${chats}/${id}`); }

    try {
      await Promise.all([createTenant(request, tenantA), createTenant(request, tenantB)]);
      const [keyA, keyB] = await Promise.all([
        configureNexus(request, tenantA.code, nexusOrigin), configureNexus(request, tenantB.code, nexusOrigin),
      ]);
      const [adminA, adminB] = await Promise.all([staffPage(tenantA), staffPage(tenantB)]);
      expect(await write(adminA, `${staff}/departments`, { code: 'route-billing', name: '非目标部门' })).toMatchObject({ status: 201 });
      const [firstAgentId] = await Promise.all([
        addAgent(adminA, agentOne, '甲一号'), addAgent(adminA, agentTwo, '甲二号'),
        addAgent(adminA, otherDepartment, '外部门在线客服', 'route-billing'), addAgent(adminB, agentTwo, '乙客服'),
      ]);
      const [one, billing, outside] = await Promise.all([
        staffPage(tenantA, agentOne, password), staffPage(tenantA, otherDepartment, password), staffPage(tenantB, agentTwo, password),
      ]);
      await Promise.all([one, billing, outside].map((tab) => expect(tab.locator('.chat-connection')).toHaveText('')));
      await one.getByRole('button', { name: '我负责' }).click();

      const accounts = Array.from({ length: 6 }, (_, i) => ({
        email: `routing-customer-${i}@example.test`, password: 'Nexus-Routing!2026',
      }));
      for (const account of accounts) {
        const registered = await request.post(`${nexusApi}/api/auth/register`, { data: { ...account, displayName: '自动分配验收' } });
        expect(registered.ok()).toBe(true);
      }

      const seed = await launch(accounts[0], keyA, tenantA.name);
      await expect(seed.locator('.chat-connection')).toHaveText('');
      await seed.getByRole('textbox', { name: '消息' }).fill('仅一号客服在线时发起');
      await seed.getByRole('button', { name: '发送', exact: true }).click();
      await expect(seed.locator('.chat-assignee')).toHaveText('负责人 甲一号');
      await expect(one.getByRole('button', { name: /仅一号客服在线时发起/ })).toBeVisible();
      const [seedId] = await assigned(one);
      const history = await browserApi(one, `${staff}/conversations/${seedId}/actions`);
      expect(history.data).toMatchObject([{ action: 'AUTO', operator: '系统', newAssignee: { id: firstAgentId } }]);

      const two = await staffPage(tenantA, agentTwo, password);
      await expect(two.locator('.chat-connection')).toHaveText('');
      const light = await launch(accounts[1], keyA, tenantA.name);
      const lightId = await startConversation(light, '优先给进行中会话更少的客服');
      expect(await detail(light, lightId)).toMatchObject({ status: 200, data: { status: 'ACTIVE', assignee: '甲二号' } });

      const concurrent = await Promise.all(accounts.slice(2).map((account) => launch(account, keyA, tenantA.name)));
      const results = await Promise.all(concurrent.flatMap((tab, index) => {
        const payload = { text: `并发发起 ${index + 1}`, clientMessageId: randomUUID() };
        const send = write(tab, chats, payload);
        return index === 0 ? [send, write(tab, chats, payload)] : [send];
      }));
      expect(results.map(({ status }) => status).sort()).toEqual([200, 201, 201, 201, 201]);
      const wave = results.map(({ data }) => data.message.conversationId);
      expect(wave[0]).toBe(wave[1]);
      expect(new Set(wave).size).toBe(4);
      const [listOne, listTwo] = await Promise.all([assigned(one), assigned(two)]);
      expect([listOne.length, listTwo.length]).toEqual([3, 3]);
      expect([...listOne, ...listTwo].sort()).toEqual([seedId, lightId, ...new Set(wave)].sort());
      expect((await browserApi(one, `${staff}/conversations?view=PENDING`)).data.items).toEqual([]);
      expect(await assigned(billing)).toEqual([]);
      expect(await assigned(outside)).toEqual([]);

      // Cleanly close one workbench and sign the other out. Other departments and tenants are still online.
      await one.context().close();
      await two.getByRole('button', { name: '退出登录' }).click();
      await expect(two.getByRole('button', { name: '登录', exact: true })).toBeVisible();
      const [waiting, foreign] = await Promise.all([
        launch(customer, keyA, tenantA.name), launch(customer, keyB, tenantB.name),
      ]);
      const [waitingId, foreignId] = await Promise.all([
        startConversation(waiting, '本部门无人在线，等待人工领取'),
        startConversation(foreign, '同一客户在乙租户由乙客服接手'),
      ]);
      expect(await detail(waiting, waitingId)).toMatchObject({ status: 200, data: { status: 'WAITING', assignee: null } });
      expect(await detail(foreign, foreignId)).toMatchObject({ status: 200, data: { status: 'ACTIVE', assignee: '乙客服' } });
      expect(await assigned(outside)).toEqual([foreignId]);
      expect((await browserApi(outside, `${staff}/conversations/${waitingId}`)).status).toBe(404);
      expect((await browserApi(billing, `${staff}/conversations?view=PENDING`)).data.items).toEqual([]);
      expect((await browserApi(waiting, `${chats}/${foreignId}`)).status).toBe(404);

      const returning = await staffPage(tenantA, agentTwo, password);
      const queued = returning.getByRole('row').filter({ hasText: '本部门无人在线，等待人工领取' });
      await expect(queued.getByRole('button', { name: '领取', exact: true })).toBeVisible();
      await queued.getByRole('button', { name: '领取', exact: true }).click();
      await expect(waiting.locator('.chat-assignee')).toHaveText('负责人 甲二号');
      expect(await assigned(returning)).toContain(waitingId);
    } finally {
      await Promise.all(contexts.map((item) => item.close()));
    }
  });
  test('an authenticated Nexus customer can safely take over a guest conversation', async ({ page, request, browser }) => {
    test.setTimeout(55_000);
    const adminContext = await browser.newContext({ baseURL: process.env.NEWCRISP_PUBLIC_ORIGIN });
    const guestContext = await browser.newContext({ baseURL: process.env.NEWCRISP_PUBLIC_ORIGIN });
    try {
      const admin = await openStaff(adminContext, first, first.adminEmail, first.adminPassword);
      const { status, data: site } = await write(admin, '/api/v1/staff/guest-sites', { title: '访客接续验收', origin: nexusOrigin });
      expect(status).toBe(201);
      const guest = await guestContext.newPage();
      await guest.goto(`/embed/${site.id}`);
      const issued = guest.waitForResponse((response) => response.url().includes('/api/v1/guest/') && response.ok());
      await guest.getByRole('button', { name: '开始聊天' }).click();
      const guestSession = await (await issued).json();
      await expect(guest.locator('.chat-connection')).toHaveText('');
      await guest.getByRole('textbox', { name: '消息' }).fill('这是登录前的访客历史');
      await guest.getByRole('button', { name: '发送', exact: true }).click();
      await expect(guest.locator('.chat-log')).toContainText('这是登录前的访客历史');
      await guest.getByRole('button', { name: '显示访客接续码' }).click();
      const code = await guest.getByLabel('访客接续码').inputValue();
      const account = { email: 'linked-guest@example.test', password: 'Nexus-Guest!2026' };
      expect((await request.post(`${nexusApi}/api/auth/register`, { data: { ...account, displayName: '访客接续验收' } })).ok()).toBe(true);
      await page.goto(`${nexusOrigin}/login`);
      await signInNexus(page, account);
      await page.getByRole('button', { name: '在线客服' }).click();
      await expect(page.getByRole('status')).toHaveText('已通过 Nexus 登录');
      await page.getByText('接续访客对话', { exact: true }).click();
      await page.getByLabel('访客接续码').fill(code);
      await page.getByRole('button', { name: '接续访客会话' }).click();
      await expect(page.getByText('访客会话已接续，可在会话列表查看。')).toBeVisible();
      await expect(page.locator('.chat-log')).toContainText('这是登录前的访客历史');
      await page.getByText('接续访客对话', { exact: true }).click();
      await expect(page.getByLabel('访客接续码')).toHaveValue('');
      expect((await request.get('/api/v1/customer/me', {
        headers: { Authorization: `Bearer ${guestSession.token.accessToken}` },
      })).status()).toBe(401);
      expect((await write(page, '/api/v1/customer/guest-link', { code })).status).toBe(404);
    } finally {
      await Promise.all([adminContext.close(), guestContext.close()]);
    }
  });
});

async function openStaff(context, tenant, email, password) {
  const tab = await context.newPage();
  await tab.goto('/staff.html');
  await tab.getByLabel('租户代码').fill(tenant.code);
  await tab.getByLabel('邮箱', { exact: true }).fill(email);
  await tab.getByLabel('密码', { exact: true }).fill(password);
  await tab.getByRole('button', { name: '登录', exact: true }).click();
  await expect(tab.getByRole('heading', { name: email === tenant.adminEmail ? '租户管理' : '客服工作台', exact: true })).toBeVisible();
  return tab;
}

async function signInNexus(page, account) {
  await page.getByPlaceholder('请输入邮箱').fill(account.email);
  await page.getByPlaceholder('请输入密码').fill(account.password);
  await page.getByRole('button', { name: '登录', exact: true }).click();
}

function nextUnread(page) {
  return page.waitForResponse((response) => response.url() === `${nexusOrigin}/api/support/chat/unread` && response.ok(), { timeout: 12_000 });
}

async function refreshNexusBadge(page) {
  const fresh = nextUnread(page);
  await page.bringToFront();
  await page.evaluate(() => window.dispatchEvent(new Event('focus')));
  const response = await fresh;
  expect(response.headers()['cache-control']).toBe('no-store');
  return (await response.json()).data;
}

async function startConversation(page, text) {
  const response = await write(page, '/api/v1/customer/conversations', { text, clientMessageId: randomUUID() });
  expect(response.status).toBe(201);
  return response.data.message.conversationId;
}

async function staffReplies(page, conversationId, texts) {
  const path = `/api/v1/staff/conversations/${conversationId}`;
  let current = await browserApi(page, path);
  if (current.status === 404) {
    const list = await browserApi(page, '/api/v1/staff/conversations?view=PENDING');
    const item = list.data.items.find((row) => row.id === conversationId);
    expect(item).toBeDefined();
    current = await write(page, `${path}/claim`, { actionId: randomUUID(), stateVersion: item.stateVersion });
  } else {
    expect(current.data).toMatchObject({ status: 'ACTIVE', canReply: true });
  }
  expect(current.status).toBe(200);
  for (const text of texts) {
    const reply = await write(page, `${path}/messages`, { text, clientMessageId: randomUUID(), stateVersion: current.data.stateVersion });
    expect(reply.status).toBe(201);
  }
}

async function redeem(request, grant, origin = nexusOrigin) {
  const response = await request.post('/launch/nexus', {
    headers: { Origin: origin }, form: { grant }, maxRedirects: 0,
  });
  return response.status();
}

async function createMember(page, email, password, role) {
  await page.getByRole('button', { name: '新增员工' }).click();
  await page.getByLabel('成员邮箱').fill(email);
  await page.getByLabel('初始密码').fill(password);
  await page.getByLabel('职责').selectOption(role);
  const created = page.waitForResponse((response) => response.url().endsWith('/api/v1/staff/members') && response.request().method() === 'POST');
  await page.getByRole('button', { name: '创建成员' }).click();
  expect((await created).status()).toBe(201);
  await expect(page.getByRole('status')).toHaveText('成员已创建。');
}

function write(page, path, body, method = 'POST') {
  return browserApi(page, path, { method, body: JSON.stringify(body), headers: { 'Content-Type': 'application/json' } });
}

function watchStaffSocket(page, ticket) {
  return page.evaluate(async (previousTicket) => {
    const auth = (await import('/web-session.js')).webSession('staff');
    const grant = previousTicket ?? (await (await auth.request('/api/v1/staff/ws-ticket', { method: 'POST' })).json()).ticket;
    const socket = new WebSocket(new URL('/ws/staff', location.href));
    const state = { ready: false, closed: null, socket, messages: [] };
    const key = crypto.randomUUID();
    window.staffSocketChecks ??= {};
    window.staffSocketChecks[key] = state;
    socket.onopen = () => socket.send(JSON.stringify({ type: 'auth', ticket: grant }));
    socket.onmessage = ({ data }) => {
      const frame = JSON.parse(data);
      if (frame.type === 'ready') state.ready = true;
      if (frame.type === 'ping') socket.send(JSON.stringify({ type: 'pong', nonce: frame.data }));
      if (frame.type === 'message.created') state.messages.push(frame.data.text);
    };
    socket.onclose = (event) => { state.closed = event.code; };
    return key;
  }, ticket);
}

function socketState(page, key) {
  return page.evaluate((id) => {
    const { ready, closed } = window.staffSocketChecks[id];
    return { ready, closed };
  }, key);
}

function staffSocketMessages(page, key) {
  return page.evaluate((id) => window.staffSocketChecks[id].messages, key);
}

function sendOnStaffSocket(page, key, payload) {
  return page.evaluate(({ key, payload }) => {
    const { socket } = window.staffSocketChecks[key];
    const requestId = crypto.randomUUID();
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        socket.removeEventListener('message', receive);
        reject(new Error('Existing socket did not answer the command'));
      }, 5_000);
      function receive(event) {
        const frame = JSON.parse(event.data);
        if (frame.requestId !== requestId) return;
        socket.removeEventListener('message', receive);
        clearTimeout(timer);
        resolve(frame);
      }
      socket.addEventListener('message', receive);
      socket.send(JSON.stringify({ ...payload, requestId, clientMessageId: crypto.randomUUID() }));
    });
  }, { key, payload });
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
  return page.evaluate(async (message) => {
    const auth = (await import('/web-session.js')).webSession('staff');
    const response = await auth.request('/api/v1/staff/ws-ticket', { method: 'POST' });
    if (!response.ok) throw new Error(`Socket ticket returned ${response.status}`);
    const { ticket } = await response.json();
    return new Promise((resolve, reject) => {
    const url = new URL('/ws/staff', location.href);
    url.protocol = location.protocol === 'https:' ? 'wss:' : 'ws:';
    const connection = new WebSocket(url);
    const requestId = crypto.randomUUID();
    const timer = setTimeout(() => { connection.close(); reject(new Error('Socket command timed out')); }, 5_000);
    connection.onopen = () => connection.send(JSON.stringify({ type: 'auth', ticket }));
    connection.onmessage = (event) => {
      const row = JSON.parse(event.data);
      if (row.type === 'ready') connection.send(JSON.stringify({ ...message, requestId, clientMessageId: crypto.randomUUID() }));
      if (row.type === 'ping') connection.send(JSON.stringify({ type: 'pong', nonce: row.data }));
      if (row.requestId !== requestId) return;
      clearTimeout(timer);
      connection.close();
      resolve(row);
    };
    });
  }, payload);
}
