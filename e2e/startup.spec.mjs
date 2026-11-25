import { execFileSync } from 'node:child_process';
import { expect, test } from '@playwright/test';

test('the application is ready after Flyway creates the tenant table in MySQL', async ({ page }) => {
  const response = await page.goto('/actuator/health/readiness');
  expect(response.status()).toBe(200);
  expect(await response.json()).toEqual({ status: 'UP' });

  const results = execFileSync('docker', [
    'exec', process.env.NEWCRISP_E2E_MYSQL_CONTAINER, 'sh', '-c',
    'MYSQL_PWD="$MYSQL_PASSWORD" exec mysql --user="$MYSQL_USER" "$MYSQL_DATABASE" --batch --skip-column-names --execute="$1"',
    'mysql-fixture',
    "SELECT version, success FROM flyway_schema_history ORDER BY installed_rank; SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='tenant';",
  ], { encoding: 'utf8', timeout: 10_000 });

  expect(results.trim().split(/\r?\n/)).toEqual([...Array.from({ length: 12 }, (_, i) => `${i + 1}\t1`), '1']);
});

test('the packaged Vue entry pages load the theme and fit a phone viewport', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 800 });
  for (const entry of ['/staff.html', '/customer.html']) {
    await page.goto(entry);
    await expect(page.locator('link[rel="stylesheet"][href^="/assets/"]')).toHaveCount(1);
    await expect(page.locator('html')).toHaveCSS('--el-color-primary', '#276b58');
    await expect(page.locator('html')).toHaveCSS('background-color', 'rgb(244, 246, 239)');
    if (entry === '/staff.html') {
      await expect(page.getByRole('button', { name: '登录', exact: true })).toHaveCSS('background-color', 'rgb(39, 107, 88)');
    }
    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBe(390);
  }
});
