import { expect, test } from '@playwright/test';
import { createTenant, platformHeaders } from './support/platform.mjs';
import { browserApi } from './support/browser-api.mjs';

test('platform provisions an isolated tenant and its first administrator logs in and out', async ({ page, request }) => {
  const first = { code: 'staff-a', name: '华东支持', adminEmail: 'owner-a@example.test', adminPassword: 'Provisioned-Login!2026' };
  const second = { code: 'staff-b', name: '华南支持', adminEmail: 'owner-b@example.test', adminPassword: 'Different-Login!2026' };
  const absent = await request.post('/api/v1/platform/tenants', { data: first });
  expect(absent.status()).toBe(401);
  await createTenant(request, first);
  await createTenant(request, second);
  const duplicate = await request.post('/api/v1/platform/tenants', { headers: platformHeaders, data: first });
  expect(duplicate.status()).toBe(409);

  await page.goto('/staff.html');
  await page.getByLabel('租户代码').fill(second.code);
  await page.getByLabel('邮箱', { exact: true }).fill(first.adminEmail);
  await page.getByLabel('密码', { exact: true }).fill(first.adminPassword);
  await page.getByRole('button', { name: '登录', exact: true }).click();
  await expect(page.getByRole('status')).toHaveText('租户代码、邮箱或密码有误。');

  await page.getByLabel('租户代码').fill(first.code);
  await page.getByRole('button', { name: '登录', exact: true }).click();
  await expect(page.getByRole('heading', { name: '租户管理' })).toBeVisible();
  await expect(page.getByText(first.name)).toBeVisible();
  expect((await browserApi(page, '/api/v1/staff/tenants/staff-a')).status).toBe(200);
  expect((await browserApi(page, '/api/v1/staff/tenants/staff-b')).status).toBe(404);
  expect((await browserApi(page, '/api/v1/customer/me')).status).toBe(401);
  expect(await page.evaluate(async () => (await fetch('/api/v1/staff/me')).status)).toBe(401);
  expect((await browserApi(page, '/api/v1/staff/session', { method: 'DELETE' })).status).toBe(403);
  await page.getByLabel('成员邮箱').fill('staff-agent@example.test');
  await page.getByLabel('初始密码').fill('Temporary-Password!2026');
  await page.getByRole('button', { name: '创建成员' }).click();
  await expect(page.getByRole('status')).toHaveText('成员已创建。');
  expect((await browserApi(page, '/api/v1/platform/tenants', {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(first),
  })).status).toBe(401);

  const cookies = await page.context().cookies();
  expect(cookies.find((cookie) => cookie.name === '__Host-newcrisp_staff_rt')).toMatchObject({ secure: true, httpOnly: true, sameSite: 'Lax' });
  await page.getByRole('button', { name: '退出登录' }).click();
  await expect(page.getByRole('heading', { name: '租户管理员登录' })).toBeVisible();
  expect((await browserApi(page, '/api/v1/staff/me')).status).toBe(401);
});
