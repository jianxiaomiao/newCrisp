const labels = { WAITING: '等待客服', ACTIVE: '客服已接入', CLOSED: '已结束' };
const messages = {
  STATE_VERSION_CHANGED: '会话已变化，已刷新最新状态。',
  ALREADY_CLAIMED: '已被其他客服领取。',
  NEW_CUSTOMER_MESSAGES: '客户有新留言，请阅读后重新确认结束。',
  CONVERSATION_CLOSED: '此会话已结束。可保留文字，手动发起新会话。',
  NOT_FOUND: '当前无法访问此会话。',
  SESSION_REQUIRED: '登录已失效，请重新进入。',
  SEND_RESULT_UNKNOWN: '发送结果待确认，重试会沿用本次发送。',
};

export function mountChat(root, { side, identity, auth, sid, canQueue = false, canSupervise = false, expired }) {
  const customer = side === 'customer';
  const views = customer ? [['CURRENT', '当前会话'], ['HISTORY', '历史会话']] : [
    ...(canQueue ? [['PENDING', '待领取'], ['MINE', '我负责'], ['CLOSED', '已结束']] : []),
    ...(canSupervise ? [['DEPARTMENT', '部门会话']] : []),
  ];
  if (!views.length) return () => {};
  root.innerHTML = `
    <nav class="chat-tabs" aria-label="会话列表"></nav>
    <p class="chat-summary"></p><p class="chat-connection" aria-live="polite">正在连接聊天…</p>
    <p class="chat-notice" aria-live="polite"></p>
    <div class="chat-layout">
      <div><ul class="chat-list" aria-label="会话"></ul><button type="button" class="chat-next" hidden>更多会话</button></div>
      <div><h2 class="chat-title">对话</h2><p class="chat-meta"></p><p class="chat-assignee"></p>
      <div class="chat-actions"></div>
      <button type="button" class="chat-older" hidden>加载早前消息</button>
      <ol class="chat-log" aria-label="消息" tabindex="0"></ol>
      <form class="chat-form" hidden><label>消息<textarea name="text" maxlength="4000" required></textarea></label>
      <button type="submit">发送</button></form>
      <details class="chat-history" hidden><summary>操作记录</summary><ol></ol></details></div>
    </div>`;
  root.hidden = false;
  const find = (selector) => root.querySelector(selector);
  const tabs = find('.chat-tabs'), list = find('.chat-list'), log = find('.chat-log');
  const form = find('.chat-form'), input = form.elements.text, submit = form.querySelector('button');
  const notice = find('.chat-notice'), connection = find('.chat-connection');
  const meta = find('.chat-meta'), actions = find('.chat-actions'), summary = find('.chat-summary');
  const history = find('.chat-history');
  const older = find('.chat-older'), next = find('.chat-next');
  const base = `/api/v1/${side}`;
  let view = views[0][0], selection = null, detail = null, records = new Map(), loaded = false, hasOlder = false;
  let rows = [], cursor = null, actionKeys = new Map(), sendAttempt = null, reading = null;
  let socket, ready = false, closed = false, retryTimer, reconnectDelay = 500;
  let dirty = false, syncing = false;
  const pending = new Map();
  const abort = new AbortController();

  function button(text, run) {
    const item = document.createElement('button');
    item.type = 'button';
    item.textContent = text;
    item.addEventListener('click', run);
    return item;
  }

  async function api(path, options) {
    const response = await auth.request(base + path, {
      cache: 'no-store', signal: abort.signal, ...options,
      headers: { 'X-NewCrisp-Actor': identity,
        ...(options?.body ? { 'Content-Type': 'application/json' } : {}) },
    }, sid);
    if (response.ok) return response.status === 204 ? null : response.json();
    if (response.status === 401 && !closed) {
      dispose();
      expired();
    }
    const error = new Error('Request failed');
    error.status = response.status;
    error.code = (await response.json().catch(() => null))?.code;
    throw error;
  }

  function showError(error) {
    if (!closed) notice.textContent = messages[error?.code] ?? '暂时无法完成操作，请稍后重试。';
  }

  function select(id, preserveDraft = false) {
    if (selection === id) return;
    selection = id;
    detail = null;
    records = new Map();
    loaded = false;
    hasOlder = false;
    reading = null;
    history.open = false;
    history.querySelector('ol').replaceChildren();
    if (!preserveDraft) { input.value = ''; sendAttempt = null; }
    renderMessages();
    renderDetail();
  }

  function changeView(nextView, preserveDraft = false) {
    view = nextView;
    select(null, preserveDraft);
    rows = [];
    cursor = null;
    renderList();
    for (const item of tabs.children) item.setAttribute('aria-pressed', String(item.dataset.view === view));
    sync();
  }

  for (const [value, title] of views) {
    const item = button(title, () => changeView(value));
    item.dataset.view = value;
    item.setAttribute('aria-pressed', String(view === value));
    tabs.append(item);
  }

  function renderList() {
    list.replaceChildren();
    next.hidden = !cursor;
    for (const row of rows) {
      const item = document.createElement('li');
      const preview = row.preview || labels[row.status];
      if (!customer && view === 'PENDING') {
        const paragraph = document.createElement('p');
        paragraph.textContent = preview;
        const time = document.createElement('time');
        time.dateTime = row.timestamp;
        time.textContent = `排队时间 ${new Date(row.timestamp).toLocaleString()}`;
        item.append(paragraph, time, button('领取', () => perform('claim', row)));
      } else {
        const open = button(`${preview}${row.unread ? `（${row.unread} 条未读）` : ''}`, () => {
          select(row.id);
          sync();
        });
        open.setAttribute('aria-pressed', String(selection === row.id));
        item.append(open);
      }
      list.append(item);
    }
  }

  function renderDetail() {
    find('.chat-title').textContent = detail ? labels[detail.status] : customer ? '在线对话' : '对话';
    meta.textContent = detail?.status === 'WAITING' && customer ? '可继续留言，稍后回来查看回复。'
      : detail?.customerOnline === true ? '客户在线' : detail?.customerOnline === false ? '客户离线' : '';
    find('.chat-assignee').textContent = detail?.assignee ? `负责人 ${detail.assignee}` : '';
    actions.replaceChildren();
    if (detail?.canReturn) actions.append(button('退回队列', () => perform('return', detail)));
    if (detail?.canClose) actions.append(button('结束会话', () => perform('close', detail)));
    if (customer && detail?.status === 'CLOSED') actions.append(button('发起新会话', () => changeView('CURRENT', true)));
    form.hidden = !(detail?.canReply || (customer && view === 'CURRENT' && !selection));
    older.hidden = !hasOlder;
    history.hidden = customer || !detail;
  }

  function ordered() {
    return [...records.values()].sort((a, b) => BigInt(a.seq) < BigInt(b.seq) ? -1 : 1);
  }

  function renderMessages(preserveScroll = false) {
    const distance = log.scrollHeight - log.scrollTop;
    const atBottom = log.scrollHeight - log.scrollTop - log.clientHeight < 25;
    log.replaceChildren();
    for (const message of ordered()) {
      const line = document.createElement('li');
      line.dataset.seq = message.seq;
      const author = document.createElement('strong');
      author.textContent = message.sender === 'CUSTOMER' ? '客户' : '客服';
      const text = document.createElement('p');
      text.textContent = message.text;
      line.append(author, text);
      log.append(line);
    }
    if (preserveScroll) log.scrollTop = Math.max(0, log.scrollHeight - distance);
    else if (atBottom) log.scrollTop = log.scrollHeight;
    requestAnimationFrame(markRead);
  }

  function merge(collection) {
    for (const message of collection) records.set(message.seq, message);
  }

  async function syncMessages(id) {
    if (!loaded) {
      const page = await api(`/conversations/${id}/messages`);
      if (id !== selection) return;
      merge(page.items);
      loaded = true;
      hasOlder = page.hasMore;
      renderMessages();
      return;
    }
    let after = ordered().at(-1)?.seq ?? '0';
    let more;
    do {
      const page = await api(`/conversations/${id}/messages?afterSeq=${after}`);
      if (id !== selection) return;
      merge(page.items);
      after = page.items.at(-1)?.seq ?? after;
      more = page.hasMore;
      if (page.items.length) renderMessages();
    } while (more);
  }

  async function sync() {
    if (closed) return;
    dirty = true;
    if (syncing) return;
    syncing = true;
    try {
      while (dirty && !closed) {
        dirty = false;
        const selectedView = view;
        const [page, counts] = await Promise.all([api(`/conversations?view=${selectedView}`), api('/summary')]);
        if (view !== selectedView || closed) continue;
        rows = page.items;
        cursor = page.nextCursor;
        summary.textContent = customer ? (counts.unread ? `${counts.unread} 条未读回复` : '')
          : `待领取 ${counts.pending} · 我的未读 ${counts.unread}`;
        if (customer && view === 'CURRENT' && rows.length && selection !== rows[0].id && detail?.status !== 'CLOSED') {
          select(rows[0].id, true);
        }
        renderList();
        const id = selection;
        if (id) {
          try {
            const result = await api(`/conversations/${id}`);
            if (selection !== id || closed) continue;
            detail = result;
            await syncMessages(id);
          } catch (error) {
            if (error.status !== 404) throw error;
            if (selection === id) select(null);
          }
        }
        renderDetail();
        requestAnimationFrame(markRead);
      }
    } catch (error) { showError(error); }
    finally { syncing = false; }
  }

  async function markRead() {
    if (closed || document.hidden || !selection || !detail || (!customer && !detail.canReply) || reading) return;
    const bounds = log.getBoundingClientRect();
    const visible = [...log.children].filter((line) => {
      const r = line.getBoundingClientRect();
      return r.top < bounds.bottom && r.bottom > bounds.top && r.top < window.innerHeight && r.bottom > 0;
    }).at(-1)?.dataset.seq;
    if (!visible || BigInt(visible) <= BigInt(detail.readSeq)) return;
    const id = selection, version = detail.stateVersion;
    const call = api(`/conversations/${id}/read-position`, {
      method: 'PUT', body: JSON.stringify({ upToSeq: visible, stateVersion: version }),
    });
    reading = call;
    try {
      await call;
      if (detail?.id === id && detail.stateVersion === version && BigInt(visible) > BigInt(detail.readSeq)) detail.readSeq = visible;
      sync();
    } catch (error) { if (error.status === 409) sync(); }
    finally { if (reading === call) reading = null; }
  }

  async function perform(operation, row) {
    if (operation === 'close' && !window.confirm('结束这段会话？')) return;
    if (operation === 'return' && !window.confirm('将这段会话退回本部门队列？')) return;
    if (operation === 'close') await markRead();
    const key = [operation, row.id, row.stateVersion, row.lastCustomerSeq].join(':');
    const actionId = actionKeys.get(key) ?? crypto.randomUUID();
    actionKeys.set(key, actionId);
    try {
      await api(`/conversations/${row.id}/${operation}`, {
        method: 'POST',
        body: JSON.stringify({ actionId, stateVersion: row.stateVersion,
          ...(operation === 'close' ? { observedCustomerSeq: row.lastCustomerSeq } : {}) }),
      });
      actionKeys.delete(key);
      notice.textContent = '';
      if (operation === 'claim') { changeView('MINE'); select(row.id); }
      if (operation === 'return') select(null);
      if (operation === 'close') { changeView('CLOSED'); select(row.id); }
    } catch (error) {
      if (error.status && error.status < 500) actionKeys.delete(key);
      showError(error);
    }
    sync();
  }

  function retrySocket() {
    if (closed) return;
    ready = false;
    connection.textContent = '正在重连，未确认的消息可以重试。';
    for (const request of pending.values()) request.reject({ code: 'SEND_RESULT_UNKNOWN' });
    sync();
    retryTimer = setTimeout(openSocket, reconnectDelay);
    reconnectDelay = Math.min(reconnectDelay * 2, 8_000);
  }

  async function openSocket() {
    if (closed) return;
    let ticket;
    try {
      const grant = await api('/ws-ticket', { method: 'POST' });
      if (closed) return;
      if (grant.sessionId !== sid) { dispose(); expired(); return; }
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
      const frame = JSON.parse(event.data);
      if (frame.type === 'ready') {
        if (frame.data !== identity) { dispose(); expired(); return; }
        ready = true;
        retryTimer = undefined;
        reconnectDelay = 500;
        connection.textContent = '';
        sync();
      } else if (frame.type === 'ping') {
        source.send(JSON.stringify({ type: 'pong', nonce: frame.data }));
        if (!customer && selection) sync();
      } else if (frame.type === 'sync.required') {
        sync();
      } else if (frame.type === 'message.created') {
        const last = ordered().at(-1)?.seq ?? '0';
        if (loaded && selection === frame.data.conversationId && BigInt(frame.data.seq) === BigInt(last) + 1n) {
          merge([frame.data]);
          renderMessages();
        }
        sync();
      } else if (frame.requestId && pending.has(frame.requestId)) {
        const request = pending.get(frame.requestId);
        if (frame.type === 'error') request.reject(frame.data);
        if (frame.type === 'message.ack') request.resolve(frame.data);
      }
    });
    source.addEventListener('close', () => {
      if (closed || source !== socket) return;
      retrySocket();
    });
  }

  function send(payload) {
    if (!ready || socket?.readyState !== WebSocket.OPEN) return Promise.reject({ code: 'SEND_RESULT_UNKNOWN' });
    const requestId = crypto.randomUUID();
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => finish(reject, { code: 'SEND_RESULT_UNKNOWN' }), 8_000);
      const finish = (callback, result) => {
        clearTimeout(timer); pending.delete(requestId); callback(result);
      };
      pending.set(requestId, { resolve: (data) => finish(resolve, data), reject: (error) => finish(reject, error) });
      try { socket.send(JSON.stringify({ ...payload, requestId })); }
      catch { finish(reject, { code: 'SEND_RESULT_UNKNOWN' }); }
    });
  }

  form.addEventListener('submit', async (event) => {
    event.preventDefault();
    const text = input.value.trim();
    if (!text || submit.disabled) return;
    const id = selection;
    if (!sendAttempt || sendAttempt.text !== text || (sendAttempt.id !== id && sendAttempt.id !== null)) {
      sendAttempt = { id, text, clientMessageId: crypto.randomUUID() };
    }
    const attempt = sendAttempt;
    submit.disabled = true;
    input.disabled = true;
    try {
      const result = await send({
        type: attempt.id ? 'message.send' : 'conversation.start', clientMessageId: attempt.clientMessageId, text,
        ...(attempt.id ? { conversationId: attempt.id } : {}), ...(!customer ? { stateVersion: detail?.stateVersion } : {}),
      });
      if (!closed && (selection === id || selection === result.message.conversationId)) {
        if (!selection) select(result.message.conversationId, true);
        input.value = '';
      }
      if (sendAttempt === attempt) sendAttempt = null;
      if (!closed) notice.textContent = '';
      sync();
    } catch (error) {
      if (error?.status && error.status < 500 && sendAttempt === attempt) sendAttempt = null;
      if (!closed && !error?.status) notice.textContent = '发送结果待确认，重试会沿用本次发送。';
      else showError(error);
      sync();
    } finally { submit.disabled = false; input.disabled = false; }
  });

  next.addEventListener('click', async () => {
    const previous = cursor, current = view;
    if (!previous) return;
    try {
      const page = await api(`/conversations?view=${current}&cursor=${encodeURIComponent(previous)}`);
      if (view !== current || cursor !== previous) return;
      const known = new Set(rows.map((row) => row.id));
      rows = rows.concat(page.items.filter((row) => !known.has(row.id)));
      cursor = page.nextCursor;
      renderList();
    } catch (error) { showError(error); }
  });

  older.addEventListener('click', async () => {
    const id = selection, before = ordered()[0]?.seq;
    if (!id || !before) return;
    try {
      const page = await api(`/conversations/${id}/messages?beforeSeq=${before}`);
      if (id !== selection) return;
      merge(page.items);
      hasOlder = page.hasMore;
      renderMessages(true);
      renderDetail();
    } catch (error) { showError(error); }
  });

  history.addEventListener('toggle', async () => {
    const id = selection;
    if (!history.open || !id) return;
    try {
      const entries = await api(`/conversations/${id}/actions`);
      if (id !== selection || !history.open) return;
      const types = { CLAIM: '领取', RETURN: '退回队列', CLOSE: '结束' };
      history.querySelector('ol').replaceChildren(...entries.map((entry) => {
        const li = document.createElement('li');
        li.textContent = [types[entry.action], entry.operator, new Date(entry.occurredAt).toLocaleString(), entry.reason]
          .filter(Boolean).join(' · ');
        return li;
      }));
    } catch (error) { showError(error); }
  });

  const onVisible = () => { if (!document.hidden) sync(); };
  document.addEventListener('visibilitychange', onVisible);
  log.addEventListener('scroll', markRead);
  const interval = setInterval(onVisible, 30_000);
  function dispose() {
    if (closed) return;
    closed = true;
    abort.abort();
    clearTimeout(retryTimer);
    clearInterval(interval);
    socket?.close();
    document.removeEventListener('visibilitychange', onVisible);
    for (const request of pending.values()) request.reject({ code: 'SESSION_REQUIRED' });
    root.replaceChildren();
    root.hidden = true;
  }
  renderDetail();
  openSocket();
  sync();
  return dispose;
}
