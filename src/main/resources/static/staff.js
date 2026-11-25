import { mountChat } from './chat.js';
import { webSession } from './web-session.js';

const login = document.querySelector('#login');
const profile = document.querySelector('#profile');
const heading = document.querySelector('#heading');
const feedback = document.querySelector('#feedback');
const logout = document.querySelector('#logout');
const submit = login.querySelector('button');
const endpoint = '/api/v1/staff';
const auth = webSession('staff');
const member = document.querySelector('#member');
let chatIdentity;
let stopChat;

async function refreshProfile() {
  const response = await auth.request(`${endpoint}/me`);
  if (response.status === 401) {
    stopChat?.();
    stopChat = undefined;
    chatIdentity = undefined;
    member.hidden = true;
    heading.textContent = '租户管理员登录';
    login.hidden = false;
    profile.hidden = true;
    return;
  }
  if (!response.ok) throw new Error('profile unavailable');
  const user = await response.json();
  document.querySelector('#workspace').textContent = user.tenantName;
  document.querySelector('#email').textContent = user.email;
  heading.textContent = user.role === 'TENANT_ADMIN' ? '租户管理' : '客服工作台';
  login.hidden = true;
  profile.hidden = false;
  member.hidden = user.role !== 'TENANT_ADMIN';
  const sid = auth.currentId();
  if (chatIdentity !== sid) {
    stopChat?.();
    chatIdentity = sid;
    stopChat = mountChat(document.querySelector('#chat'), {
      side: 'staff', identity: user.staffId, auth, sid, canQueue: user.canQueue, canSupervise: user.canSupervise,
      expired: () => { chatIdentity = undefined; refreshProfile().catch(() => { feedback.textContent = '请重新登录。'; }); },
    });
  }
}

login.addEventListener('submit', async (event) => {
  event.preventDefault();
  submit.disabled = true;
  feedback.textContent = '';
  try {
    await auth.login(Object.fromEntries(new FormData(login)));
    login.reset();
    await refreshProfile();
  } catch (error) {
    feedback.textContent = error.status === 401 ? '租户代码、邮箱或密码有误。' : '暂时无法确认登录，请刷新页面后重试。';
  } finally {
    submit.disabled = false;
  }
});

logout.addEventListener('click', async () => {
  logout.disabled = true;
  feedback.textContent = '';
  try {
    await auth.logout();
    await refreshProfile();
  } catch {
    feedback.textContent = '退出未确认，请重试。';
  } finally {
    logout.disabled = false;
  }
});

refreshProfile().catch(() => { feedback.textContent = '暂时无法加载登录状态，请刷新页面。'; });
auth.onChange(() => {
  stopChat?.();
  chatIdentity = undefined;
  refreshProfile().catch(() => { feedback.textContent = '请重新登录。'; });
});

member.addEventListener('submit', async (event) => {
  event.preventDefault();
  const submitMember = member.querySelector('button');
  submitMember.disabled = true;
  try {
    const response = await auth.request(`${endpoint}/members`, {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(Object.fromEntries(new FormData(member))),
    }, chatIdentity);
    if (!response.ok) throw new Error('member unavailable');
    member.reset();
    feedback.textContent = '成员已创建。';
  } catch { feedback.textContent = '无法创建成员，请核对邮箱、部门和密码。'; }
  finally { submitMember.disabled = false; }
});
