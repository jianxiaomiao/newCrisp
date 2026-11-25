const feedback = document.querySelector('#feedback');
const profile = document.querySelector('#profile');
const logout = document.querySelector('#logout');
const endpoint = '/api/v1/customer';
let csrf;
let profileRevision = 0;

async function refreshProfile() {
  const revision = ++profileRevision;
  try {
    const response = await fetch(`${endpoint}/me`, { cache: 'no-store', signal: AbortSignal.timeout(6_000) });
    if (revision !== profileRevision) return;
    if (response.status === 401) {
      csrf = undefined;
      profile.hidden = true;
      feedback.textContent = '请从 Nexus 控制台重新进入在线客服。';
      return;
    }
    if (!response.ok) throw new Error('profile unavailable');
    const user = await response.json();
    if (revision !== profileRevision) return;
    csrf = response.headers.get('X-NewCrisp-CSRF');
    document.querySelector('#tenant').textContent = user.tenantName;
    profile.hidden = false;
    feedback.textContent = '已通过 Nexus 登录';
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
    const response = await fetch(`${endpoint}/session`, { method: 'DELETE', headers: { 'X-NewCrisp-CSRF': csrf } });
    if (!response.ok) throw new Error('logout unavailable');
    await refreshProfile();
  } catch {
    feedback.textContent = '退出未确认，请重试。';
  } finally {
    logout.disabled = false;
  }
});

refreshProfile();
const refreshWhenVisible = () => { if (!document.hidden && !logout.disabled) refreshProfile(); };
document.addEventListener('visibilitychange', refreshWhenVisible);
setInterval(refreshWhenVisible, 30_000);
