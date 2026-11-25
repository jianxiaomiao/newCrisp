<script setup lang="ts">
import { onMounted, onUnmounted, ref, shallowRef } from 'vue';
import { Connection, Headset } from '@element-plus/icons-vue';
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
let latestPageUrl: string | null = null;
try { resume = localStorage.getItem(key); } catch { /* browser partitions may disable storage */ }

async function reportPageContext() {
  if (!visitor.value || !latestPageUrl) return;
  try {
    await auth.request('/api/v1/customer/context', {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ pageUrl: latestPageUrl }),
    }, visitor.value.token.sessionId);
  } catch { /* non-blocking context report */ }
}

function onParentMessage(event: MessageEvent) {
  if (!event.data || typeof event.data !== 'object') return;
  if (event.data.type === 'newcrisp:context' && typeof event.data.pageUrl === 'string') {
    latestPageUrl = event.data.pageUrl;
    void reportPageContext();
  }
}

onMounted(() => window.addEventListener('message', onParentMessage));
onUnmounted(() => window.removeEventListener('message', onParentMessage));

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
    void reportPageContext();
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
  <main class="guest-shell surface">
    <header class="guest-heading">
      <el-avatar :size="40"><el-icon><Headset /></el-icon></el-avatar>
      <div class="guest-heading-copy"><h1>{{ visitor?.tenantName ?? '访客客服' }}</h1><p>网站访客</p></div>
      <el-button v-if="visitor" class="guest-code-toggle" link :icon="Connection" :aria-expanded="showCode" @click="showCode = !showCode">
        {{ showCode ? '隐藏接续码' : '显示访客接续码' }}
      </el-button>
    </header>
    <div v-if="visitor && showCode" class="guest-code-drawer">
      <label class="guest-code">访客接续码<el-input readonly size="small" aria-label="访客接续码" :model-value="`${site}.${resume}`" /></label>
    </div>
    <p v-if="error" class="feedback guest-feedback" aria-live="polite">{{ error }}</p>
    <div v-if="!visitor" class="guest-welcome">
      <div class="inline-actions">
        <el-button type="primary" :disabled="busy" @click="start()">{{ busy ? '正在连接…' : resume ? '接续会话' : '开始聊天' }}</el-button>
        <el-button v-if="error && resume" :disabled="busy" @click="start(true)">以新访客开始</el-button>
      </div>
      <details class="customer-resume">
        <summary><el-icon><Connection /></el-icon>使用已有接续码</summary>
        <form class="field-stack" @submit.prevent="continueWithCode">
          <label>已有访客接续码
            <el-input v-model="previousCode" aria-label="已有访客接续码" required maxlength="90" autocomplete="off" />
          </label>
          <el-button native-type="submit" type="primary" :disabled="busy">继续之前的会话</el-button>
        </form>
      </details>
    </div>
    <ChatPanel v-else :key="visitor.token.sessionId" side="customer" :identity="visitor.customerId" :sid="visitor.token.sessionId" :auth="auth" guest @expired="expired" />
  </main>
</template>
