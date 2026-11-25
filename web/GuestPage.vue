<script setup lang="ts">
import { ref, shallowRef } from 'vue';
import ChatPanel from './ChatPanel.vue';

const site = location.pathname.split('/').at(-1)!;
const key = `newcrisp:guest:${site}`;
type Visit = {
  customerId: string; tenantName: string;
  token: { accessToken: string; sessionId: string; expiresAt: string };
  resume: string | null;
};
const visitor = shallowRef<Visit>();
const error = ref('');
const busy = ref(false);
const showCode = ref(false);
const previousCode = ref('');
let resume: string | null = null;
let connecting: Promise<void> | undefined;
try { resume = localStorage.getItem(key); } catch { /* browser partitions may disable storage */ }

async function connect() {
  if (connecting) return connecting;
  const run = (async () => {
    const response = await fetch(`/api/v1/guest/${encodeURIComponent(site)}/session`, {
      method: 'POST', headers: { 'Content-Type': 'application/json' }, cache: 'no-store', signal: AbortSignal.timeout(8000),
      body: JSON.stringify({ resume, previousSid: visitor.value?.token.sessionId }),
    });
    if (!response.ok) throw new Error('Guest session unavailable');
    const next: Visit = await response.json();
    if (next.resume) {
      resume = next.resume;
      try { localStorage.setItem(key, next.resume); } catch { /* keep the code in memory */ }
    }
    visitor.value = next;
  })();
  connecting = run;
  try { await run; }
  finally { connecting = undefined; }
}

async function start(fresh = false) {
  if (busy.value) return;
  if (fresh) {
    resume = null;
    try { localStorage.removeItem(key); } catch { /* storage is optional */ }
  }
  busy.value = true;
  error.value = '';
  try { await connect(); }
  catch { error.value = '暂时无法接续访客会话。可以重试或开始新的会话。'; }
  finally { busy.value = false; }
}

const auth = {
  async request(path: string, options: RequestInit = {}, expectedSid?: string) {
    try {
      if (!visitor.value || Date.parse(visitor.value.token.expiresAt) <= Date.now() + 5000) await connect();
      const session = visitor.value!.token;
      if (expectedSid && expectedSid !== session.sessionId) return new Response(null, { status: 401 });
      const headers = new Headers(options.headers);
      headers.set('Authorization', `Bearer ${session.accessToken}`);
      return fetch(path, { ...options, headers, cache: 'no-store' });
    } catch {
      return new Response(null, { status: 503 });
    }
  },
};

function expired() {
  visitor.value = undefined;
  error.value = '访客登录已过期，请接续会话。';
}

function continueWithCode() {
  const code = previousCode.value.trim();
  if (!code.startsWith(`${site}.`) || !/^[A-Za-z0-9_-]{43}$/.test(code.slice(site.length + 1))) {
    error.value = '该接续码与当前访客入口不匹配。';
    return;
  }
  resume = code.slice(site.length + 1);
  previousCode.value = '';
  try { localStorage.setItem(key, resume); } catch { /* keep the code in memory */ }
  void start();
}

if (resume) void start();
</script>

<template>
  <main>
    <h1>{{ visitor?.tenantName ?? '访客客服' }}</h1>
    <p v-if="error" aria-live="polite">{{ error }}</p>
    <template v-if="!visitor">
      <button type="button" :disabled="busy" @click="start()">{{ busy ? '正在连接…' : resume ? '接续会话' : '开始聊天' }}</button>
      <button v-if="error && resume" type="button" :disabled="busy" @click="start(true)">以新访客开始</button>
      <details>
        <summary>使用已有接续码</summary>
        <form @submit.prevent="continueWithCode">
          <label>已有访客接续码<input v-model="previousCode" required maxlength="90" autocomplete="off"></label>
          <button type="submit" :disabled="busy">继续之前的会话</button>
        </form>
      </details>
    </template>
    <template v-else>
      <button type="button" @click="showCode = !showCode">{{ showCode ? '隐藏接续码' : '显示访客接续码' }}</button>
      <label v-if="showCode">访客接续码<input readonly :value="`${site}.${resume}`"></label>
      <ChatPanel :key="visitor.token.sessionId" side="customer" :identity="visitor.customerId" :sid="visitor.token.sessionId" :auth="auth" @expired="expired" />
    </template>
  </main>
</template>
