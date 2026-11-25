<script setup lang="ts">
import { computed, onMounted, onUnmounted, reactive, ref, shallowRef } from 'vue';
import ChatPanel from './ChatPanel.vue';
import TenantManagement from './TenantManagement.vue';
import { SessionError, webSession, type SessionSide } from './web-session';

type StaffProfile = {
  tenantName: string;
  email: string;
  role: 'TENANT_ADMIN' | 'STAFF';
  staffId: string;
  accessVersion: string;
  canQueue: boolean;
  canSupervise: boolean;
};
type CustomerProfile = { tenantName: string; customerId: string };
type Visit = { sid: string; user: StaffProfile | CustomerProfile };

const { side } = defineProps<{ side: SessionSide }>();
const customer = side === 'customer';
const auth = webSession(side);
const feedback = ref(customer ? '正在验证登录…' : '');
const visit = shallowRef<Visit>();
const showLogin = ref(false);
const signingIn = ref(false);
const signingOut = ref(false);
const credentials = reactive({ tenantCode: '', email: '', password: '' });
const staff = computed(() => {
  const profile = visit.value?.user;
  return profile && 'staffId' in profile ? profile : undefined;
});
const admin = computed(() => staff.value?.role === 'TENANT_ADMIN');
const heading = computed(() => customer ? '在线客服'
  : !staff.value ? '租户管理员登录' : admin.value ? '租户管理' : '客服工作台');
const chat = computed(() => {
  const current = visit.value;
  if (!current) return;
  if ('customerId' in current.user) {
    return { side, identity: current.user.customerId, auth, sid: current.sid };
  }
  if (current.user.canQueue || current.user.canSupervise) {
    return {
      side, identity: current.user.staffId, auth, sid: current.sid,
      accessVersion: current.user.accessVersion, canQueue: current.user.canQueue, canSupervise: current.user.canSupervise,
    };
  }
});
let revision = 0;
let stopListening: (() => void) | undefined;
let timer: number | undefined;

async function refreshProfile() {
  const current = ++revision;
  try {
    const response = await auth.request(`/api/v1/${side}/me`, { signal: AbortSignal.timeout(6_000) });
    if (current !== revision) return;
    if (response.status === 401) {
      visit.value = undefined;
      showLogin.value = !customer;
      if (customer) feedback.value = '请从 Nexus 控制台重新进入在线客服。';
      return;
    }
    if (!response.ok) throw new Error('Profile unavailable');
    const user: Visit['user'] = await response.json();
    const sid = auth.currentId();
    if (current !== revision || !sid) return;
    visit.value = { sid, user };
    showLogin.value = false;
    if (customer) feedback.value = '已通过 Nexus 登录';
  } catch {
    if (current !== revision) return;
    if (customer) {
      visit.value = undefined;
      feedback.value = '暂时无法验证 Nexus 登录，请稍后刷新。';
    } else {
      feedback.value = '暂时无法加载登录状态，请刷新页面。';
    }
  }
}

function recheckAccess() {
  visit.value = undefined;
  void refreshProfile();
}

async function login() {
  signingIn.value = true;
  feedback.value = '';
  try {
    await auth.login(credentials);
    Object.assign(credentials, { tenantCode: '', email: '', password: '' });
    await refreshProfile();
  } catch (error) {
    feedback.value = error instanceof SessionError && error.status === 401
      ? '租户代码、邮箱或密码有误。' : '暂时无法确认登录，请刷新页面后重试。';
  } finally {
    signingIn.value = false;
  }
}

async function logout() {
  signingOut.value = true;
  if (!customer) feedback.value = '';
  revision++;
  try {
    await auth.logout();
    visit.value = undefined;
    await refreshProfile();
  } catch {
    feedback.value = '退出未确认，请重试。';
  } finally {
    signingOut.value = false;
  }
}

function checkVisibleProfile() {
  if (!document.hidden && !showLogin.value && !signingOut.value) void refreshProfile();
}

onMounted(() => {
  stopListening = auth.onChange(recheckAccess);
  document.addEventListener('visibilitychange', checkVisibleProfile);
  timer = window.setInterval(checkVisibleProfile, 30_000);
  void refreshProfile();
});
onUnmounted(() => {
  revision++;
  stopListening?.();
  document.removeEventListener('visibilitychange', checkVisibleProfile);
  window.clearInterval(timer);
});
</script>

<template>
  <main>
    <h1 id="heading">{{ heading }}</h1>
    <p id="feedback" role="status" aria-live="polite">{{ feedback }}</p>
    <form v-if="showLogin" @submit.prevent="login">
      <label>租户代码<input v-model="credentials.tenantCode" required autocomplete="organization" pattern="[a-z][a-z0-9\-]{2,47}"></label>
      <label>邮箱<input v-model="credentials.email" required type="email" autocomplete="username" maxlength="254"></label>
      <label>密码<input v-model="credentials.password" required type="password" autocomplete="current-password" maxlength="72"></label>
      <button type="submit" :disabled="signingIn">登录</button>
    </form>
    <section v-if="visit" id="profile" :aria-label="customer ? '客户身份' : undefined" :aria-labelledby="customer ? undefined : 'heading'">
      <p>{{ visit.user.tenantName }}</p>
      <p v-if="staff">{{ staff.email }}</p>
      <button type="button" :disabled="signingOut" @click="logout">{{ customer ? '退出客服' : '退出登录' }}</button>
    </section>
    <TenantManagement
      v-if="visit && admin"
      :key="visit.sid"
      :sid="visit.sid"
      @notice="feedback = $event"
      @expired="recheckAccess"
    />
    <p v-if="staff && !admin && !chat">暂无部门权限，请联系租户管理员。</p>
    <ChatPanel
      v-if="chat"
      :key="`${chat.sid}:${chat.accessVersion ?? ''}`"
      v-bind="chat"
      @expired="recheckAccess"
    />
  </main>
</template>
