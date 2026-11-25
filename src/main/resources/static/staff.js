const login = document.querySelector('#login');
const profile = document.querySelector('#profile');
const heading = document.querySelector('#heading');
const feedback = document.querySelector('#feedback');
const logout = document.querySelector('#logout');
const submit = login.querySelector('button');
const endpoint = '/api/v1/staff';
let csrf;

async function refreshProfile() {
  const response = await fetch(`${endpoint}/me`, { cache: 'no-store' });
  if (response.status === 401) {
    csrf = undefined;
    heading.textContent = '租户管理员登录';
    login.hidden = false;
    profile.hidden = true;
    return;
  }
  if (!response.ok) throw new Error('profile unavailable');
  const user = await response.json();
  csrf = response.headers.get('X-NewCrisp-CSRF');
  document.querySelector('#workspace').textContent = user.tenantName;
  document.querySelector('#email').textContent = user.email;
  heading.textContent = '租户管理';
  login.hidden = true;
  profile.hidden = false;
}

login.addEventListener('submit', async (event) => {
  event.preventDefault();
  submit.disabled = true;
  feedback.textContent = '';
  try {
    const response = await fetch(`${endpoint}/session`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(Object.fromEntries(new FormData(login))),
    });
    if (response.status === 401) {
      feedback.textContent = '租户代码、邮箱或密码有误。';
      return;
    }
    if (!response.ok) throw new Error('login unavailable');
    login.reset();
    await refreshProfile();
  } catch {
    feedback.textContent = '暂时无法确认登录，请刷新页面后重试。';
  } finally {
    submit.disabled = false;
  }
});

logout.addEventListener('click', async () => {
  logout.disabled = true;
  feedback.textContent = '';
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

refreshProfile().catch(() => { feedback.textContent = '暂时无法加载登录状态，请刷新页面。'; });
