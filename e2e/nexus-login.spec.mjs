import { expect, test } from '@playwright/test';
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
});

async function redeem(request, grant, origin = nexusOrigin) {
  const response = await request.post('/launch/nexus', {
    headers: { Origin: origin }, form: { grant }, maxRedirects: 0,
  });
  return response.status();
}
