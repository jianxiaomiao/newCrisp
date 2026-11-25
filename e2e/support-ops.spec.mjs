import { expect, test } from '@playwright/test';
import { createTenant } from './support/platform.mjs';
import { browserApi } from './support/browser-api.mjs';
import { randomUUID } from 'node:crypto';
import { createServer } from 'node:http';
import { once } from 'node:events';

const tenant = { code: 'ops-a', name: '接待功能验收', adminEmail: 'ops-a@example.test', adminPassword: 'Support-Admin!2026' };
const other = { ...tenant, code: 'ops-b', adminEmail: 'ops-b@example.test' };
const agent = { email: 'ops-agent@example.test', password: 'Support-Agent!2026' };
const adminApi = '/api/v1/staff';
const image = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+tmPwAAAAASUVORK5CYII=', 'base64');

function apiWrite(page, path, data, method = 'POST') {
  return browserApi(page, path, { method, body: JSON.stringify(data), headers: { 'Content-Type': 'application/json' } });
}

async function login(page, tenantCode, credentials, administrator = false) {
  await page.goto('/staff.html');
  await page.getByLabel('租户代码').fill(tenantCode);
  await page.getByLabel('邮箱').fill(credentials.email);
  await page.getByLabel('密码').fill(credentials.password);
  await page.getByRole('button', { name: '登录', exact: true }).click();
  await expect(page.getByRole('heading', { name: administrator ? '租户管理' : '客服工作台', exact: true })).toBeVisible();
}

async function embedded(page, origin, site) {
  await page.goto(`${origin}/site/${site}`);
  return page.frameLocator('iframe');
}

async function startGuest(page, origin, site) {
  const faults = [];
  page.on('pageerror', (error) => faults.push(error.message));
  page.on('console', (message) => { if (message.type() === 'error') faults.push(message.text()); });
  page.on('requestfailed', (request) => faults.push(`${request.url()} ${request.failure()?.errorText}`));
  const guest = await embedded(page, origin, site);
  await expect(guest.getByRole('button', { name: '开始聊天' }), faults.join('\n') || page.frames().map((entry) => entry.url()).join('\n')).toBeVisible();
  const issued = page.waitForResponse((response) => response.url().endsWith(`/guest/${site}/session`), { timeout: 10_000 });
  await guest.getByRole('button', { name: '开始聊天' }).click();
  const response = await issued;
  expect(response.status()).toBe(200);
  const session = await response.json();
  await expect(guest.locator('.chat-connection')).toHaveText('');
  return { guest, session };
}

test('service rules, notifications, quick replies, attachments, and embedded guests share the real queue', async ({ page: admin, request, browser }) => {
  test.setTimeout(100_000);
  const contexts = [];
  const siteServer = createServer((req, res) => {
    const match = req.url.match(/^\/site\/([a-f0-9-]{36})$/);
    if (!match) { res.writeHead(404).end(); return; }
    res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' }).end(
      `<!doctype html><iframe title="在线客服" src="${process.env.NEWCRISP_PUBLIC_ORIGIN}/embed/${match[1]}" width="780" height="700"></iframe>`);
  });
  siteServer.listen(0, '127.0.0.1');
  await once(siteServer, 'listening');
  const siteOrigin = `http://localhost:${siteServer.address().port}`;
  async function tab() {
    const context = await browser.newContext({ baseURL: process.env.NEWCRISP_PUBLIC_ORIGIN });
    contexts.push(context);
    return context.newPage();
  }
  try {
    await createTenant(request, tenant);
    await createTenant(request, other);
    await login(admin, tenant.code, { email: tenant.adminEmail, password: tenant.adminPassword }, true);
    const outsider = await tab();
    await login(outsider, other.code, { email: other.adminEmail, password: other.adminPassword }, true);
    expect(await apiWrite(admin, `${adminApi}/members`, {
      departmentCode: 'support', displayName: '一号客服', role: 'AGENT', ...agent,
    })).toMatchObject({ status: 201 });

    await admin.getByText('共享快捷回复', { exact: true }).click();
    await admin.getByLabel('快捷回复标题').fill('核查订单');
    await admin.getByLabel('快捷回复正文').fill('您好，请提供订单编号。');
    await admin.getByRole('button', { name: '保存快捷回复' }).click();
    await expect(admin.getByText('快捷回复已保存。')).toBeVisible();
    expect((await browserApi(outsider, `${adminApi}/quick-replies`)).data).toEqual([]);
    const tomorrow = new Date(Date.now() + 86400000).getUTCDay() || 7;
    const settings = { zone: 'UTC', windows: [{ day: tomorrow, start: '09:00', end: '10:00' }], maxActive: 1, fillQueue: true };
    expect(await apiWrite(admin, `${adminApi}/support-settings`, settings, 'PUT')).toMatchObject({ status: 204 });
    const site = (await apiWrite(admin, `${adminApi}/guest-sites`, { title: '网站访客服务', origin: siteOrigin })).data;
    expect(site.id).toBeTruthy();
    const iframe = await request.get(`/embed/${site.id}`);
    expect(iframe.headers()['content-security-policy']).toContain(`frame-ancestors ${siteOrigin}`);
    expect((await request.get('/guest.html')).status()).toBe(404);
    const wrongSite = await tab();
    const blocked = wrongSite.waitForEvent('requestfailed', {
      predicate: (req) => req.url().endsWith(`/embed/${site.id}`), timeout: 10_000,
    });
    await wrongSite.goto(`${siteOrigin.replace('localhost', '127.0.0.1')}/site/${site.id}`);
    await blocked;

    const employee = await tab();
    await login(employee, tenant.code, agent);
    await expect(employee.locator('.chat-connection')).toHaveText('');
    await employee.getByRole('button', { name: '暂停接单' }).click();
    await expect(employee.getByRole('button', { name: '恢复接单' })).toBeVisible();
    await employee.evaluate(() => {
      window.testNotices = [];
      window.Notification = class {
        static permission = 'granted';
        static requestPermission = async () => 'granted';
        constructor(title) { window.testNotices.push(title); }
        close() {}
      };
    });
    await employee.getByRole('button', { name: '开启客服通知' }).click();
    await expect(employee.getByRole('button', { name: '关闭客服通知' })).toBeVisible();
    await employee.evaluate(() => Object.defineProperty(document, 'hidden', { configurable: true, value: true }));

    const guestPage = await tab();
    const { guest, session } = await startGuest(guestPage, siteOrigin, site.id);
    await expect(guest.locator('html')).toHaveCSS('--el-color-primary', '#276b58');
    await expect(guest.getByText(/非营业时间.*可以继续留言/)).toBeVisible();
    await expect(guest.getByText(/下次接待/)).toBeVisible();
    await guest.getByRole('textbox', { name: '消息' }).fill('访客一的订单');
    await guest.getByRole('button', { name: '发送', exact: true }).click();
    await expect(guest.getByRole('heading', { name: '等待客服' })).toBeVisible();
    await expect(employee.getByText('访客一的订单', { exact: true })).toBeVisible();
    expect(await apiWrite(admin, `${adminApi}/support-settings`, { ...settings, windows: null }, 'PUT')).toMatchObject({ status: 204 });
    await employee.getByRole('button', { name: '恢复接单' }).click();
    await expect(guest.locator('.chat-assignee')).toHaveText('负责人 一号客服');
    await expect.poll(() => employee.evaluate(() => window.testNotices)).toEqual(['有新客服会话']);

    await employee.getByRole('button', { name: '我负责' }).click();
    await employee.getByRole('button', { name: /访客一的订单/ }).click();
    await employee.getByRole('button', { name: '快捷回复', exact: true }).click();
    await employee.getByLabel('搜索快捷回复').fill('订单');
    await employee.getByRole('button', { name: '核查订单', exact: true }).click();
    await expect(employee.getByRole('textbox', { name: '消息' })).toHaveValue('您好，请提供订单编号。');
    await employee.getByRole('textbox', { name: '消息' }).fill('您好，请提供订单编号和截图。');
    await employee.getByRole('button', { name: '发送', exact: true }).click();
    await expect(guest.locator('.chat-log')).toContainText('您好，请提供订单编号和截图。');

    await guest.getByLabel('选择附件').setInputFiles({ name: '订单截图.png', mimeType: 'image/png', buffer: image });
    await guest.getByRole('button', { name: '发送附件' }).click();
    await expect(employee.locator('.chat-log')).toContainText('订单截图.png');
    await employee.getByRole('button', { name: '预览图片' }).click();
    await expect(employee.getByRole('img', { name: '订单截图.png' })).toBeVisible();
    const mine = (await browserApi(employee, `${adminApi}/conversations?view=MINE`)).data.items[0];
    expect((await browserApi(outsider, `${adminApi}/conversations/${mine.id}/attachments/3`)).status).toBe(404);
    const files = `/api/v1/customer/conversations/${mine.id}/attachments`;
    const headers = { Authorization: `Bearer ${session.token.accessToken}`, Origin: process.env.NEWCRISP_PUBLIC_ORIGIN };
    const pdf = { name: '订单.pdf', mimeType: 'application/pdf', buffer: Buffer.from('%PDF-1.7\n%%EOF\n') };
    const fileKey = randomUUID();
    expect((await request.post(files, { headers, multipart: { file: pdf, clientMessageId: fileKey } })).status()).toBe(201);
    expect((await request.post(files, { headers, multipart: { file: pdf, clientMessageId: fileKey } })).status()).toBe(200);
    const savedPdf = await request.get(`${files}/4`, { headers });
    expect(savedPdf.headers()['content-type']).toBe('application/pdf');
    expect(await savedPdf.body()).toEqual(pdf.buffer);

    await guestPage.reload();
    await expect(guest.locator('.chat-log')).toContainText('访客一的订单');
    await expect(guest.locator('.chat-log')).toContainText('订单截图.png');

    const nextPage = await tab();
    const { guest: nextGuest } = await startGuest(nextPage, siteOrigin, site.id);
    await nextGuest.getByRole('textbox', { name: '消息' }).fill('满载时的第二个会话');
    await nextGuest.getByRole('button', { name: '发送', exact: true }).click();
    await expect(nextGuest.getByRole('heading', { name: '等待客服' })).toBeVisible();
    expect((await browserApi(employee, `${adminApi}/conversations?view=MINE`)).data.items).toHaveLength(1);
    const path = `${adminApi}/conversations/${mine.id}`;
    const current = (await browserApi(employee, path)).data;
    expect(await apiWrite(employee, `${path}/read-position`, { stateVersion: current.stateVersion, upToSeq: current.lastSeq }, 'PUT')).toMatchObject({ status: 204 });
    await employee.getByRole('button', { name: '结束会话', exact: true }).click();
    await employee.getByRole('dialog', { name: '结束会话' }).getByRole('button', { name: '确定', exact: true }).click();
    await expect(employee.getByRole('heading', { name: '已结束', exact: true })).toBeVisible();
    await expect(nextGuest.locator('.chat-assignee')).toHaveText('负责人 一号客服');
  } finally {
    await Promise.all(contexts.map((context) => context.close()));
    await new Promise((resolve) => siteServer.close(resolve));
  }
});
