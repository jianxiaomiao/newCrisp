<script setup lang="ts">
import { onMounted, onUnmounted, reactive, ref } from 'vue';
import { responseError } from './api';
import { SessionError, webSession } from './web-session';

type Reply = { id: string; title: string; body: string };
type Site = { id: string; title: string; origin: string; enabled: boolean };
type Hours = { day: number; start: string; end: string };
type Settings = { zone: string; windows: Hours[] | null; maxActive: number | null; fillQueue: boolean };

const { sid } = defineProps<{ sid: string }>();
const emit = defineEmits<{ expired: [] }>();
const auth = webSession('staff');
const abort = new AbortController();
const weekdays = ['周一', '周二', '周三', '周四', '周五', '周六', '周日'];
const schedule = reactive(weekdays.map((label, index) => ({
  label, day: index + 1, enabled: index < 5, start: '09:00', end: '18:00',
})));
const config = reactive({ zone: 'Asia/Shanghai', enabled: false, maxActive: '', fillQueue: false });
const reply = reactive({ id: '', title: '', body: '' });
const site = reactive({ title: '', origin: '' });
const replies = ref<Reply[]>([]);
const sites = ref<Site[]>([]);
const notice = ref('');
const loaded = ref(false);
const busy = ref(false);

async function request(path: string, method = 'GET', body?: object) {
  const response = await auth.request(`/api/v1/staff${path}`, {
    method, signal: abort.signal,
    ...(body && { headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) }),
  }, sid);
  if (response.ok) return response;
  const error = await responseError(response);
  if (error.status === 401) emit('expired');
  throw error;
}

async function load() {
  const [settingResponse, replyResponse, siteResponse] = await Promise.all([
    request('/support-settings'), request('/quick-replies'), request('/guest-sites'),
  ]);
  const settings: Settings = await settingResponse.json();
  Object.assign(config, {
    zone: settings.zone, enabled: settings.windows !== null,
    maxActive: settings.maxActive?.toString() ?? '', fillQueue: settings.fillQueue,
  });
  if (settings.windows) for (const entry of schedule) {
    const saved = settings.windows.find(({ day }) => day === entry.day);
    entry.enabled = !!saved;
    if (saved) Object.assign(entry, { start: saved.start, end: saved.end });
  }
  replies.value = await replyResponse.json();
  sites.value = await siteResponse.json();
}

async function run(task: () => Promise<void>, message: string) {
  if (busy.value) return;
  busy.value = true;
  notice.value = '';
  try {
    await task();
    notice.value = message;
  } catch (error) {
    if (!abort.signal.aborted) {
      const code = error instanceof SessionError ? error.code : '';
      notice.value = code === 'INVALID_SUPPORT_SETTINGS' ? '请检查时区、营业时间和接单上限。'
        : code === 'INVALID_GUEST_ORIGIN' ? '请输入完整的网站来源，例如 https://example.com。'
        : '操作未完成，请重试。';
    }
  } finally {
    busy.value = false;
  }
}

function saveSettings() {
  void run(async () => {
    await request('/support-settings', 'PUT', {
      zone: config.zone.trim(), maxActive: config.maxActive === '' ? null : Number(config.maxActive), fillQueue: config.fillQueue,
      windows: config.enabled ? schedule.filter(({ enabled }) => enabled).map(({ day, start, end }) => ({ day, start, end })) : null,
    });
  }, '接待规则已保存。');
}

function clearReply() { Object.assign(reply, { id: '', title: '', body: '' }); }

function saveReply() {
  void run(async () => {
    const suffix = reply.id ? `/${reply.id}` : '';
    await request(`/quick-replies${suffix}`, reply.id ? 'PUT' : 'POST', { title: reply.title, body: reply.body });
    clearReply();
    replies.value = await (await request('/quick-replies')).json();
  }, '快捷回复已保存。');
}

function deleteReply(id: string) {
  void run(async () => {
    await request(`/quick-replies/${id}`, 'DELETE');
    if (reply.id === id) clearReply();
    replies.value = await (await request('/quick-replies')).json();
  }, '快捷回复已删除。');
}

function saveSite() {
  void run(async () => {
    await request('/guest-sites', 'POST', { title: site.title, origin: site.origin.trim() });
    Object.assign(site, { title: '', origin: '' });
    sites.value = await (await request('/guest-sites')).json();
  }, '访客入口已创建。');
}

function disableSite(id: string) {
  if (!window.confirm('停用此访客入口？')) return;
  void run(async () => {
    await request(`/guest-sites/${id}`, 'DELETE');
    sites.value = await (await request('/guest-sites')).json();
  }, '访客入口已停用。');
}

function embedCode(entry: Site) {
  return `<iframe src="${window.location.origin}/embed/${entry.id}" title="在线客服" width="420" height="600"></iframe>`;
}

onMounted(() => void run(async () => { await load(); loaded.value = true; }, ''));
onUnmounted(() => abort.abort());
</script>

<template>
  <section class="support-admin" aria-label="客服配置">
    <p aria-live="polite">{{ notice }}</p>
    <details>
      <summary>接待规则</summary>
      <form v-if="loaded" @submit.prevent="saveSettings">
        <label>自动分配上限<input v-model="config.maxActive" type="number" min="1" max="1000" placeholder="不限"></label>
        <label class="check-line"><input v-model="config.fillQueue" type="checkbox">启用待领取自动补派</label>
        <label class="check-line"><input v-model="config.enabled" type="checkbox">启用营业时间</label>
        <template v-if="config.enabled">
          <label>营业时区<input v-model="config.zone" required maxlength="64"></label>
          <div v-for="entry in schedule" :key="entry.day" class="schedule-row">
            <label class="check-line"><input v-model="entry.enabled" type="checkbox">{{ entry.label }}</label>
            <label v-if="entry.enabled">开始<input v-model="entry.start" required type="time"></label>
            <label v-if="entry.enabled">结束<input v-model="entry.end" required type="time"></label>
          </div>
        </template>
        <button :disabled="busy">保存接待规则</button>
      </form>
    </details>
    <details>
      <summary>共享快捷回复</summary>
      <form v-if="loaded" @submit.prevent="saveReply">
        <label>快捷回复标题<input v-model="reply.title" required maxlength="80"></label>
        <label>快捷回复正文<textarea v-model="reply.body" required maxlength="4000"></textarea></label>
        <button :disabled="busy">保存快捷回复</button>
        <button v-if="reply.id" type="button" @click="clearReply">取消编辑</button>
      </form>
      <ul>
        <li v-for="entry in replies" :key="entry.id">
          {{ entry.title }}
          <button type="button" :disabled="busy" @click="Object.assign(reply, entry)">编辑</button>
          <button type="button" :disabled="busy" @click="deleteReply(entry.id)">删除</button>
        </li>
      </ul>
    </details>
    <details>
      <summary>访客入口</summary>
      <form v-if="loaded" @submit.prevent="saveSite">
        <label>入口名称<input v-model="site.title" required maxlength="80"></label>
        <label>允许嵌入的网站来源<input v-model="site.origin" required type="url" maxlength="255" placeholder="https://example.com"></label>
        <button :disabled="busy">创建访客入口</button>
      </form>
      <ul>
        <li v-for="entry in sites" :key="entry.id">
          <p>{{ entry.title }} · {{ entry.origin }}</p>
          <template v-if="entry.enabled">
            <label>嵌入代码<textarea readonly :value="embedCode(entry)"></textarea></label>
            <button type="button" :disabled="busy" @click="disableSite(entry.id)">停用入口</button>
          </template>
          <span v-else>已停用</span>
        </li>
      </ul>
    </details>
  </section>
</template>
