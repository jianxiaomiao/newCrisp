<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue';
import { CircleCheck, DataAnalysis, Grid, Key, Link, Lock, Plus, Refresh, Search, SwitchButton } from '@element-plus/icons-vue';
import BrandMark from './BrandMark.vue';
import { responseError } from './api';
import { confirmAction } from './confirmation';

type PlatformSession = { authenticated: boolean; selfSignupEnabled: boolean; landingDemoSiteId: string | null };
type PlatformOverview = {
  totalTenants: number;
  activeTenants: number;
  disabledTenants: number;
  totalStaff: number;
  totalConversations: number;
  openConversations: number;
  conversationsLast24h: number;
};
type TenantSummary = {
  id: string;
  code: string;
  name: string;
  status: 'ACTIVE' | 'DISABLED';
  createdAt: string;
  staffCount: number;
  conversationCount: number;
  openConversations: number;
  guestSiteCount: number;
  nexusOrigin: string | null;
  adminEmail: string | null;
};
type IntegrationCreated = { integrationId: string; secret: string; nexusOrigin: string };

const checking = ref(true);
const session = ref<PlatformSession | null>(null);
const platformKey = ref('');
const signingIn = ref(false);
const signingOut = ref(false);
const loading = ref(false);
const feedback = ref('');

const overview = ref<PlatformOverview | null>(null);
const tenants = ref<TenantSummary[]>([]);
const searchQuery = ref('');
const statusFilter = ref('');

const showCreate = ref(false);
const creating = ref(false);
const createForm = reactive({ code: '', name: '', adminEmail: '', adminPassword: '' });

const nexusTarget = ref<TenantSummary | null>(null);
const nexusOrigin = ref('');
const nexusSaving = ref(false);
const nexusCreated = ref<IntegrationCreated | null>(null);

const resetTarget = ref<TenantSummary | null>(null);
const resetForm = reactive({ email: '', password: '' });
const resetting = ref(false);

const dayTime = new Intl.DateTimeFormat('zh-CN', { year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' });
const activeRate = computed(() => {
  if (!overview.value || !overview.value.totalTenants) return '0%';
  return `${Math.round((overview.value.activeTenants / overview.value.totalTenants) * 100)}%`;
});

async function platformRequest(path: string, options: RequestInit = {}) {
  const response = await fetch(`/api/v1/platform${path}`, {
    credentials: 'same-origin',
    cache: 'no-store',
    ...options,
    headers: {
      ...(typeof options.body === 'string' ? { 'Content-Type': 'application/json' } : {}),
      ...(options.headers as Record<string, string> | undefined),
    },
  });
  if (response.status === 401) {
    session.value = null;
    throw new Error('UNAUTHORIZED');
  }
  if (!response.ok) {
    throw await responseError(response);
  }
  return response;
}

async function checkSession() {
  checking.value = true;
  try {
    const res = await fetch('/api/v1/platform/me', { credentials: 'same-origin', cache: 'no-store' });
    if (res.ok) {
      session.value = await res.json();
      await loadData();
    } else {
      session.value = null;
    }
  } catch {
    session.value = null;
  } finally {
    checking.value = false;
  }
}

async function login() {
  if (signingIn.value || !platformKey.value.trim()) return;
  signingIn.value = true;
  feedback.value = '';
  try {
    const res = await fetch('/api/v1/platform/session', {
      method: 'POST',
      credentials: 'same-origin',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ key: platformKey.value.trim() }),
    });
    if (!res.ok) {
      feedback.value = res.status === 401 ? '平台密钥不正确，或服务端未配置 NEWCRISP_PLATFORM_KEY_SHA256。' : '登录失败，请稍后重试。';
      return;
    }
    session.value = await res.json();
    platformKey.value = '';
    await loadData();
  } catch {
    feedback.value = '网络异常，请检查服务是否运行。';
  } finally {
    signingIn.value = false;
  }
}

async function logout() {
  if (signingOut.value) return;
  signingOut.value = true;
  try {
    await fetch('/api/v1/platform/session', { method: 'DELETE', credentials: 'same-origin' });
  } finally {
    session.value = null;
    overview.value = null;
    tenants.value = [];
    signingOut.value = false;
  }
}

async function loadData() {
  if (loading.value) return;
  loading.value = true;
  feedback.value = '';
  try {
    const params = new URLSearchParams();
    if (searchQuery.value.trim()) params.set('q', searchQuery.value.trim());
    if (statusFilter.value) params.set('status', statusFilter.value);
    const qs = params.toString() ? `?${params.toString()}` : '';
    const [ovRes, listRes] = await Promise.all([
      platformRequest('/overview'),
      platformRequest(`/tenants${qs}`),
    ]);
    overview.value = await ovRes.json();
    tenants.value = await listRes.json();
  } catch (err: unknown) {
    if (err instanceof Error && err.message === 'UNAUTHORIZED') {
      feedback.value = '平台登录状态已过期，请重新输入平台密钥。';
    } else {
      feedback.value = '加载平台数据失败，请刷新重试。';
    }
  } finally {
    loading.value = false;
  }
}

async function createTenant() {
  if (creating.value) return;
  creating.value = true;
  feedback.value = '';
  try {
    await platformRequest('/tenants', {
      method: 'POST',
      body: JSON.stringify({
        code: createForm.code.trim().toLowerCase(),
        name: createForm.name.trim(),
        adminEmail: createForm.adminEmail.trim(),
        adminPassword: createForm.adminPassword,
      }),
    });
    showCreate.value = false;
    createForm.code = '';
    createForm.name = '';
    createForm.adminEmail = '';
    createForm.adminPassword = '';
    feedback.value = '租户创建成功。';
    await loadData();
  } catch (err: any) {
    feedback.value = err?.code === 'TENANT_ALREADY_EXISTS'
      ? '租户代码已存在。'
      : err?.code === 'INVALID_ADMIN_PASSWORD'
        ? '管理员密码需为 12–72 位字符。'
        : '创建租户失败，请检查输入格式。';
  } finally {
    creating.value = false;
  }
}

async function toggleStatus(row: TenantSummary) {
  const next = row.status === 'ACTIVE' ? 'DISABLED' : 'ACTIVE';
  const confirmed = await confirmAction(
    next === 'DISABLED' ? '停用租户' : '启用租户',
    next === 'DISABLED'
      ? `确定停用租户「${row.name}」（${row.code}）？停用后该租户的员工将被立即强制下线，数据会完整保留。`
      : `确定重新启用租户「${row.name}」（${row.code}）？`,
  );
  if (!confirmed) return;
  try {
    await platformRequest(`/tenants/${encodeURIComponent(row.code)}/status`, {
      method: 'PATCH',
      body: JSON.stringify({ status: next }),
    });
    feedback.value = next === 'DISABLED' ? `已停用租户 ${row.code}。` : `已启用租户 ${row.code}。`;
    await loadData();
  } catch {
    feedback.value = '更新租户状态失败。';
  }
}

function openNexusDialog(row: TenantSummary) {
  nexusTarget.value = row;
  nexusOrigin.value = row.nexusOrigin ?? '';
  nexusCreated.value = null;
}

async function saveNexusIntegration() {
  if (!nexusTarget.value || nexusSaving.value) return;
  nexusSaving.value = true;
  try {
    const res = await platformRequest(`/tenants/${encodeURIComponent(nexusTarget.value.code)}/nexus-integrations`, {
      method: 'POST',
      body: JSON.stringify({ nexusOrigin: nexusOrigin.value.trim() }),
    });
    nexusCreated.value = await res.json();
    await loadData();
  } catch {
    feedback.value = '配置 Nexus 集成失败，请确认 Origin 格式正确（如 https://nexus.example.com）。';
  } finally {
    nexusSaving.value = false;
  }
}

function openResetDialog(row: TenantSummary) {
  resetTarget.value = row;
  resetForm.email = row.adminEmail ?? '';
  resetForm.password = '';
}

async function submitPasswordReset() {
  if (!resetTarget.value || resetting.value) return;
  resetting.value = true;
  try {
    await platformRequest(`/tenants/${encodeURIComponent(resetTarget.value.code)}/admin-password`, {
      method: 'POST',
      body: JSON.stringify({ email: resetForm.email.trim(), password: resetForm.password }),
    });
    feedback.value = `已重置租户 ${resetTarget.value.code} 管理员（${resetForm.email}）的密码并吊销旧会话。`;
    resetTarget.value = null;
  } catch (err: any) {
    feedback.value = err?.code === 'INVALID_ADMIN_PASSWORD'
      ? '新密码需为 12–72 位字符。'
      : '重置密码失败，请确认该邮箱是此租户的管理员账号。';
  } finally {
    resetting.value = false;
  }
}

async function copyText(text: string) {
  try {
    await navigator.clipboard.writeText(text);
    feedback.value = '已复制到剪贴板。';
  } catch { /* ignore clipboard errors */ }
}

onMounted(() => void checkSession());
</script>

<template>
  <main v-if="checking" class="signin-shell">
    <section class="surface empty-state">正在验证平台管理员会话…</section>
  </main>

  <main v-else-if="!session" class="signin-shell">
    <section class="signin surface">
      <div class="signin-brand">
        <BrandMark />
        <div class="brand-art" aria-hidden="true">
          <span class="orbit"></span><span class="orbit orbit-two"></span><span class="orbit orbit-three"></span>
          <span class="art-bubble art-back"><el-icon><Grid /></el-icon></span>
          <span class="art-bubble art-front"><el-icon><Key /></el-icon></span>
        </div>
        <div class="signin-intro">
          <strong>平台管理台</strong>
          <p>跨租户管理中心：查看全平台租户与用量、开通或停用租户、配置 Nexus 集成与重置管理员密码。</p>
        </div>
      </div>
      <div class="signin-form">
        <h1>平台超管登录</h1>
        <p class="feedback" role="status" aria-live="polite">{{ feedback }}</p>
        <form class="field-stack" @submit.prevent="login">
          <label>平台管理密钥（Platform Key）
            <el-input v-model="platformKey" aria-label="平台管理密钥" required type="password" show-password :prefix-icon="Lock"
              autocomplete="current-password" placeholder="输入部署时配置的平台密钥" />
          </label>
          <el-button class="login-submit" native-type="submit" type="primary" :loading="signingIn">进入平台管理台</el-button>
        </form>
        <div class="inline-actions" style="margin-top: 16px;">
          <a href="/">返回官网首页</a>
          <a href="/staff.html">前往客服工作台</a>
        </div>
      </div>
    </section>
  </main>

  <div v-else class="staff-shell">
    <aside class="rail">
      <BrandMark />
      <div class="workspace">
        <el-avatar :size="36"><el-icon><Grid /></el-icon></el-avatar>
        <div><strong>平台管理中心</strong><small>多租户运维控制面</small></div>
      </div>
      <nav class="rail-nav" aria-label="平台导航">
        <el-button :icon="DataAnalysis" aria-current="page">租户与用量总览</el-button>
        <el-button tag="a" href="/" :icon="Link">官网首页</el-button>
        <el-button tag="a" href="/staff.html" :icon="CircleCheck">客服工作台</el-button>
      </nav>
      <div class="staff-profile">
        <el-avatar :size="34"><el-icon><Key /></el-icon></el-avatar>
        <div class="staff-profile-copy">
          <strong>Platform Admin</strong>
          <small>自助注册：{{ session.selfSignupEnabled ? '已开启' : '已关闭' }}</small>
        </div>
      </div>
    </aside>

    <main class="desk">
      <div class="topbar">
        <span class="breadcrumb"><span>平台管理</span><span aria-hidden="true">›</span><strong>租户管理</strong></span>
        <div class="inline-actions">
          <el-button :icon="Refresh" :loading="loading" @click="loadData">刷新</el-button>
          <el-button type="primary" :icon="Plus" @click="showCreate = true">新建租户</el-button>
          <el-button :icon="SwitchButton" :disabled="signingOut" @click="logout">退出登录</el-button>
        </div>
      </div>

      <header class="desk-heading">
        <h1>平台租户管理</h1>
        <p>统一管理全部工作空间的运行状态、Nexus 集成与管理员兜底支持</p>
      </header>

      <p v-if="feedback" class="feedback" role="status" aria-live="polite">{{ feedback }}</p>

      <section v-if="overview" class="kpi-grid platform-kpis" aria-label="平台运行概览">
        <article class="surface kpi-card">
          <small>租户总数</small>
          <strong>{{ overview.totalTenants }}</strong>
          <span>活跃 {{ overview.activeTenants }}（{{ activeRate }}）· 已停用 {{ overview.disabledTenants }}</span>
        </article>
        <article class="surface kpi-card">
          <small>活跃客服与管理员</small>
          <strong>{{ overview.totalStaff }}</strong>
          <span>全平台已启用账号</span>
        </article>
        <article class="surface kpi-card">
          <small>累计会话数</small>
          <strong>{{ overview.totalConversations }}</strong>
          <span>近 24 小时新增 {{ overview.conversationsLast24h }} 条</span>
        </article>
        <article class="surface kpi-card">
          <small>当前进行中会话</small>
          <strong>{{ overview.openConversations }}</strong>
          <span>含等待中与接待中</span>
        </article>
      </section>

      <section class="surface settings-card" style="margin-top: 16px;">
        <div class="section-head">
          <div>
            <h2>全部租户（{{ tenants.length }}）</h2>
            <p>支持按代码或名称快速检索；停用租户会立即吊销其现有员工登录态。</p>
          </div>
          <div class="inline-actions">
            <el-input v-model="searchQuery" :prefix-icon="Search" clearable placeholder="搜索租户代码或名称" style="width: 220px;"
              @clear="loadData" @keydown.enter="loadData" />
            <el-select v-model="statusFilter" placeholder="全部状态" clearable style="width: 130px;" @change="loadData">
              <el-option label="正常" value="ACTIVE" />
              <el-option label="已停用" value="DISABLED" />
            </el-select>
            <el-button @click="loadData">筛选</el-button>
          </div>
        </div>

        <el-table :data="tenants" class="data-table" empty-text="暂无符合条件的租户">
          <el-table-column label="租户" min-width="180">
            <template #default="{ row }">
              <div class="table-person">
                <el-avatar :size="32">{{ row.name.charAt(0) }}</el-avatar>
                <div>
                  <strong>{{ row.name }}</strong>
                  <small>代码：<code>{{ row.code }}</code></small>
                </div>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="96">
            <template #default="{ row }">
              <el-tag size="small" round :class="row.status === 'ACTIVE' ? 'tag-success' : 'tag-neutral'">
                {{ row.status === 'ACTIVE' ? '正常' : '已停用' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="管理员邮箱" min-width="170">
            <template #default="{ row }">{{ row.adminEmail || '—' }}</template>
          </el-table-column>
          <el-table-column label="员工 / 站点" width="115">
            <template #default="{ row }">{{ row.staffCount }} 人 · {{ row.guestSiteCount }} 站</template>
          </el-table-column>
          <el-table-column label="会话（进行/总计）" width="135">
            <template #default="{ row }">{{ row.openConversations }} / {{ row.conversationCount }}</template>
          </el-table-column>
          <el-table-column label="Nexus 集成" min-width="150">
            <template #default="{ row }">
              <span v-if="row.nexusOrigin">{{ row.nexusOrigin }}</span>
              <span v-else style="color: var(--muted);">未配置</span>
            </template>
          </el-table-column>
          <el-table-column label="创建时间" width="148">
            <template #default="{ row }">{{ dayTime.format(new Date(row.createdAt)) }}</template>
          </el-table-column>
          <el-table-column label="操作" width="230" align="right">
            <template #default="{ row }">
              <el-button size="small" link type="primary" @click="openNexusDialog(row as TenantSummary)">Nexus 集成</el-button>
              <el-button size="small" link @click="openResetDialog(row as TenantSummary)">重置密码</el-button>
              <el-button size="small" link :type="row.status === 'ACTIVE' ? 'danger' : 'primary'" @click="toggleStatus(row as TenantSummary)">
                {{ row.status === 'ACTIVE' ? '停用' : '启用' }}
              </el-button>
            </template>
          </el-table-column>
        </el-table>
      </section>

      <el-dialog v-model="showCreate" title="新建租户工作空间" width="460px">
        <form class="field-stack" @submit.prevent="createTenant">
          <label>租户名称
            <el-input v-model="createForm.name" required maxlength="120" placeholder="例如：清禾科技" />
          </label>
          <label>租户代码
            <el-input v-model="createForm.code" required maxlength="48" pattern="[a-z][a-z0-9\-]{2,47}" placeholder="小写字母开头，例如：qinghe" />
          </label>
          <label>初始管理员邮箱
            <el-input v-model="createForm.adminEmail" required type="email" maxlength="254" placeholder="admin@qinghe.test" />
          </label>
          <label>初始管理员密码（12–72 位）
            <el-input v-model="createForm.adminPassword" required type="password" minlength="12" maxlength="72" show-password />
          </label>
          <div class="inline-actions" style="justify-content: flex-end; width: 100%; margin-top: 6px;">
            <el-button @click="showCreate = false">取消</el-button>
            <el-button native-type="submit" type="primary" :loading="creating">确认创建</el-button>
          </div>
        </form>
      </el-dialog>

      <el-dialog :model-value="Boolean(nexusTarget)" :title="`配置 Nexus 集成 · ${nexusTarget?.name ?? ''}`" width="520px" @close="nexusTarget = null">
        <form v-if="!nexusCreated" class="field-stack" @submit.prevent="saveNexusIntegration">
          <label>Nexus 前端 Origin（例如 https://nexus.example.com 或 http://localhost:18762）
            <el-input v-model="nexusOrigin" required maxlength="255" placeholder="https://nexus.example.com" />
          </label>
          <div class="inline-actions" style="justify-content: flex-end; width: 100%; margin-top: 6px;">
            <el-button @click="nexusTarget = null">取消</el-button>
            <el-button native-type="submit" type="primary" :loading="nexusSaving">生成 / 轮换密钥</el-button>
          </div>
        </form>
        <div v-else class="field-stack">
          <p class="feedback" style="margin: 0;">集成密钥仅展示这一次，请立即保存到 Nexus 服务端环境变量中：</p>
          <label>Integration ID
            <div class="inline-actions" style="width: 100%;">
              <el-input readonly :model-value="nexusCreated.integrationId" style="flex: 1;" />
              <el-button @click="copyText(nexusCreated.integrationId)">复制</el-button>
            </div>
          </label>
          <label>Shared Secret
            <div class="inline-actions" style="width: 100%;">
              <el-input readonly :model-value="nexusCreated.secret" style="flex: 1;" />
              <el-button @click="copyText(nexusCreated.secret)">复制</el-button>
            </div>
          </label>
          <div class="inline-actions" style="justify-content: flex-end; width: 100%;">
            <el-button type="primary" @click="nexusTarget = null">完成</el-button>
          </div>
        </div>
      </el-dialog>

      <el-dialog :model-value="Boolean(resetTarget)" :title="`重置管理员密码 · ${resetTarget?.name ?? ''}`" width="460px" @close="resetTarget = null">
        <form class="field-stack" @submit.prevent="submitPasswordReset">
          <label>管理员邮箱
            <el-input v-model="resetForm.email" required type="email" maxlength="254" />
          </label>
          <label>新密码（12–72 位）
            <el-input v-model="resetForm.password" required type="password" minlength="12" maxlength="72" show-password />
          </label>
          <div class="inline-actions" style="justify-content: flex-end; width: 100%; margin-top: 6px;">
            <el-button @click="resetTarget = null">取消</el-button>
            <el-button native-type="submit" type="primary" :loading="resetting">确认重置</el-button>
          </div>
        </form>
      </el-dialog>
    </main>
  </div>
</template>
