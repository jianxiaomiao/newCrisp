<script setup lang="ts">
import { computed, onMounted, onUnmounted, reactive, ref } from 'vue';
import { Grid, Plus, Search } from '@element-plus/icons-vue';
import { confirmAction } from './confirmation';
import { responseError } from './api';
import { SessionError, webSession } from './web-session';

type Role = 'AGENT' | 'SUPERVISOR';
type Department = { id: string; code: string; name: string; version: string };
type Grant = { departmentId: string; role: Role };
type Member = { id: string; email: string; displayName: string; enabled: boolean; version: string; roles: Grant[] };

const { sid } = defineProps<{ sid: string }>();
const emit = defineEmits<{ notice: [message: string]; expired: [] }>();
const auth = webSession('staff');
const abort = new AbortController();
const { signal } = abort;
const roleNames: Record<Role, string> = { AGENT: '客服', SUPERVISOR: '主管' };
const roles: Role[] = ['AGENT', 'SUPERVISOR'];
const failures = new Map([
  ['DEPARTMENT_ALREADY_EXISTS', '该部门代码已存在。'],
  ['MANAGEMENT_VERSION_CHANGED', '此记录已被更新，已加载最新设置，请重新操作。'],
  ['STAFF_DISABLED', '账号已停用，已加载最新设置。'],
  ['SESSION_CHANGED', '登录已变化，请重新登录。'],
]);

const departments = ref<Department[]>([]);
const members = ref<Member[]>([]);
const names = ref<Record<string, string>>({});
const grants = ref<Record<string, string[]>>({});
const busy = ref(false);
const loaded = ref(false);
const listRevision = ref(0);
const departmentFilter = ref('');
const roleFilter = ref<Role | ''>('');
const statusFilter = ref('');
const memberSearch = ref('');
const showDepartmentForm = ref(false);
const showMemberForm = ref(false);
const editingId = ref('');
const editing = computed(() => members.value.find(({ id, enabled }) => id === editingId.value && enabled));
const filteredMembers = computed(() => {
  const term = memberSearch.value.trim().toLowerCase();
  return members.value.filter((member) => {
    const assigned = member.roles.filter(({ departmentId }) => !departmentFilter.value || departmentId === departmentFilter.value);
    return (!departmentFilter.value || assigned.length > 0)
      && (!roleFilter.value || assigned.some(({ role }) => role === roleFilter.value))
      && (!statusFilter.value || member.enabled === (statusFilter.value === 'enabled'))
      && (!term || `${member.displayName} ${member.email}`.toLowerCase().includes(term));
  });
});
const memberCount = (id: string) => members.value.filter(({ roles }) => roles.some(({ departmentId }) => id === departmentId)).length;
const newDepartment = reactive({ code: '', name: '' });
const newMember = reactive({ departmentCode: 'support', email: '', displayName: '', password: '', role: 'AGENT' as Role });

const departmentTitle = ({ code, name }: Department) => `${name}（${code}）`;
const grantKey = (departmentId: string, role: Role) => `${departmentId}:${role}`;

function grantLabels(member: Member) {
  const assigned = new Set(member.roles.map(({ departmentId, role }) => grantKey(departmentId, role)));
  return departments.value.flatMap((department) => roles.filter((role) => assigned.has(grantKey(department.id, role)))
    .map((role) => `${departmentTitle(department)} · ${roleNames[role]}`)).join('、');
}

async function request(path: string, method = 'GET', body?: object) {
  const response = await auth.request(`/api/v1/staff${path}`, {
    signal, method, ...(body && { headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) }),
  }, sid);
  if (response.ok) return response;
  const error = await responseError(response);
  if (error.status === 401 && !signal.aborted) emit('expired');
  throw error;
}

async function load() {
  const [departmentResponse, memberResponse] = await Promise.all([request('/departments'), request('/members')]);
  const [nextDepartments, nextMembers]: [Department[], Member[]] = await Promise.all([
    departmentResponse.json(), memberResponse.json(),
  ]);
  if (signal.aborted) return;
  departments.value = nextDepartments;
  members.value = nextMembers;
  names.value = Object.fromEntries(nextDepartments.map((department) => [department.id, department.name]));
  grants.value = Object.fromEntries(nextMembers.map((member) => [
    member.id, member.roles.map(({ departmentId, role }) => grantKey(departmentId, role)),
  ]));
  editingId.value = '';
  if (!nextDepartments.some(({ code }) => code === newMember.departmentCode)) {
    newMember.departmentCode = nextDepartments[0]?.code ?? '';
  }
  listRevision.value++;
  loaded.value = true;
}

async function submit(action: () => Promise<unknown>, success: string, fallback: string) {
  if (busy.value || !loaded.value) return;
  busy.value = true;
  emit('notice', '');
  try {
    await action();
    try {
      await load();
      if (!signal.aborted) emit('notice', success);
    } catch {
      if (!signal.aborted) emit('notice', `${success}列表加载失败，请刷新页面。`);
    }
  } catch (error) {
    if (signal.aborted) return;
    if (error instanceof SessionError && ['MANAGEMENT_VERSION_CHANGED', 'STAFF_DISABLED'].includes(error.code)) {
      try { await load(); }
      catch { if (!signal.aborted) emit('notice', '无法加载最新设置，请刷新页面。'); return; }
    }
    if (!signal.aborted) emit('notice', error instanceof SessionError ? failures.get(error.code) ?? fallback : fallback);
  } finally {
    busy.value = false;
  }
}

function createDepartment() {
  void submit(async () => {
    await request('/departments', 'POST', newDepartment);
    Object.assign(newDepartment, { code: '', name: '' });
    showDepartmentForm.value = false;
  }, '部门已创建。', '无法创建部门，请核对代码和名称。');
}

function rename(department: Department) {
  void submit(() => request(`/departments/${department.id}`, 'PUT', {
    name: names.value[department.id], version: department.version,
  }), '部门已更新。', '无法调整部门，请核对名称。');
}

function createMember() {
  void submit(async () => {
    await request('/members', 'POST', newMember);
    Object.assign(newMember, { email: '', displayName: '', password: '', role: 'AGENT' });
    showMemberForm.value = false;
  }, '成员已创建。', '无法创建成员，请核对邮箱、部门和密码。');
}

function saveRoles(member: Member) {
  const checked = new Set(grants.value[member.id]);
  const updated = departments.value.flatMap((department) => roles.filter((role) => checked.has(grantKey(department.id, role)))
    .map((role) => ({ departmentId: department.id, role })));
  void submit(() => request(`/members/${member.id}/roles`, 'PUT', {
    version: member.version, roles: updated,
  }), '部门角色已更新。', '无法更新部门角色。');
}

async function disable(member: Member) {
  if (busy.value || !await confirmAction('停用账号', `停用 ${member.email} 并退出该账号的所有工作台？`) || signal.aborted) return;
  void submit(() => request(`/members/${member.id}/disable`, 'POST', { version: member.version }),
    '账号已停用。', '无法停用账号。');
}

onMounted(() => {
  load().catch(() => { if (!signal.aborted) emit('notice', '无法加载租户设置，请刷新页面。'); });
});
onUnmounted(() => abort.abort());
</script>

<template>
  <section id="management" class="admin-layout" aria-label="租户管理">
    <section class="surface department" aria-labelledby="departments-title">
      <div class="section-head">
        <h2 id="departments-title">部门管理</h2>
        <el-button circle :icon="Plus" aria-label="新增部门" :aria-expanded="showDepartmentForm" @click="showDepartmentForm = !showDepartmentForm" />
      </div>
      <form v-if="showDepartmentForm" class="field-stack field-panel" @submit.prevent="createDepartment">
        <label>新部门代码
          <el-input v-model="newDepartment.code" aria-label="新部门代码" required pattern="[a-z][a-z0-9\-]{2,47}" maxlength="48" autocomplete="off" />
        </label>
        <label>部门名称
          <el-input v-model="newDepartment.name" aria-label="部门名称" required maxlength="120" autocomplete="off" />
        </label>
        <el-button native-type="submit" type="primary" :disabled="busy || !loaded">创建部门</el-button>
      </form>
      <div class="department-filters">
        <el-button :class="{ 'department-active': !departmentFilter }" :aria-pressed="!departmentFilter" :icon="Grid" @click="departmentFilter = ''">
          <span>全部部门</span><small>{{ members.length }}</small>
        </el-button>
        <el-button v-for="item in departments" :key="item.id" :class="{ 'department-active': departmentFilter === item.id }"
          :aria-pressed="departmentFilter === item.id" @click="departmentFilter = item.id">
          <span>{{ item.name }}</span><small>{{ memberCount(item.id) }}</small>
        </el-button>
      </div>
      <details class="department-settings">
        <summary>调整部门</summary>
        <ul :key="listRevision" class="management-list" aria-label="部门">
          <li v-for="item in departments" :key="item.id">
            <details>
              <summary>调整 {{ departmentTitle(item) }}</summary>
              <form class="field-stack" @submit.prevent="rename(item)">
                <label>部门新名称<el-input v-model="names[item.id]" aria-label="部门新名称" maxlength="120" required /></label>
                <el-button native-type="submit" type="primary" :disabled="busy">保存部门</el-button>
              </form>
            </details>
          </li>
        </ul>
      </details>
    </section>

    <section class="surface members" aria-labelledby="members-title">
      <div class="section-head">
        <div><h2 id="members-title">员工管理</h2><p>共 {{ filteredMembers.length }} 人</p></div>
        <el-button type="primary" :icon="Plus" :aria-expanded="showMemberForm" @click="showMemberForm = !showMemberForm">新增员工</el-button>
      </div>
      <form v-if="showMemberForm" id="member" class="field-stack field-panel" @submit.prevent="createMember">
        <h3>添加客服成员</h3>
        <div class="form-columns">
          <label>部门代码
            <select v-model="newMember.departmentCode" required>
              <option v-for="item in departments" :key="item.id" :value="item.code">{{ departmentTitle(item) }}</option>
            </select>
          </label>
          <label>职责
            <select v-model="newMember.role"><option value="AGENT">客服</option><option value="SUPERVISOR">主管</option></select>
          </label>
          <label>成员邮箱
            <el-input v-model="newMember.email" aria-label="成员邮箱" type="email" required autocomplete="off" />
          </label>
          <label>客服称呼
            <el-input v-model="newMember.displayName" aria-label="客服称呼" maxlength="64" />
          </label>
          <label>初始密码
            <el-input v-model="newMember.password" aria-label="初始密码" required minlength="12" maxlength="72" type="password" autocomplete="new-password" show-password />
          </label>
        </div>
        <el-button native-type="submit" type="primary" :disabled="busy || !loaded">创建成员</el-button>
      </form>

      <div class="member-filters">
        <el-input v-model="memberSearch" :prefix-icon="Search" aria-label="搜索姓名或邮箱" placeholder="搜索姓名或邮箱" clearable />
        <select v-model="roleFilter" aria-label="筛选角色">
          <option value="">全部角色</option><option value="AGENT">客服</option><option value="SUPERVISOR">主管</option>
        </select>
        <select v-model="statusFilter" aria-label="筛选状态">
          <option value="">全部状态</option><option value="enabled">启用</option><option value="disabled">停用</option>
        </select>
      </div>
      <el-table :data="filteredMembers" row-key="id" class="data-table members-table" empty-text="暂无客服成员">
        <el-table-column label="员工" min-width="200">
          <template #default="{ row }">
            <div class="table-person">
              <el-avatar :size="36">{{ (row.displayName || row.email).charAt(0).toUpperCase() }}</el-avatar>
              <span><strong>{{ row.displayName || row.email }}</strong><small v-if="row.displayName">{{ row.email }}</small></span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="部门与角色" min-width="185">
          <template #default="{ row }">{{ grantLabels(row as Member) || '未分配部门角色' }}</template>
        </el-table-column>
        <el-table-column label="状态" width="84">
          <template #default="{ row }">
            <el-tag :class="row.enabled ? 'tag-success' : 'tag-neutral'" round size="small">{{ row.enabled ? '启用中' : '已停用' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="146" align="right">
          <template #default="{ row }">
            <template v-if="row.enabled">
              <el-button link type="primary" :aria-expanded="editingId === row.id" @click="editingId = editingId === row.id ? '' : row.id">调整角色</el-button>
              <el-button link type="danger" :disabled="busy" @click="disable(row as Member)">停用账号</el-button>
            </template>
          </template>
        </el-table-column>
      </el-table>
      <form v-if="editing" class="field-panel field-stack" aria-label="调整部门角色" @submit.prevent="saveRoles(editing)">
        <h3>{{ editing.displayName || editing.email }} · 调整部门角色</h3>
        <fieldset class="management-roles">
          <legend>部门角色</legend>
          <el-checkbox-group v-model="grants[editing.id]">
            <div v-for="item in departments" :key="item.id" class="grant-options">
              <el-checkbox v-for="role in roles" :key="role" :value="grantKey(item.id, role)">{{ departmentTitle(item) }} · {{ roleNames[role] }}</el-checkbox>
            </div>
          </el-checkbox-group>
        </fieldset>
        <el-button native-type="submit" type="primary" :disabled="busy">保存角色</el-button>
      </form>
    </section>
  </section>
</template>
