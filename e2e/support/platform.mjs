import { expect } from '@playwright/test';

export const platformHeaders = { Authorization: `Bearer ${process.env.NEWCRISP_E2E_PLATFORM_KEY}` };

export async function createTenant(request, { code, name, adminEmail, adminPassword }) {
  const response = await request.post('/api/v1/platform/tenants', {
    headers: platformHeaders,
    data: { code, name, adminEmail, adminPassword },
  });
  expect(response.status()).toBe(201);
  expect(await response.json()).toEqual({ code, name });
}

export async function configureNexus(request, tenantCode, nexusOrigin) {
  const response = await request.post(`/api/v1/platform/tenants/${tenantCode}/nexus-integrations`, {
    headers: platformHeaders,
    data: { nexusOrigin },
  });
  expect(response.status()).toBe(201);
  return (await response.json()).credential;
}
