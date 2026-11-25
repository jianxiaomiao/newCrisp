type StaffNotice = { conversationId: string; eventId: string; assigned: boolean };

export const preferenceKey = (identity: string) => `newcrisp:notification:${identity}`;

export async function staffNotification(identity: string, event: StaffNotice, open: (id: string) => void) {
  if (!document.hidden || !('Notification' in window) || Notification.permission !== 'granted') return;
  const key = preferenceKey(identity);
  if (localStorage.getItem(key) !== 'on') return;
  await navigator.locks.request(key, () => {
    if (!document.hidden || localStorage.getItem(key) !== 'on') return;
    const cacheKey = `${key}:recent`;
    const cache: Record<string, { event: string; at: number }> = JSON.parse(localStorage.getItem(cacheKey) ?? '{}');
    const now = Date.now();
    const previous = cache[event.conversationId];
    if (previous && (BigInt(event.eventId) <= BigInt(previous.event) || now - previous.at < 5000)) return;
    for (const [conversation, value] of Object.entries(cache)) if (now - value.at > 600_000) delete cache[conversation];
    const notification = new Notification(event.assigned ? '有新客服会话' : '有新的客户消息', {
      body: '打开客服工作台查看',
      tag: `newcrisp:${identity}:${event.conversationId}`,
    });
    cache[event.conversationId] = { event: event.eventId, at: now };
    localStorage.setItem(cacheKey, JSON.stringify(cache));
    notification.onclick = () => {
      window.focus();
      notification.close();
      open(event.conversationId);
    };
  });
}
