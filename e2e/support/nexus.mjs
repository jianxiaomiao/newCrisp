import { spawn } from 'node:child_process';
import { createHash, randomBytes } from 'node:crypto';
import { createReadStream, createWriteStream } from 'node:fs';
import { stat } from 'node:fs/promises';
import { createServer, request as proxyRequest } from 'node:http';
import { once } from 'node:events';
import { extname, resolve, sep } from 'node:path';
import { setTimeout as delay } from 'node:timers/promises';

const work = process.env.NEWCRISP_E2E_NEXUS_WORK;
const apiPort = process.env.NEWCRISP_E2E_NEXUS_API_PORT;
const webPort = process.env.NEWCRISP_E2E_NEXUS_WEB_PORT;
export const nexusOrigin = `http://localhost:${webPort}`;
export const nexusApi = `http://127.0.0.1:${apiPort}`;
const mime = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.woff2': 'font/woff2',
};

function frontend(dist) {
  return createServer(async (incoming, outgoing) => {
    const url = new URL(incoming.url, nexusOrigin);
    if (url.pathname.startsWith('/api/')) {
      const proxy = proxyRequest(`${nexusApi}${url.pathname}${url.search}`, {
        method: incoming.method,
        headers: { ...incoming.headers, host: new URL(nexusApi).host },
      }, (response) => {
        outgoing.writeHead(response.statusCode, response.headers);
        response.pipe(outgoing);
      });
      proxy.on('error', () => outgoing.writeHead(502).end());
      incoming.pipe(proxy);
      return;
    }
    try {
      const path = resolve(dist, `.${decodeURIComponent(url.pathname)}`);
      const isFile = path.startsWith(dist + sep) && (await stat(path).catch(() => null))?.isFile();
      const file = isFile ? path : resolve(dist, 'index.html');
      outgoing.writeHead(200, { 'Content-Type': mime[extname(file)] ?? 'application/octet-stream' });
      createReadStream(file).on('error', () => outgoing.destroy()).pipe(outgoing);
    } catch {
      outgoing.writeHead(400).end();
    }
  });
}

export async function startNexus(credential) {
  const verifyHash = createHash('sha256').update(process.env.NEWCRISP_NEXUS_VERIFY_KEY).digest('hex');
  const child = spawn('java', [
    '-Duser.timezone=UTC', '-jar', 'target/nexus-server-0.0.1-SNAPSHOT.jar',
    '--spring.profiles.active=local', '--nexus.auth.jwt.access-token-ttl=30m',
    '--nexus.auth.web.secure-cookie=false', '--nexus.auth.web.cookie-name=nexus_rt_dev',
    `--nexus.auth.web.allowed-origins[0]=${nexusOrigin}`,
  ], {
    cwd: resolve(work, 'server'),
    env: {
      ...process.env,
      SERVER_ADDRESS: '127.0.0.1', SERVER_PORT: apiPort,
      SPRING_DATASOURCE_URL: process.env.NEWCRISP_E2E_NEXUS_DB_URL,
      SPRING_DATASOURCE_USERNAME: 'nexus_test_user',
      SPRING_DATASOURCE_PASSWORD: process.env.NEWCRISP_E2E_NEXUS_DB_PASSWORD,
      SPRING_FLYWAY_URL: process.env.NEWCRISP_E2E_NEXUS_DB_URL,
      SPRING_FLYWAY_USER: 'nexus_test_user',
      SPRING_FLYWAY_PASSWORD: process.env.NEWCRISP_E2E_NEXUS_DB_PASSWORD,
      NEXUS_AUTH_JWT_ISSUER: 'newcrisp-e2e', NEXUS_AUTH_JWT_SECRET: randomBytes(32).toString('base64'),
      NEXUS_WEBHOOK_CRYPTO_CURRENT_KEY_VERSION: 'v1', NEXUS_WEBHOOK_CRYPTO_KEYS_V1: randomBytes(32).toString('base64'),
      NEXUS_WEBHOOK_SCHEDULING_ENABLED: 'false', NEXUS_LOG_DIR: resolve(work, 'nexus-logs'),
      NEXUS_SCREENSHOT_JOB_RESULT_DIR: resolve(work, 'screenshot-results'),
      NEXUS_CHAT_ORIGIN: process.env.NEWCRISP_PUBLIC_ORIGIN,
      NEXUS_CHAT_CREDENTIAL: credential, NEXUS_CHAT_VERIFY_KEY_SHA256: verifyHash,
    },
    stdio: ['ignore', 'pipe', 'pipe'],
  });
  const log = createWriteStream(resolve(work, 'nexus-process.log'));
  let output = '';
  let spawnError;
  child.on('error', (error) => { spawnError = error; });
  for (const stream of [child.stdout, child.stderr]) {
    stream.pipe(log, { end: false });
    stream.on('data', (chunk) => { output = (output + chunk).slice(-5_000); });
  }
  child.on('close', () => log.end());
  const web = frontend(resolve(work, 'console/dist'));
  async function stop() {
    if (web.listening) await new Promise((done) => web.close(done));
    if (child.exitCode === null && child.signalCode === null && child.pid) {
      child.kill();
      const stopped = await Promise.race([once(child, 'exit').then(() => true), delay(3_000, false, { ref: false })]);
      if (!stopped) {
        child.kill('SIGKILL');
        await once(child, 'exit');
      }
    }
  }
  try {
    const deadline = Date.now() + 60_000;
    while (Date.now() < deadline) {
      if (spawnError || child.exitCode !== null || child.signalCode !== null) break;
      const response = await fetch(`${nexusApi}/api/support/chat/launch`, {
        method: 'POST', signal: AbortSignal.timeout(1_000),
      }).catch(() => null);
      if (response?.status === 401) {
        web.listen(Number(webPort), '127.0.0.1');
        await once(web, 'listening');
        return stop;
      }
      await delay(250);
    }
    throw new Error(`Nexus did not start in the isolated fixture.\n${output}`);
  } catch (error) {
    await stop();
    throw error;
  }
}
