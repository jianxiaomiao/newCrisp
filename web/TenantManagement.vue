<script setup lang="ts">
import { onMounted, onUnmounted, reactive, ref } from 'vue';
import { responseError } from './api';
import { SessionError, webSession } from './web-session';

type Role = 'AGENT' | 'SUPERVISOR';
type Department = { id: string; code: string; name: string; version: string };
type Grant = { departmentId: string; role: Role };
type Member = { id: string; email: string; enabled: boolean; version: string; roles: Grant[] };

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

function disable(member: Member) {
  if (!window.confirm(`停用 ${member.email} 并退出该账号的所有工作台？`)) return;
  void submit(() => request(`/members/${member.id}/disable`, 'POST', { version: member.version }),
    '账号已停用。', '无法停用账号。');
}

onMounted(() => {
  load().catch(() => { if (!signal.aborted) emit('notice', '无法加载租户设置，请刷新页面。'); });
});
onUnmounted(() => abort.abort());
</script>

<template>
  <section id="management" aria-label="租户管理">
    <section class="management-section" aria-labelledby="departments-title">
      <h2 id="departments-title">部门管理</h2>
      <form @submit.prevent="createDepartment">
        <label>新部门代码<input v-model="newDepartment.code" required pattern="[a-z][a-z0-9\-]{2,47}" maxlength="48" autocomplete="off"></label>
        <label>部门名称<input v-model="newDepartment.name" required maxlength="120" autocomplete="off"></label>
        <button type="submit" :disabled="busy || !loaded">创建部门</button>
      </form>
      <ul :key="listRevision" class="management-list" aria-label="部门">
        <li v-for="department in departments" :key="department.id">
          <details>
            <summary>调整 {{ departmentTitle(department) }}</summary>
            <form @submit.prevent="rename(department)">
              <label>部门新名称<input v-model="names[department.id]" maxlength="120" required></label>
              <button type="submit" :disabled="busy">保存部门</button>
            </form>
          </details>
        </li>
      </ul>
    </section>
    <section class="management-section" aria-labelledby="members-title">
      <h2 id="members-title">员工管理</h2>
      <ul :key="listRevision" class="management-list" aria-label="员工">
        <li v-if="loaded && !members.length">暂无客服成员</li>
        <li v-for="member in members" :key="member.id">
          <strong>{{ member.email }}</strong>
          <p>{{ member.enabled ? '启用中' : '已停用' }}</p>
          <p>{{ grantLabels(member) || '未分配部门角色' }}</p>
          <template v-if="member.enabled">
            <details>
              <summary>调整角色</summary>
              <form @submit.prevent="saveRoles(member)">
                <fieldset class="management-roles">
                  <legend>部门角色</legend>
                  <template v-for="department in departments" :key="department.id">
                    <label v-for="role in roles" :key="role">
                      <input v-model="grants[member.id]" type="checkbox" :value="grantKey(department.id, role)">
                      {{ departmentTitle(department) }} · {{ roleNames[role] }}
                    </label>
                  </template>
                </fieldset>
                <button type="submit" :disabled="busy">保存角色</button>
              </form>
            </details>
            <form class="management-disable" @submit.prevent="disable(member)">
              <button type="submit" :disabled="busy">停用账号</button>
            </form>
          </template>
        </li>
      </ul>
      <form id="member" @submit.prevent="createMember">
        <h3>添加客服成员</h3>
        <label>部门代码
          <select v-model="newMember.departmentCode" required>
            <option v-for="department in departments" :key="department.id" :value="department.code">
              {{ departmentTitle(department) }}
            </option>
          </select>
        </label>
        <label>成员邮箱<input v-model="newMember.email" type="email" required autocomplete="off"></label>
        <label>客服称呼<input v-model="newMember.displayName" maxlength="64"></label>
        <label>初始密码<input v-model="newMember.password" required minlength="12" maxlength="72" type="password" autocomplete="new-password"></label>
        <label>职责<select v-model="newMember.role"><option value="AGENT">客服</option><option value="SUPERVISOR">主管</option></select></label>
        <button type="submit" :disabled="busy || !loaded">创建成员</button>
      </form>
    </section>
  </section>
</template>
