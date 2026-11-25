<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, shallowRef, watch } from 'vue';
import { responseError } from './api';
import { SessionError, type SessionSide } from './web-session';
import AttachmentMessage from './AttachmentMessage.vue';
import { preferenceKey, staffNotification } from './staff-notifications';

type Status = 'WAITING' | 'ACTIVE' | 'CLOSED';
type View = 'CURRENT' | 'HISTORY' | 'PENDING' | 'MINE' | 'CLOSED' | 'DEPARTMENT';
type Operation = 'claim' | 'return' | 'transfer' | 'close';
type ConversationRef = { id: string; status: Status; stateVersion: string; lastCustomerSeq: string };
type ListItem = ConversationRef & { preview: string | null; unread: number; timestamp: string; queuedAt: string };
type Detail = ConversationRef & {
  readSeq: string; canReply: boolean; canReturn: boolean; canTransfer: boolean; canClose: boolean;
  assignee: string | null; customerOnline: boolean | null; assigneeOnline: boolean | null; queuedAt: string;
};
type ChatMessage = {
  conversationId: string; seq: string; sender: 'CUSTOMER' | 'STAFF'; text: string;
  attachment?: { name: string; type: string; size: number } | null;
};
type Reply = { id: string; title: string; body: string };
type ServiceHours = { open: boolean; nextOpen: string | null; zone: string };
type Agent = { id: string; name: string; email: string; department: string; online: boolean; accepting: boolean; active: number };
type MessagePage = { items: ChatMessage[]; hasMore: boolean };
type ConversationPage = { items: ListItem[]; nextCursor: string | null };
type Counts = { pending: number; unread: number };
type StaffOption = { id: string; displayName: string; email: string };
type ServerHistory = {
  action: 'CLAIM' | 'RETURN' | 'TRANSFER' | 'CLOSE' | 'AUTO'; operator: string; occurredAt: string; reason: string | null;
  previousAssignee: StaffOption | null; newAssignee: StaffOption | null;
};
type TransferDraft = { row: ConversationRef; candidates?: StaffOption[] };
type Ticket = { sessionId: string; ticket: string };
type Ack = { message: ChatMessage };
type SendAttempt = { id: string | null; text: string; clientMessageId: string };
type Frame =
  | { type: 'ready' | 'ping'; data: string }
  | { type: 'sync.required' }
  | { type: 'staff.notification'; data: { conversationId: string; eventId: string; assigned: boolean } }
  | { type: 'message.created'; data: ChatMessage }
  | { type: 'message.ack'; requestId: string; data: Ack }
  | { type: 'error'; requestId: string | null; data: { status: number; code: string } };

const { side, identity, auth, sid, accessVersion, canQueue, canSupervise } = defineProps<{
  side: SessionSide; identity: string; auth: { request: (path: string, options?: RequestInit, expectedSid?: string) => Promise<Response> }; sid: string;
  accessVersion?: string; canQueue?: boolean; canSupervise?: boolean;
}>();
const emit = defineEmits<{ expired: [] }>();
const customer = side === 'customer';
const views: [View, string][] = customer ? [['CURRENT', '当前会话'], ['HISTORY', '历史会话']] : [];
if (canQueue) views.push(['PENDING', '待领取'], ['MINE', '我负责'], ['CLOSED', '已结束']);
if (canSupervise) views.push(['DEPARTMENT', '部门会话']);

const labels: Record<Status, string> = { WAITING: '等待客服', ACTIVE: '客服已接入', CLOSED: '已结束' };
const historyLabels = { CLAIM: '领取', RETURN: '退回队列', TRANSFER: '转派', CLOSE: '结束', AUTO: '自动分配' };
const errorMessages = new Map([
  ['STATE_VERSION_CHANGED', '会话已变化，已刷新最新状态。'],
  ['ALREADY_CLAIMED', '已被其他客服领取。'],
  ['ALREADY_ASSIGNED', '该客服已负责本会话，请重新选择。'],
  ['NEW_CUSTOMER_MESSAGES', '客户有新留言，请阅读后重新确认结束。'],
  ['CONVERSATION_CLOSED', '此会话已结束。可保留文字，手动发起新会话。'],
  ['NOT_FOUND', '当前无法访问此会话。'],
  ['SESSION_REQUIRED', '登录已失效，请重新进入。'],
  ['SEND_RESULT_UNKNOWN', '发送结果待确认，重试会沿用本次发送。'],
  ['INVALID_ATTACHMENT_SIZE', '文件须小于或等于 5 MB。'],
  ['UNSUPPORTED_ATTACHMENT_TYPE', '请选择 PNG、JPEG 或 PDF 文件。'],
  ['INVALID_ATTACHMENT_NAME', '文件名太长或含不支持的字符。'],
]);

const view = ref<View>(views[0][0]);
const selection = ref<string | null>(null);
const detail = ref<Detail | null>(null);
const rows = shallowRef<ListItem[]>([]);
const visibleMessages = shallowRef<ChatMessage[]>([]);
const cursor = ref<string | null>(null);
const hasOlder = ref(false);
const draft = ref('');
const sending = ref(false);
const history = ref<HTMLDetailsElement>();
const historyRows = shallowRef<ServerHistory[]>([]);
const log = ref<HTMLOListElement>();
const summary = ref('');
const connection = ref('正在连接聊天…');
const notice = ref('');
const serviceHours = ref<ServiceHours>();
const accepting = ref<boolean>();
const updatingAvailability = ref(false);
const team = ref<Agent[]>([]);
const showTeam = ref(false);
const replies = ref<Reply[]>([]);
const showReplies = ref(false);
const query = ref('');
const matches = computed(() => {
  const term = query.value.trim().toLowerCase();
  return replies.value.filter((reply) => `${reply.title} ${reply.body}`.toLowerCase().includes(term));
});
const queuedClock = ref(Date.now());
const notifications = ref(!customer && localStorage.getItem(preferenceKey(identity)) === 'on');
const fileInput = ref<HTMLInputElement>();
const attachment = shallowRef<{ file: File; key: string; conversation: string | null }>();
const transfer = shallowRef<TransferDraft>();
const transferTarget = ref('');
const transferring = ref(false);
const canReply = computed(() => detail.value?.canReply || (customer && view.value === 'CURRENT' && !selection.value));
const meta = computed(() => detail.value?.status === 'WAITING' && customer ? '可继续留言，稍后回来查看回复。'
  : detail.value?.customerOnline === true ? '客户在线' : detail.value?.customerOnline === false ? '客户离线' : '');
const records = new Map<string, ChatMessage>();
const actionKeys = new Map<string, string>();
const pending = new Map<string, { resolve: (data: Ack) => void; reject: (error: SessionError) => void }>();
const abort = new AbortController();
let loaded = false;
let sendAttempt: SendAttempt | null = null;
let reading: Promise<void> | null = null;
let socket: WebSocket | undefined;
let ready = false;
let closed = false;
let retryTimer: number | undefined;
let interval: number | undefined;
let reconnectDelay = 500;
let dirty = false;
let syncing = false;

async function request(path: string, options?: RequestInit): Promise<Response> {
  const headers: Record<string, string> = { 'X-NewCrisp-Actor': identity };
  if (accessVersion !== undefined) headers['X-NewCrisp-Staff-Access'] = accessVersion;
  if (typeof options?.body === 'string') headers['Content-Type'] = 'application/json';
  const response = await auth.request(`/api/v1/${side}${path}`, { signal: abort.signal, ...options, headers }, sid);
  if (response.ok) return response;
  const error = await responseError(response);
  if (error.status === 401 || error.code === 'STAFF_ACCESS_CHANGED') invalidate();
  throw error;
}

async function api<T = void>(path: string, options?: RequestInit): Promise<T> {
  const response = await request(path, options);
  return response.status === 204 ? undefined as T : response.json();
}

async function readControls() {
  try {
    if (customer) serviceHours.value = await api<ServiceHours>('/service-hours');
    if (canQueue && !updatingAvailability.value) accepting.value = (await api<{ accepting: boolean }>('/availability')).accepting;
    if (canSupervise && showTeam.value) team.value = await api<Agent[]>('/availability/team');
  } catch (error) { showError(error); }
}

async function toggleAvailability() {
  if (accepting.value === undefined || updatingAvailability.value) return;
  updatingAvailability.value = true;
  try {
    accepting.value = (await api<{ accepting: boolean }>('/availability', {
      method: 'PUT', body: JSON.stringify({ accepting: !accepting.value }),
    })).accepting;
  } catch (error) { showError(error); }
  finally { updatingAvailability.value = false; }
}

async function toggleReplies() {
  showReplies.value = !showReplies.value;
  if (!showReplies.value) return;
  try { replies.value = await api<Reply[]>('/quick-replies'); }
  catch (error) { showError(error); }
}

async function toggleNotifications() {
  if (notifications.value) {
    localStorage.removeItem(preferenceKey(identity));
    notifications.value = false;
    return;
  }
  if (!('Notification' in window) || await Notification.requestPermission() !== 'granted') {
    notice.value = '请在浏览器中允许本站发送通知。';
    return;
  }
  localStorage.setItem(preferenceKey(identity), 'on');
  notifications.value = true;
}

async function openNotification(id: string) {
  if (closed) return;
  try {
    const current = await api<Detail>(`/conversations/${id}`);
    if (current.canReply && !closed) {
      changeView('MINE');
      openConversation(id);
    }
  } catch (error) { showError(error); }
}

function waitMinutes(timestamp: string) { return Math.max(0, Math.floor((queuedClock.value - Date.parse(timestamp)) / 60000)); }

function pickFile(event: Event) {
  const input = event.target as HTMLInputElement;
  const file = input.files?.[0];
  if (file && file.size > 5 * 1024 * 1024) {
    notice.value = '文件须小于或等于 5 MB。';
    input.value = '';
    attachment.value = undefined;
    return;
  }
  attachment.value = file ? { file, key: crypto.randomUUID(), conversation: selection.value } : undefined;
}

async function uploadFile() {
  const pending = attachment.value;
  if (!pending || sending.value) return;
  sending.value = true;
  const form = new FormData();
  form.set('file', pending.file);
  form.set('clientMessageId', pending.key);
  if (detail.value) form.set('stateVersion', detail.value.stateVersion);
  try {
    const base = pending.conversation ? `/conversations/${pending.conversation}` : '/conversations';
    const result = await api<Ack>(`${base}/attachments`, { method: 'POST', body: form });
    if (attachment.value === pending) {
      if (!selection.value) select(result.message.conversationId, true);
      attachment.value = undefined;
      if (fileInput.value) fileInput.value.value = '';
      notice.value = '';
    }
  } catch (error) { showError(error); }
  finally { sending.value = false; void sync(); }
}

async function attachmentBlob(message: ChatMessage) {
  return (await request(`/conversations/${message.conversationId}/attachments/${message.seq}`)).blob();
}

function showError(error: unknown) {
  if (!closed) {
    const code = error instanceof SessionError ? error.code : '';
    notice.value = errorMessages.get(code) ?? '暂时无法完成操作，请稍后重试。';
  }
}

function select(id: string | null, preserveDraft = false) {
  if (selection.value === id) return;
  selection.value = id;
  detail.value = null;
  records.clear();
  visibleMessages.value = [];
  loaded = false;
  hasOlder.value = false;
  reading = null;
  if (history.value) history.value.open = false;
  historyRows.value = [];
  transfer.value = undefined;
  if (!preserveDraft) {
    draft.value = '';
    sendAttempt = null;
    attachment.value = undefined;
    showReplies.value = false;
    if (fileInput.value) fileInput.value.value = '';
  }
}

function changeView(nextView: View, preserveDraft = false) {
  view.value = nextView;
  select(null, preserveDraft);
  rows.value = [];
  cursor.value = null;
  void sync();
}

function openConversation(id: string) {
  select(id);
  void sync();
}

function merge(collection: ChatMessage[], preserveScroll = false) {
  const element = log.value;
  const distance = element ? element.scrollHeight - element.scrollTop : 0;
  const atBottom = !element || distance - element.clientHeight < 25;
  const id = selection.value;
  for (const message of collection) records.set(message.seq, message);
  visibleMessages.value = [...records.values()].sort((a, b) => BigInt(a.seq) < BigInt(b.seq) ? -1 : 1);
  void nextTick(() => {
    if (!element || closed || id !== selection.value) return;
    if (preserveScroll) element.scrollTop = Math.max(0, element.scrollHeight - distance);
    else if (atBottom) element.scrollTop = element.scrollHeight;
    requestAnimationFrame(markRead);
  });
}

async function syncMessages(id: string) {
  if (!loaded) {
    const page = await api<MessagePage>(`/conversations/${id}/messages`);
    if (id !== selection.value || closed) return;
    merge(page.items);
    loaded = true;
    hasOlder.value = page.hasMore;
    return;
  }
  let after = visibleMessages.value.at(-1)?.seq ?? '0';
  let more;
  do {
    const page = await api<MessagePage>(`/conversations/${id}/messages?afterSeq=${after}`);
    if (id !== selection.value || closed) return;
    after = page.items.at(-1)?.seq ?? after;
    more = page.hasMore;
    if (page.items.length) merge(page.items);
  } while (more);
}

async function sync() {
  if (closed) return;
  queuedClock.value = Date.now();
  dirty = true;
  if (syncing) return;
  syncing = true;
  try {
    while (dirty && !closed) {
      dirty = false;
      const selectedView = view.value;
      const [page, counts] = await Promise.all([
        api<ConversationPage>(`/conversations?view=${selectedView}`), api<Counts>('/summary'),
      ]);
      if (view.value !== selectedView || closed) continue;
      rows.value = page.items;
      cursor.value = page.nextCursor;
      summary.value = customer ? (counts.unread ? `${counts.unread} 条未读回复` : '')
        : `待领取 ${counts.pending} · 我的未读 ${counts.unread}`;
      const active = rows.value[0];
      if (customer && view.value === 'CURRENT' && active && selection.value !== active.id && detail.value?.status !== 'CLOSED') {
        select(active.id, true);
      }
      const id = selection.value;
      if (id) {
        try {
          const result = await api<Detail>(`/conversations/${id}`);
          if (selection.value !== id || closed) continue;
          detail.value = result;
          await syncMessages(id);
        } catch (error) {
          if (!(error instanceof SessionError) || error.status !== 404) throw error;
          if (selection.value === id) select(null);
        }
      }
      requestAnimationFrame(markRead);
    }
  } catch (error) { showError(error); }
  finally { syncing = false; }
}

async function markRead() {
  const element = log.value, selected = selection.value, current = detail.value;
  if (closed || document.hidden || !element || !selected || !current || (!customer && !current.canReply) || reading) return;
  const bounds = element.getBoundingClientRect();
  const visible = [...element.querySelectorAll<HTMLLIElement>('li[data-seq]')].filter((line) => {
    const r = line.getBoundingClientRect();
    return r.top < bounds.bottom && r.bottom > bounds.top && r.top < window.innerHeight && r.bottom > 0;
  }).at(-1)?.dataset.seq;
  if (!visible || BigInt(visible) <= BigInt(current.readSeq)) return;
  const version = current.stateVersion;
  const call = api(`/conversations/${selected}/read-position`, {
    method: 'PUT', body: JSON.stringify({ upToSeq: visible, stateVersion: version }),
  });
  reading = call;
  try {
    await call;
    const latest = detail.value;
    if (latest?.id === selected && latest.stateVersion === version && BigInt(visible) > BigInt(latest.readSeq)) {
      latest.readSeq = visible;
    }
    void sync();
  } catch (error) { if (error instanceof SessionError && error.status === 409) void sync(); }
  finally { if (reading === call) reading = null; }
}

async function perform(operation: Operation, row: ConversationRef, targetStaffId?: string) {
  if (operation === 'close' && !window.confirm('结束这段会话？')) return;
  if (operation === 'return' && !window.confirm('将这段会话退回本部门队列？')) return;
  if (operation === 'close') await markRead();
  const key = [operation, row.id, row.stateVersion, row.lastCustomerSeq, targetStaffId].join(':');
  const actionId = actionKeys.get(key) ?? crypto.randomUUID();
  actionKeys.set(key, actionId);
  try {
    await api(`/conversations/${row.id}/${operation}`, {
      method: 'POST',
      body: JSON.stringify({ actionId, stateVersion: row.stateVersion, targetStaffId,
        ...(operation === 'close' ? { observedCustomerSeq: row.lastCustomerSeq } : {}) }),
    });
    if (closed) return;
    actionKeys.delete(key);
    notice.value = '';
    if (operation === 'claim') { changeView('MINE'); select(row.id); }
    if (operation === 'return') select(null);
    if (operation === 'close') { changeView('CLOSED'); select(row.id); }
    if (operation === 'transfer') notice.value = '会话已转派。';
    return true;
  } catch (error) {
    if (error instanceof SessionError && error.status < 500) actionKeys.delete(key);
    showError(error);
    return error instanceof SessionError && error.status < 500;
  } finally {
    void sync();
  }
}

const staffLabel = (staff: StaffOption) => `${staff.displayName}（${staff.email}）`;

async function openTransfer(row: ConversationRef) {
  const current: TransferDraft = { row };
  transfer.value = current;
  transferTarget.value = '';
  notice.value = '';
  try {
    const candidates = await api<StaffOption[]>(`/conversations/${row.id}/transfer-candidates`);
    if (transfer.value === current && !closed) transfer.value = { row, candidates };
  } catch (error) {
    if (transfer.value !== current) return;
    transfer.value = undefined;
    showError(error);
  }
}

async function submitTransfer() {
  const current = transfer.value;
  if (!current?.candidates?.some(({ id }) => id === transferTarget.value) || transferring.value) return;
  transferring.value = true;
  try {
    const finished = await perform('transfer', current.row, transferTarget.value);
    if (finished && transfer.value === current) transfer.value = undefined;
  } finally {
    transferring.value = false;
  }
}

function retrySocket() {
  if (closed) return;
  ready = false;
  connection.value = '正在重连，未确认的消息可以重试。';
  for (const request of pending.values()) request.reject(new SessionError(0, 'SEND_RESULT_UNKNOWN'));
  void sync();
  retryTimer = window.setTimeout(openSocket, reconnectDelay);
  reconnectDelay = Math.min(reconnectDelay * 2, 8_000);
}

async function openSocket() {
  if (closed) return;
  let ticket: string | undefined;
  try {
    const grant = await api<Ticket>('/ws-ticket', { method: 'POST' });
    if (closed) return;
    if (grant.sessionId !== sid) { invalidate(); return; }
    ticket = grant.ticket;
  } catch {
    retrySocket();
    return;
  }
  const scheme = location.protocol === 'https:' ? 'wss' : 'ws';
  const source = new WebSocket(`${scheme}://${location.host}/ws/${side}`);
  socket = source;
  source.addEventListener('open', () => {
    if (!closed && source === socket) source.send(JSON.stringify({ type: 'auth', ticket }));
    ticket = undefined;
  });
  source.addEventListener('message', (event) => {
    if (closed || source !== socket) return;
    const frame: Frame = JSON.parse(event.data);
    if (frame.type === 'ready') {
      if (frame.data !== identity) { invalidate(); return; }
      ready = true;
      reconnectDelay = 500;
      connection.value = '';
      void sync();
    } else if (frame.type === 'ping') {
      source.send(JSON.stringify({ type: 'pong', nonce: frame.data }));
      if (!customer && selection.value) void sync();
    } else if (frame.type === 'staff.notification') {
      void staffNotification(identity, frame.data, (id) => void openNotification(id)).catch(() => {});
    } else if (frame.type === 'sync.required') {
      void sync();
    } else if (frame.type === 'message.created') {
      const last = visibleMessages.value.at(-1)?.seq ?? '0';
      if (loaded && selection.value === frame.data.conversationId && BigInt(frame.data.seq) === BigInt(last) + 1n) {
        merge([frame.data]);
      }
      void sync();
    } else if (frame.type === 'message.ack') {
      pending.get(frame.requestId)?.resolve(frame.data);
    } else if (frame.type === 'error' && frame.requestId) {
      pending.get(frame.requestId)?.reject(new SessionError(frame.data.status, frame.data.code));
    }
  });
  source.addEventListener('close', (event) => {
    if (closed || source !== socket) return;
    if (!customer && [4003, 4004].includes(event.code)) { invalidate(); return; }
    retrySocket();
  });
}

function send(payload: object): Promise<Ack> {
  const connection = socket;
  if (!ready || connection?.readyState !== WebSocket.OPEN) return Promise.reject(new SessionError(0, 'SEND_RESULT_UNKNOWN'));
  const requestId = crypto.randomUUID();
  return new Promise((resolve, reject) => {
    const unknown = new SessionError(0, 'SEND_RESULT_UNKNOWN');
    const finish = () => { window.clearTimeout(timer); pending.delete(requestId); };
    const onError = (error: SessionError) => { finish(); reject(error); };
    const timer = window.setTimeout(() => onError(unknown), 8_000);
    pending.set(requestId, { resolve: (data) => { finish(); resolve(data); }, reject: onError });
    try { connection.send(JSON.stringify({ ...payload, requestId })); }
    catch { onError(unknown); }
  });
}

async function submitMessage() {
  const text = draft.value.trim();
  if (!text || sending.value) return;
  const id = selection.value;
  if (!sendAttempt || sendAttempt.text !== text || (sendAttempt.id !== id && sendAttempt.id !== null)) {
    sendAttempt = { id, text, clientMessageId: crypto.randomUUID() };
  }
  const attempt = sendAttempt;
  sending.value = true;
  try {
    const result = await send({
      type: attempt.id ? 'message.send' : 'conversation.start', clientMessageId: attempt.clientMessageId, text,
      ...(attempt.id ? { conversationId: attempt.id } : {}), ...(!customer ? { stateVersion: detail.value?.stateVersion } : {}),
    });
    if (!closed && (selection.value === id || selection.value === result.message.conversationId)) {
      if (!selection.value) select(result.message.conversationId, true);
      draft.value = '';
    }
    if (sendAttempt === attempt) sendAttempt = null;
    if (!closed) notice.value = '';
  } catch (error) {
    if (error instanceof SessionError && error.status && error.status < 500 && sendAttempt === attempt) sendAttempt = null;
    showError(error);
  } finally { sending.value = false; }
  void sync();
}

async function loadMoreConversations() {
  const previous = cursor.value, current = view.value;
  if (!previous) return;
  try {
    const page = await api<ConversationPage>(`/conversations?view=${current}&cursor=${encodeURIComponent(previous)}`);
    if (closed || view.value !== current || cursor.value !== previous) return;
    const known = new Set(rows.value.map((row) => row.id));
    rows.value = rows.value.concat(page.items.filter((row) => !known.has(row.id)));
    cursor.value = page.nextCursor;
  } catch (error) { showError(error); }
}

async function loadOlderMessages() {
  const id = selection.value, before = visibleMessages.value[0]?.seq;
  if (!id || !before) return;
  try {
    const page = await api<MessagePage>(`/conversations/${id}/messages?beforeSeq=${before}`);
    if (closed || id !== selection.value) return;
    merge(page.items, true);
    hasOlder.value = page.hasMore;
  } catch (error) { showError(error); }
}

async function loadHistory() {
  const id = selection.value;
  if (!history.value?.open || !id) return;
  try {
    const entries = await api<ServerHistory[]>(`/conversations/${id}/actions`);
    if (!closed && id === selection.value && history.value?.open) historyRows.value = entries;
  } catch (error) { showError(error); }
}

function historyText(entry: ServerHistory) {
  const change = entry.newAssignee ? staffLabel(entry.newAssignee) : '';
  const assignment = entry.previousAssignee && change ? `${staffLabel(entry.previousAssignee)} → ${change}` : change;
  return [historyLabels[entry.action], entry.operator, assignment, new Date(entry.occurredAt).toLocaleString(), entry.reason]
    .filter(Boolean).join(' · ');
}

watch(() => detail.value?.stateVersion, () => {
  if (!detail.value?.canTransfer) transfer.value = undefined;
  void loadHistory();
});

function onVisible() {
  if (!document.hidden) {
    void sync();
    void readControls();
  }
}

function invalidate() {
  if (closed) return;
  dispose();
  emit('expired');
}

function dispose() {
  if (closed) return;
  closed = true;
  abort.abort();
  window.clearTimeout(retryTimer);
  window.clearInterval(interval);
  socket?.close();
  document.removeEventListener('visibilitychange', onVisible);
  for (const request of pending.values()) request.reject(new SessionError(401, 'SESSION_REQUIRED'));
}

onMounted(() => {
  document.addEventListener('visibilitychange', onVisible);
  interval = window.setInterval(onVisible, 30_000);
  void openSocket();
  void sync();
  void readControls();
});
onUnmounted(dispose);
</script>

<template>
  <section id="chat" :aria-label="customer ? '在线对话' : '客服工作台'">
    <nav class="chat-tabs" aria-label="会话列表">
      <button v-for="[value, title] in views" :key="value" type="button" :aria-pressed="view === value" @click="changeView(value)">
        {{ title }}
      </button>
    </nav>
    <p class="chat-summary">{{ summary }}</p>
    <p class="chat-connection" aria-live="polite">{{ connection }}</p>
    <p class="chat-notice" aria-live="polite">{{ notice }}</p>
    <p v-if="customer && serviceHours && !serviceHours.open" class="chat-hours" aria-live="polite">
      当前为非营业时间，可以继续留言。
      <template v-if="serviceHours.nextOpen">
        下次接待 {{ new Date(serviceHours.nextOpen).toLocaleString('zh-CN', { timeZone: serviceHours.zone }) }}（{{ serviceHours.zone }}）
      </template>
    </p>
    <div v-if="!customer" class="chat-actions">
      <button v-if="canQueue && accepting !== undefined" type="button" :disabled="updatingAvailability"
        :aria-pressed="accepting" @click="toggleAvailability">{{ accepting ? '暂停接单' : '恢复接单' }}</button>
      <span v-if="canQueue && accepting !== undefined">{{ accepting ? '接单中' : '已暂停接单' }}</span>
      <button v-if="canQueue" type="button" @click="toggleNotifications">{{ notifications ? '关闭客服通知' : '开启客服通知' }}</button>
      <button v-if="canSupervise" type="button" :aria-expanded="showTeam" @click="showTeam = !showTeam; readControls()">客服接单状态</button>
    </div>
    <ul v-if="showTeam" aria-label="部门客服接单状态">
      <li v-for="agent in team" :key="`${agent.department}:${agent.id}`">
        {{ agent.department }} · {{ agent.name }}（{{ agent.email }}）· {{ agent.online ? '在线' : '离线' }} ·
        {{ agent.accepting ? '接单中' : '暂停接单' }} · 进行中 {{ agent.active }}
      </li>
    </ul>
    <div class="chat-layout">
      <div>
        <ul class="chat-list" aria-label="会话">
          <li v-for="row in rows" :key="row.id">
            <template v-if="!customer && view === 'PENDING'">
              <p>{{ row.preview || labels[row.status] }}</p>
              <time :datetime="row.timestamp">排队时间 {{ new Date(row.timestamp).toLocaleString() }}</time>
              <p :class="{ 'queue-overdue': waitMinutes(row.timestamp) >= 10 }">
                {{ waitMinutes(row.timestamp) >= 10 ? '等待已超时 · ' : '' }}已等待 {{ waitMinutes(row.timestamp) }} 分钟
              </p>
              <button type="button" @click="perform('claim', row)">领取</button>
            </template>
            <button v-else type="button" :aria-pressed="selection === row.id" @click="openConversation(row.id)">
              {{ row.preview || labels[row.status] }}{{ row.unread ? `（${row.unread} 条未读）` : '' }}
            </button>
            <p v-if="view === 'DEPARTMENT' && row.status === 'WAITING'" :class="{ 'queue-overdue': waitMinutes(row.queuedAt) >= 10 }">
              {{ waitMinutes(row.queuedAt) >= 10 ? '等待已超时 · ' : '' }}已等待 {{ waitMinutes(row.queuedAt) }} 分钟
            </p>
          </li>
        </ul>
        <button v-if="cursor" type="button" class="chat-next" @click="loadMoreConversations">更多会话</button>
      </div>
      <div>
        <h2 class="chat-title">{{ detail ? labels[detail.status] : customer ? '在线对话' : '对话' }}</h2>
        <p class="chat-meta">{{ meta }}</p>
        <p class="chat-assignee">{{ detail?.assignee ? `负责人 ${detail.assignee}` : '' }}</p>
          <p v-if="detail?.assigneeOnline === false" class="queue-overdue">负责人已离线</p>
        <div class="chat-actions">
          <button v-if="detail?.canReturn" type="button" @click="perform('return', detail)">退回队列</button>
          <button v-if="detail?.canTransfer && !transfer" type="button" @click="openTransfer(detail)">转派会话</button>
          <button v-if="detail?.canClose" type="button" @click="perform('close', detail)">结束会话</button>
          <button v-if="customer && detail?.status === 'CLOSED'" type="button" @click="changeView('CURRENT', true)">发起新会话</button>
        </div>
        <form v-if="transfer && detail?.canTransfer" class="chat-transfer" @submit.prevent="submitTransfer">
          <p v-if="!transfer.candidates" aria-live="polite">正在加载同部门客服…</p>
          <p v-else-if="!transfer.candidates.length">暂无可接手的同部门客服。</p>
          <label v-else>接手客服
            <select v-model="transferTarget" required :disabled="transferring">
              <option value="" disabled>选择同部门客服</option>
              <option v-for="staff in transfer.candidates" :key="staff.id" :value="staff.id">{{ staffLabel(staff) }}</option>
            </select>
          </label>
          <div class="chat-actions">
            <button v-if="transfer.candidates?.length" type="submit" :disabled="!transferTarget || transferring">确认转派</button>
            <button type="button" :disabled="transferring" @click="transfer = undefined">取消</button>
          </div>
        </form>
        <button v-if="hasOlder" type="button" class="chat-older" @click="loadOlderMessages">加载早前消息</button>
        <ol ref="log" class="chat-log" aria-label="消息" tabindex="0" @scroll="markRead">
          <li v-for="message in visibleMessages" :key="message.seq" :data-seq="message.seq">
            <strong>{{ message.sender === 'CUSTOMER' ? '客户' : '客服' }}</strong>
            <AttachmentMessage v-if="message.attachment" :name="message.attachment.name" :type="message.attachment.type"
              :get-file="() => attachmentBlob(message)" />
            <p v-else>{{ message.text }}</p>
          </li>
        </ol>
        <form v-if="canReply" class="chat-form" @submit.prevent="submitMessage">
          <label>消息<textarea v-model="draft" name="text" maxlength="4000" required :disabled="sending"></textarea></label>
          <button type="submit" :disabled="sending">发送</button>
          <button v-if="!customer" type="button" :aria-expanded="showReplies" @click="toggleReplies">快捷回复</button>
          <div v-if="showReplies">
            <label>搜索快捷回复<input v-model="query" type="search"></label>
            <button v-for="reply in matches" :key="reply.id" type="button"
              @click="draft = (draft ? draft + '\n' + reply.body : reply.body).slice(0, 4000); showReplies = false">{{ reply.title }}</button>
            <p v-if="!matches.length">暂无匹配的快捷回复。</p>
          </div>
          <label>选择附件<input ref="fileInput" type="file" accept="image/png,image/jpeg,application/pdf" :disabled="sending" @change="pickFile"></label>
          <button v-if="attachment" type="button" :disabled="sending" @click="uploadFile">发送附件</button>
        </form>
        <details v-if="!customer && detail" ref="history" class="chat-history" @toggle="loadHistory">
          <summary>操作记录</summary>
          <ol>
            <li v-for="(entry, index) in historyRows" :key="index">
              {{ historyText(entry) }}
            </li>
          </ol>
        </details>
      </div>
    </div>
  </section>
</template>
