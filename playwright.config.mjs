import { defineConfig } from '@playwright/test';

const port = process.env.NEWCRISP_E2E_PORT ?? '18760';
const databaseContainer = process.env.NEWCRISP_E2E_MYSQL_CONTAINER ?? '';
if (!/^[a-f0-9]{64}$/.test(databaseContainer)) {
  throw new Error('Run npm run test:e2e to create the isolated MySQL fixture first.');
}
const baseURL = `http://127.0.0.1:${port}`;

export default defineConfig({
  testDir: './e2e',
  workers: 1,
  use: { baseURL, browserName: 'chromium' },
  webServer: {
    command: 'java -jar target/newcrisp-server.jar',
    url: `${baseURL}/actuator/health/readiness`,
    env: { SERVER_ADDRESS: '127.0.0.1', SERVER_PORT: port },
    reuseExistingServer: false,
    gracefulShutdown: { signal: 'SIGTERM', timeout: 5_000 },
    timeout: 60_000,
  },
});
