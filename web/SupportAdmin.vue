<script setup lang="ts">
import { onMounted, onUnmounted, reactive, ref, watch } from 'vue';
import { Check, Document, Link } from '@element-plus/icons-vue';
import { confirmAction } from './confirmation';
import { responseError } from './api';
import { SessionError, webSession } from './web-session';

type Reply = { id: string; title: string; body: string; personal?: boolean };
type Site = { id: string; title: string; origin: string; enabled: boolean };
type Tag = { id: string; name: string; color: string };
type Hours = { day: number; start: string; end: string };
type Settings = {
  zone: string; windows: Hours[] | null; maxActive: number | null; fillQueue: boolean;
  welcomeMessage?: string | null; autoCloseMinutes?: number | null;
};

const { sid, view } = defineProps<{ sid: string; view: 'rules' | 'replies' | 'sites' }>();
const emit = defineEmits<{ expired: [] }>();
const auth = webSession('staff');
const abort = new AbortController();
const weekdays = ['周一', '周二', '周三', '周四', '周五', '周六', '周日'];
const schedule = reactive(weekdays.map((label, index) => ({
  label, day: index + 1, enabled: index < 5, start: '09:00', end: '18:00',
})));
const config = reactive({
  zone: 'Asia/Shanghai', enabled: false, maxActive: '', fillQueue: false,
  welcomeMessage: '', autoCloseMinutes: '',
});
const reply = reactive({ id: '', title: '', body: '' });
const site = reactive({ title: '', origin: '' });
const tagForm = reactive({ name: '', color: '#2563eb' });
const replies = ref<Reply[]>([]);
const sites = ref<Site[]>([]);
const tags = ref<Tag[]>([]);
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
  const [settingResponse, replyResponse, siteResponse, tagResponse] = await Promise.all([
    request('/support-settings'), request('/quick-replies'), request('/guest-sites'), request('/tags'),
  ]);
  const settings: Settings = await settingResponse.json();
  Object.assign(config, {
    zone: settings.zone, enabled: settings.windows !== null,
    maxActive: settings.maxActive?.toString() ?? '', fillQueue: settings.fillQueue,
    welcomeMessage: settings.welcomeMessage ?? '', autoCloseMinutes: settings.autoCloseMinutes?.toString() ?? '',
  });
  if (settings.windows) for (const entry of schedule) {
    const saved = settings.windows.find(({ day }) => day === entry.day);
    entry.enabled = !!saved;
    if (saved) Object.assign(entry, { start: saved.start, end: saved.end });
  }
  replies.value = (await replyResponse.json() as Reply[]).filter((r) => !r.personal);
  sites.value = await siteResponse.json();
  tags.value = await tagResponse.json();
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
      zone: config.zone.trim(),
      maxActive: config.maxActive === '' ? null : Number(config.maxActive),
      fillQueue: config.fillQueue,
      welcomeMessage: config.welcomeMessage.trim() || null,
      autoCloseMinutes: config.autoCloseMinutes === '' ? null : Number(config.autoCloseMinutes),
      windows: config.enabled ? schedule.filter(({ enabled }) => enabled).map(({ day, start, end }) => ({ day, start, end })) : null,
    });
  }, '接待规则已保存。');
}

function createTag() {
  if (!tagForm.name.trim()) return;
  void run(async () => {
    await request('/tags', 'POST', { name: tagForm.name.trim(), color: tagForm.color });
    tagForm.name = '';
    tags.value = await (await request('/tags')).json();
  }, '标签已创建。');
}

function deleteTag(id: string) {
  void run(async () => {
    await request(`/tags/${id}`, 'DELETE');
    tags.value = await (await request('/tags')).json();
  }, '标签已删除。');
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

async function disableSite(id: string) {
  if (busy.value || !await confirmAction('停用入口', '停用后，访客将无法通过此入口进入客服。') || abort.signal.aborted) return;
  void run(async () => {
    await request(`/guest-sites/${id}`, 'DELETE');
    sites.value = await (await request('/guest-sites')).json();
  }, '访客入口已停用。');
}

function widgetCode(entry: Site) {
  return `<script src="${window.location.origin}/widget.js" data-site="${entry.id}" async><\/script>`;
}

function embedCode(entry: Site) {
  return `<iframe src="${window.location.origin}/embed/${entry.id}" title="在线客服" width="420" height="600"></iframe>`;
}

watch(() => view, () => { notice.value = ''; });
onMounted(() => void run(async () => { await load(); loaded.value = true; }, ''));
onUnmounted(() => abort.abort());
</script>

<template>
  <section class="support-admin" aria-label="客服配置">
    <p class="feedback" aria-live="polite">{{ notice }}</p>
    <p v-if="!loaded && busy" class="surface empty-state">正在加载客服配置…</p>
    <template v-if="loaded">
      <form v-if="view === 'rules'" class="rules-form" @submit.prevent="saveSettings">
        <div class="rules-layout">
          <section class="surface settings-card">
            <header class="section-head"><div><h2>自动分配</h2><p>优先分配给同部门在线且进行中会话最少的客服。</p></div></header>
            <div class="rules-row">
              <label for="active-limit"><strong>自动分配上限</strong><small>每位客服可通过自动分配同时接待的会话数。</small></label>
              <el-input id="active-limit" v-model="config.maxActive" type="number" min="1" max="1000" placeholder="不限" />
            </div>
            <div class="rules-row">
              <div><strong>待领取自动补派</strong><small>客服恢复接单或释放名额时，优先分配等待最久的会话。</small></div>
              <el-switch v-model="config.fillQueue" aria-label="启用待领取自动补派" />
            </div>
            <div class="rules-row">
              <label for="auto-close"><strong>无回复自动结束（分钟）</strong><small>客服回复后客户超时未回应则自动关闭并释放名额（5–43200）。</small></label>
              <el-input id="auto-close" v-model="config.autoCloseMinutes" type="number" min="5" max="43200" placeholder="不自动关闭" />
            </div>
            <div class="field-stack">
              <label for="welcome-msg"><strong>自动欢迎语</strong><small>客户发起新会话时自动发送一条系统欢迎消息。</small></label>
              <el-input id="welcome-msg" v-model="config.welcomeMessage" type="textarea" :rows="2" maxlength="1000" placeholder="例如：您好，请问有什么可以帮您？" />
            </div>
          </section>
          <section class="surface settings-card">
            <header class="section-head">
              <div><h2>营业时间</h2><p>非营业时间可以继续留言，客户会看到下次接待时间。</p></div>
              <el-switch v-model="config.enabled" aria-label="启用营业时间" />
            </header>
            <template v-if="config.enabled">
              <div class="rules-row">
                <label for="service-zone"><strong>营业时区</strong></label>
                <el-input id="service-zone" v-model="config.zone" required maxlength="64" />
              </div>
              <div v-for="entry in schedule" :key="entry.day" class="schedule-row">
                <el-checkbox v-model="entry.enabled">{{ entry.label }}</el-checkbox>
                <template v-if="entry.enabled">
                  <el-input v-model="entry.start" :aria-label="`${entry.label}开始`" required type="time" />
                  <span>至</span>
                  <el-input v-model="entry.end" :aria-label="`${entry.label}结束`" required type="time" />
                </template>
                <span v-else>休息</span>
              </div>
            </template>
          </section>
        </div>
        <el-button native-type="submit" type="primary" :icon="Check" :disabled="busy">保存接待规则</el-button>
      </form>

      <section v-if="view === 'rules'" class="surface settings-card" style="margin-top: 16px;">
        <header class="section-head"><div><h2>会话标签</h2><p>客服可为会话打上业务分类标签，便于筛选与统计。</p></div></header>
        <form class="inline-actions" @submit.prevent="createTag">
          <el-input v-model="tagForm.name" placeholder="新标签名称（如：账单、Bug）" maxlength="32" style="max-width: 220px;" />
          <input v-model="tagForm.color" type="color" aria-label="标签颜色" style="height: 32px; width: 42px; border: 0; background: transparent; cursor: pointer;">
          <el-button native-type="submit" type="primary" plain :disabled="busy || !tagForm.name.trim()">添加标签</el-button>
        </form>
        <div class="inline-actions" style="margin-top: 12px; flex-wrap: wrap;">
          <el-tag v-for="t in tags" :key="t.id" closable :color="t.color" effect="dark" style="border: 0;" @close="deleteTag(t.id)">{{ t.name }}</el-tag>
          <span v-if="!tags.length" class="list-empty">暂无标签</span>
        </div>
      </section>

      <div v-else-if="view === 'replies'" class="settings-layout">
        <section class="surface settings-card">
          <div class="section-head"><h2>{{ reply.id ? '编辑快捷回复' : '新增快捷回复' }}</h2></div>
          <form class="field-stack" @submit.prevent="saveReply">
            <label>快捷回复标题<el-input v-model="reply.title" aria-label="快捷回复标题" required maxlength="80" /></label>
            <label>快捷回复正文<el-input v-model="reply.body" aria-label="快捷回复正文" type="textarea" required maxlength="4000" :rows="5" /></label>
            <div class="inline-actions">
              <el-button native-type="submit" type="primary" :disabled="busy">保存快捷回复</el-button>
              <el-button v-if="reply.id" :disabled="busy" @click="clearReply">取消编辑</el-button>
            </div>
          </form>
        </section>
        <section class="surface settings-card">
          <div class="section-head"><h2>回复列表</h2></div>
          <ul class="settings-list">
            <li v-for="entry in replies" :key="entry.id">
              <div class="settings-item">
                <span class="file-icon"><el-icon><Document /></el-icon></span>
                <div><strong>{{ entry.title }}</strong><p>{{ entry.body }}</p></div>
                <div class="inline-actions">
                  <el-button link type="primary" :disabled="busy" @click="Object.assign(reply, entry);">编辑</el-button>
                  <el-button link type="danger" :disabled="busy" @click="deleteReply(entry.id)">删除</el-button>
                </div>
              </div>
            </li>
            <li v-if="!replies.length" class="list-empty">暂无快捷回复</li>
          </ul>
        </section>
      </div>

      <div v-else class="settings-layout">
        <section class="surface settings-card">
          <div class="section-head"><h2>创建访客入口</h2></div>
          <form class="field-stack" @submit.prevent="saveSite">
            <label>入口名称<el-input v-model="site.title" aria-label="入口名称" required maxlength="80" /></label>
            <label>允许嵌入的网站来源
              <el-input v-model="site.origin" aria-label="允许嵌入的网站来源" required type="url" maxlength="255" placeholder="https://example.com" />
            </label>
            <el-button native-type="submit" type="primary" :disabled="busy">创建访客入口</el-button>
          </form>
        </section>
        <section class="surface settings-card">
          <div class="section-head"><h2>访客入口列表</h2></div>
          <ul class="settings-list">
            <li v-for="entry in sites" :key="entry.id">
              <div class="settings-item">
                <span class="file-icon"><el-icon><Link /></el-icon></span>
                <div><strong>{{ entry.title }}</strong><p>{{ entry.origin }}</p></div>
                <el-tag size="small" round :class="entry.enabled ? 'tag-success' : 'tag-neutral'">{{ entry.enabled ? '启用中' : '已停用' }}</el-tag>
              </div>
              <div v-if="entry.enabled" class="field-stack site-embed">
                <label>悬浮窗接入代码（推荐，Crisp 样式右下角弹出）<el-input type="textarea" aria-label="悬浮窗接入代码" readonly :rows="2" :model-value="widgetCode(entry)" /></label>
                <label>嵌入代码<el-input type="textarea" aria-label="嵌入代码" readonly :rows="3" :model-value="embedCode(entry)" /></label>
                <el-button link type="danger" :disabled="busy" @click="disableSite(entry.id)">停用入口</el-button>
              </div>
            </li>
            <li v-if="!sites.length" class="list-empty">暂无访客入口</li>
          </ul>
        </section>
      </div>
    </template>
  </section>
</template>
