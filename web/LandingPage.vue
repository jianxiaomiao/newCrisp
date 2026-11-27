<script setup lang="ts">
import { onMounted, onUnmounted, reactive, ref } from 'vue';
import { ChatDotRound, CircleCheck, Connection, DataAnalysis, Grid, Headset, Lock, Promotion, Setting, User } from '@element-plus/icons-vue';
import BrandMark from './BrandMark.vue';
import { responseError } from './api';

const publicOrigin = window.location.origin;
const selfSignupEnabled = ref(true);
const submitting = ref(false);
const feedback = ref('');
const feedbackType = ref<'info' | 'error' | 'success'>('info');
let demoScriptEl: HTMLScriptElement | null = null;

const form = reactive({
  name: '',
  code: '',
  adminEmail: '',
  adminPassword: '',
});

const features = [
  { icon: Grid, title: '多租户彻底隔离', desc: '按租户与部门独立隔离数据、员工权限、接待规则与会话队列，一套部署服务无数团队。' },
  { icon: ChatDotRound, title: '一行代码悬浮窗', desc: 'Shadow DOM 样式零冲突，复制一行 script 嵌入任意官网，点击气泡原地对话，支持访客接续。' },
  { icon: Connection, title: '实时已读与输入态', desc: '基于 WebSocket 的毫秒级消息投递、乐观发送、已读/未读回执、正在输入提示与断线自动补齐。' },
  { icon: Setting, title: '高效团队协作', desc: '团队专属内部备注（客户不可见）、会话标签、全库检索、/ 快捷回复与变量替换、自动分配与超时关闭。' },
  { icon: Headset, title: '客户画像与历史', desc: '自动识别访客当前浏览页面，无缝对接自有业务系统同步用户资料，一眼查看该客户全部历史会话。' },
  { icon: DataAnalysis, title: '满意度与数据看板', desc: '会话结束自动弹出 1–5 星 CSAT 评价，管理员实时掌控首响时长、解决率与客服工作量分布。' },
];

const signupErrors = new Map([
  ['TENANT_ALREADY_EXISTS', '该租户代码已被占用，请更换一个唯一的租户代码。'],
  ['RESERVED_TENANT_CODE', '该租户代码为系统保留字，请更换其他名称。'],
  ['INVALID_ADMIN_PASSWORD', '密码长度需在 12 到 72 个字符之间。'],
  ['SELF_SIGNUP_DISABLED', '当前平台已关闭自助注册，请联系平台管理员开通。'],
  ['TOO_MANY_SIGNUPS', '注册过于频繁，请稍后再试。'],
  ['INVALID_REQUEST', '请检查输入格式：租户代码需以小写字母开头，3–48 位小写字母、数字或连字符。'],
]);

async function loadPublicConfig() {
  try {
    const res = await fetch('/api/v1/public/config', { cache: 'no-store', signal: AbortSignal.timeout(6000) });
    if (!res.ok) return;
    const cfg: { selfSignupEnabled: boolean; landingDemoSiteId: string | null } = await res.json();
    selfSignupEnabled.value = cfg.selfSignupEnabled;
    if (cfg.landingDemoSiteId && !document.querySelector('script[data-newcrisp-demo]')) {
      const script = document.createElement('script');
      script.src = '/widget.js';
      script.async = true;
      script.dataset.siteId = cfg.landingDemoSiteId;
      script.dataset.newcrispDemo = '1';
      document.body.appendChild(script);
      demoScriptEl = script;
    }
  } catch { /* ignore config fetch failure */ }
}

async function registerTenant() {
  if (submitting.value) return;
  submitting.value = true;
  feedback.value = '';
  feedbackType.value = 'info';
  try {
    const res = await fetch('/api/v1/public/tenants/register', {
      method: 'POST',
      credentials: 'same-origin',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        code: form.code.trim().toLowerCase(),
        name: form.name.trim(),
        adminEmail: form.adminEmail.trim(),
        adminPassword: form.adminPassword,
      }),
    });
    if (!res.ok) {
      const err = await responseError(res);
      feedbackType.value = 'error';
      feedback.value = signupErrors.get(err.code) ?? '开通失败，请检查表单内容后重试。';
      return;
    }
    feedbackType.value = 'success';
    feedback.value = '工作空间已创建并自动登录，正在进入管理员工作台…';
    window.location.assign('/staff.html');
  } catch {
    feedbackType.value = 'error';
    feedback.value = '网络连接异常，请稍后重试。';
  } finally {
    submitting.value = false;
  }
}

onMounted(() => void loadPublicConfig());
onUnmounted(() => {
  demoScriptEl?.remove();
  document.getElementById('newcrisp-widget-root')?.remove();
});
</script>

<template>
  <div class="landing-shell">
    <header class="landing-nav">
      <div class="landing-container landing-nav-inner">
        <a href="/" class="landing-brand-link" aria-label="NewCrisp 首页"><BrandMark /></a>
        <nav class="landing-links" aria-label="主导航">
          <a href="#features">核心能力</a>
          <a href="#steps">三步接入</a>
          <a href="#signup">免费开通</a>
          <a href="/platform.html">平台管理</a>
        </nav>
        <div class="landing-nav-actions">
          <el-button tag="a" href="/staff.html" plain>登录工作台</el-button>
          <el-button tag="a" href="#signup" type="primary">免费创建工作空间</el-button>
        </div>
      </div>
    </header>

    <main>
      <section class="landing-hero">
        <div class="landing-container landing-hero-grid">
          <div class="landing-hero-copy">
            <span class="landing-pill"><el-icon><CircleCheck /></el-icon>多租户 · 轻量级 · 开箱即用</span>
            <h1>为你的网站与产品打造<br>丝滑的在线客服服务台</h1>
            <p class="landing-lead">
              对标 Crisp 的现代 Web 客服体验。只需一行代码即可为官网挂载悬浮对话窗，
              开箱拥有多租户工作台、客户画像、内部协作备注、自动分配与满意度看板。
            </p>
            <div class="inline-actions landing-hero-cta">
              <el-button tag="a" href="#signup" type="primary" size="large" :icon="Promotion">立即免费开通</el-button>
              <el-button tag="a" href="/staff.html" size="large">已有账号，登录工作台</el-button>
            </div>
            <ul class="landing-trust">
              <li><el-icon><CircleCheck /></el-icon>无需复杂配置，30 秒创建独立租户</li>
              <li><el-icon><CircleCheck /></el-icon>Shadow DOM 悬浮窗，零样式冲突</li>
              <li><el-icon><CircleCheck /></el-icon>完整 RBAC 权限与数据隔离</li>
            </ul>
          </div>

          <div class="surface landing-preview" aria-hidden="true">
            <div class="preview-topbar">
              <span class="preview-dots"><i></i><i></i><i></i></span>
              <small>NewCrisp · 实时工作台预览</small>
            </div>
            <div class="preview-body">
              <div class="preview-sidebar">
                <div class="preview-item active">
                  <strong>张小明</strong>
                  <span>已接入 · 刚刚</span>
                </div>
                <div class="preview-item">
                  <strong>官网访客 #402</strong>
                  <span>等待客服 · 1 分钟前</span>
                </div>
                <div class="preview-item">
                  <strong>企业版咨询</strong>
                  <span>已结束 · ★★★★★</span>
                </div>
              </div>
              <div class="preview-chat">
                <div class="preview-bubble incoming">你好，我想给官网接入在线客服悬浮窗，需要多久？</div>
                <div class="preview-note">内部备注：高意向企业客户，可发送自助接入文档</div>
                <div class="preview-bubble outgoing">您好！只需在网站底部贴一行 script 代码，1 分钟即可上线 ✨</div>
              </div>
            </div>
          </div>
        </div>
      </section>

      <section id="features" class="landing-section">
        <div class="landing-container">
          <div class="landing-section-head">
            <h2>聚焦核心人工客服体验，没有臃肿负担</h2>
            <p>每一项功能都围绕「访客轻松找到你、客服高效解决它、管理者看得清数据」打磨。</p>
          </div>
          <div class="landing-feature-grid">
            <article v-for="item in features" :key="item.title" class="surface landing-feature-card">
              <span class="landing-feature-icon"><el-icon><component :is="item.icon" /></el-icon></span>
              <h3>{{ item.title }}</h3>
              <p>{{ item.desc }}</p>
            </article>
          </div>
        </div>
      </section>

      <section id="steps" class="landing-section landing-section-alt">
        <div class="landing-container">
          <div class="landing-section-head">
            <h2>三步上线你的专属客服台</h2>
            <p>无需改造现有后端，纯静态站点也能直接嵌入。</p>
          </div>
          <div class="landing-steps-grid">
            <article class="surface landing-step-card">
              <span class="step-num">01</span>
              <h3>创建租户工作空间</h3>
              <p>在下方填写工作空间名称、租户代码与管理员账号，系统会自动初始化默认「客户支持」部门。</p>
            </article>
            <article class="surface landing-step-card">
              <span class="step-num">02</span>
              <h3>复制一行嵌入脚本</h3>
              <p>进入工作台「访客入口」创建站点，把生成的代码贴到你网站 HTML 的 <code>&lt;/body&gt;</code> 前：</p>
              <pre class="landing-code"><code>&lt;script src="{{ publicOrigin }}/widget.js"
  data-site-id="你的站点ID" async&gt;&lt;/script&gt;</code></pre>
            </article>
            <article class="surface landing-step-card">
              <span class="step-num">03</span>
              <h3>邀请客服开始接待</h3>
              <p>在「租户管理」添加客服成员并配置营业时间与欢迎语，访客消息即刻推送到工作台。</p>
            </article>
          </div>
        </div>
      </section>

      <section id="signup" class="landing-section">
        <div class="landing-container landing-signup-wrap">
          <div class="surface landing-signup-card">
            <div class="landing-signup-intro">
              <h2>免费创建你的工作空间</h2>
              <p>提交后将立即为你创建独立租户并自动登录进入管理员工作台，随时可以添加团队成员、生成悬浮窗代码。</p>
              <ul class="landing-signup-bullets">
                <li><el-icon><CircleCheck /></el-icon>自动创建初始管理员与默认「客户支持」部门</li>
                <li><el-icon><CircleCheck /></el-icon>支持后续随时接入外部官网或 Nexus 控制台</li>
                <li><el-icon><CircleCheck /></el-icon>租户代码将作为团队登录工作台的唯一标识</li>
              </ul>
            </div>

            <div class="landing-signup-form">
              <template v-if="selfSignupEnabled">
                <p v-if="feedback" :class="['landing-feedback', feedbackType]" role="status" aria-live="polite">{{ feedback }}</p>
                <form class="field-stack" @submit.prevent="registerTenant">
                  <label>工作空间名称
                    <el-input v-model="form.name" aria-label="工作空间名称" required maxlength="120" placeholder="例如：清禾科技客服中心" />
                  </label>
                  <label>租户代码（唯一标识，小写字母/数字/连字符）
                    <el-input v-model="form.code" aria-label="租户代码" required maxlength="48" pattern="[a-z][a-z0-9\-]{2,47}" :prefix-icon="Grid" placeholder="例如：qinghe" />
                  </label>
                  <label>管理员邮箱
                    <el-input v-model="form.adminEmail" aria-label="管理员邮箱" required type="email" maxlength="254" :prefix-icon="User" placeholder="admin@yourcompany.com" />
                  </label>
                  <label>管理员密码（12–72 位）
                    <el-input v-model="form.adminPassword" aria-label="管理员密码" required type="password" minlength="12" maxlength="72" :prefix-icon="Lock" show-password placeholder="至少 12 位安全密码" />
                  </label>
                  <el-button class="login-submit" native-type="submit" type="primary" :loading="submitting">创建工作空间并进入工作台</el-button>
                </form>
              </template>
              <div v-else class="empty-state">
                <h3>当前未开放自助注册</h3>
                <p>管理员已关闭公开自助注册入口。如需开通工作空间，请联系平台管理员或前往 <a href="/staff.html">登录工作台</a>。</p>
              </div>
            </div>
          </div>
        </div>
      </section>
    </main>

    <footer class="landing-footer">
      <div class="landing-container landing-footer-inner">
        <span>© {{ new Date().getFullYear() }} NewCrisp · 多租户在线客服服务台</span>
        <div class="inline-actions">
          <a href="/staff.html">客服工作台</a>
          <a href="/platform.html">平台管理台</a>
        </div>
      </div>
    </footer>
  </div>
</template>
