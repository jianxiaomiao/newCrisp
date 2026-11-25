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
    "SELECT version, success FROM flyway_schema_history WHERE version = '1'; SELECT COUNT(*) FROM tenant;",
  ], { encoding: 'utf8', timeout: 10_000 });

  expect(results.trim().split(/\r?\n/)).toEqual(['1\t1', '0']);
});
