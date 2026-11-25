<script setup lang="ts">
import { computed, onMounted, onUnmounted, reactive, ref, shallowRef } from 'vue';
import { ArrowLeft, ChatDotRound, CircleCheck, Connection, DataAnalysis, Document, Grid, Link, Lock, Setting, SwitchButton, User } from '@element-plus/icons-vue';
import BrandMark from './BrandMark.vue';
import ChatPanel from './ChatPanel.vue';
import TenantManagement from './TenantManagement.vue';
import SupportAdmin from './SupportAdmin.vue';
import AnalyticsDashboard from './AnalyticsDashboard.vue';
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
const embedded = customer && new URLSearchParams(window.location.search).get('embed') === '1';
const auth = webSession(side);
const feedback = ref(customer ? '正在验证登录…' : '');
const visit = shallowRef<Visit>();
const showLogin = ref(false);
const signingIn = ref(false);
const signingOut = ref(false);
const linkingGuest = ref(false);
const guestCode = ref('');
const guestNotice = ref('');
const adminViews = [
  { id: 'analytics', label: '数据看板', icon: DataAnalysis },
  { id: 'rules', label: '接待规则', icon: Setting },
  { id: 'replies', label: '共享快捷回复', icon: Document },
  { id: 'sites', label: '访客入口', icon: Link },
  { id: 'management', label: '租户管理', icon: Grid },
] as const;
const adminView = ref<(typeof adminViews)[number]['id']>('management');
const canGoBack = window.history.length > 1;
const goBack = () => window.history.back();
const credentials = reactive({ tenantCode: '', email: '', password: '' });
const staff = computed(() => {
  const profile = visit.value?.user;
  return profile && 'staffId' in profile ? profile : undefined;
});
const admin = computed(() => staff.value?.role === 'TENANT_ADMIN');
const workspaceTitle = computed(() => admin.value
  ? adminViews.find(({ id }) => id === adminView.value)?.label ?? '租户管理'
  : '客服工作台');
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
    if (visit.value?.sid !== sid) adminView.value = 'management';
    if (visit.value && visit.value.sid !== sid) {
      guestCode.value = '';
      guestNotice.value = '';
    }
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
  guestCode.value = '';
  guestNotice.value = '';
  visit.value = undefined;
  void refreshProfile();
}

async function linkGuest() {
  const sid = visit.value?.sid;
  if (!sid || linkingGuest.value) return;
  linkingGuest.value = true;
  guestNotice.value = '';
  try {
    const result = await auth.request('/api/v1/customer/guest-link', {
      method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ code: guestCode.value.trim() }),
    }, sid);
    if (!result.ok) {
      const failure = await result.json().catch(() => null);
      guestNotice.value = failure?.code === 'GUEST_ACTIVE_CONVERSATION_CONFLICT'
        ? '两个身份都有进行中的会话，请先结束其中一个再接续。' : '接续码无效或已过期。';
      return;
    }
    recheckAccess();
    guestNotice.value = '访客会话已接续，可在会话列表查看。';
  } catch {
    guestNotice.value = '接续请求未完成，请重试。';
  } finally {
    linkingGuest.value = false;
  }
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
  guestCode.value = '';
  guestNotice.value = '';
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
  <div v-if="customer" :class="['customer-shell', { 'embedded-customer': embedded }]">
    <header v-if="!embedded" class="consumer-bar">
      <div class="consumer-bar-inner">
        <BrandMark />
        <span v-if="visit" class="consumer-tenant">{{ visit.user.tenantName }}</span>
        <div class="consumer-actions">
          <el-button v-if="canGoBack" :icon="ArrowLeft" @click="goBack">返回上一页</el-button>
          <el-button v-if="visit" :icon="SwitchButton" :disabled="signingOut" @click="logout">退出客服</el-button>
        </div>
      </div>
    </header>
    <main class="customer-main">
      <header class="customer-heading">
        <h1>在线客服</h1>
        <span class="identity" role="status" aria-live="polite"><el-icon v-if="visit"><CircleCheck /></el-icon>{{ feedback }}</span>
      </header>
      <ChatPanel v-if="chat && visit" :key="chat.sid" v-bind="chat" :embedded="embedded" @expired="recheckAccess">
        <template #sidebar>
          <details class="customer-resume">
            <summary><el-icon><Connection /></el-icon>接续访客对话</summary>
            <form class="field-stack" @submit.prevent="linkGuest">
              <label>访客接续码
                <el-input v-model="guestCode" required maxlength="90" autocomplete="off" aria-label="访客接续码" />
              </label>
              <el-button native-type="submit" type="primary" :disabled="linkingGuest">接续访客会话</el-button>
            </form>
          </details>
          <p v-if="guestNotice" class="feedback" aria-live="polite">{{ guestNotice }}</p>
        </template>
      </ChatPanel>
    </main>
  </div>

  <main v-else-if="!visit" class="signin-shell">
    <section class="signin surface">
      <div class="signin-brand">
        <BrandMark />
        <div class="brand-art" aria-hidden="true">
          <span class="orbit"></span><span class="orbit orbit-two"></span><span class="orbit orbit-three"></span>
          <span class="art-bubble art-back"><el-icon><ChatDotRound /></el-icon></span>
          <span class="art-bubble art-front"><el-icon><ChatDotRound /></el-icon></span>
        </div>
        <div class="signin-intro"><strong>客服工作台</strong><p>登录你的租户，领取会话并回复客户。</p></div>
      </div>
      <div class="signin-form">
        <h1 id="heading">登录工作台</h1>
        <p class="feedback" role="status" aria-live="polite">{{ feedback }}</p>
        <form v-if="showLogin" class="field-stack" @submit.prevent="login">
          <label>租户代码
            <el-input v-model="credentials.tenantCode" aria-label="租户代码" required autocomplete="organization" pattern="[a-z][a-z0-9\-]{2,47}" :prefix-icon="Grid" placeholder="例如：qinghe" />
          </label>
          <label>邮箱
            <el-input v-model="credentials.email" aria-label="邮箱" required type="email" autocomplete="username" maxlength="254" :prefix-icon="User" placeholder="输入工作邮箱" />
          </label>
          <label>密码
            <el-input v-model="credentials.password" aria-label="密码" required type="password" autocomplete="current-password" maxlength="72" :prefix-icon="Lock" show-password placeholder="输入密码" />
          </label>
          <el-button class="login-submit" native-type="submit" type="primary" :loading="signingIn">登录</el-button>
        </form>
      </div>
    </section>
  </main>

  <div v-else class="staff-shell">
    <aside class="rail">
      <BrandMark />
      <div class="workspace">
        <el-avatar :size="36">{{ visit.user.tenantName.charAt(0) }}</el-avatar>
        <div><strong>{{ visit.user.tenantName }}</strong><small>客服工作空间</small></div>
      </div>
      <nav class="rail-nav" aria-label="工作空间">
        <template v-if="admin">
          <el-button v-for="item in adminViews" :key="item.id" :icon="item.icon"
            :aria-current="adminView === item.id ? 'page' : undefined"
            @click="adminView = item.id; feedback = ''">{{ item.label }}</el-button>
        </template>
        <el-button v-else :icon="ChatDotRound" aria-current="page">会话工作台</el-button>
      </nav>
      <div v-if="staff" class="staff-profile">
        <el-avatar :size="34">{{ staff.email.charAt(0).toUpperCase() }}</el-avatar>
        <div class="staff-profile-copy" :title="staff.email"><strong>{{ staff.email }}</strong><small>{{ admin ? '租户管理员' : staff.canSupervise ? '部门主管' : '客服' }}</small></div>
      </div>
    </aside>

    <main class="desk">
      <div class="topbar">
        <span class="breadcrumb"><span>工作空间</span><span aria-hidden="true">›</span><strong>{{ workspaceTitle }}</strong></span>
        <el-button :icon="SwitchButton" :disabled="signingOut" @click="logout">退出登录</el-button>
      </div>
      <header class="desk-heading">
        <h1 id="heading">{{ workspaceTitle }}</h1>
        <p v-if="!admin">领取、回复并跟进客户会话</p>
      </header>
      <p class="feedback" role="status" aria-live="polite">{{ feedback }}</p>
      <template v-if="admin">
        <AnalyticsDashboard v-if="adminView === 'analytics'" :key="`analytics:${visit.sid}`" :sid="visit.sid" @expired="recheckAccess" />
        <TenantManagement v-show="adminView === 'management'" :key="`management:${visit.sid}`" :sid="visit.sid"
          @notice="feedback = $event" @expired="recheckAccess" />
        <SupportAdmin v-show="adminView !== 'management' && adminView !== 'analytics'" :key="`support:${visit.sid}`" :sid="visit.sid"
          :view="adminView === 'rules' || adminView === 'replies' || adminView === 'sites' ? adminView : 'rules'" @expired="recheckAccess" />
      </template>
      <p v-else-if="staff && !chat" class="surface empty-state">暂无部门权限，请联系租户管理员。</p>
      <ChatPanel v-if="chat" :key="`${chat.sid}:${chat.accessVersion ?? ''}`" v-bind="chat" @expired="recheckAccess" />
    </main>
  </div>
</template>
