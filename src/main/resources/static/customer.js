import { mountChat } from './chat.js';
import { webSession } from './web-session.js';

const feedback = document.querySelector('#feedback');
const profile = document.querySelector('#profile');
const logout = document.querySelector('#logout');
const endpoint = '/api/v1/customer';
const auth = webSession('customer');
let profileRevision = 0;
let chatIdentity;
let stopChat;

function clearChat() {
  stopChat?.();
  stopChat = undefined;
  chatIdentity = undefined;
}

async function refreshProfile() {
  const revision = ++profileRevision;
  try {
    const response = await auth.request(`${endpoint}/me`, { signal: AbortSignal.timeout(6_000) });
    if (revision !== profileRevision) return;
    if (response.status === 401) {
      clearChat();
      profile.hidden = true;
      feedback.textContent = '请从 Nexus 控制台重新进入在线客服。';
      return;
    }
    if (!response.ok) throw new Error('profile unavailable');
    const user = await response.json();
    if (revision !== profileRevision) return;
    document.querySelector('#tenant').textContent = user.tenantName;
    profile.hidden = false;
    feedback.textContent = '已通过 Nexus 登录';
    const sid = auth.currentId();
    if (chatIdentity !== sid) {
      clearChat();
      chatIdentity = sid;
      stopChat = mountChat(document.querySelector('#chat'), {
        side: 'customer', identity: user.customerId, auth, sid, expired: () => { clearChat(); refreshProfile(); },
      });
    }
  } catch {
    if (revision !== profileRevision) return;
    profile.hidden = true;
    feedback.textContent = '暂时无法验证 Nexus 登录，请稍后刷新。';
  }
}

logout.addEventListener('click', async () => {
  logout.disabled = true;
  profileRevision++;
  try {
    await auth.logout();
    clearChat();
    await refreshProfile();
  } catch {
    feedback.textContent = '退出未确认，请重试。';
  } finally {
    logout.disabled = false;
  }
});

refreshProfile();
auth.onChange(() => { clearChat(); refreshProfile(); });
const refreshWhenVisible = () => { if (!document.hidden && !logout.disabled) refreshProfile(); };
document.addEventListener('visibilitychange', refreshWhenVisible);
setInterval(refreshWhenVisible, 30_000);
