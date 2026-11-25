<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue';
import { responseError } from './api';
import { webSession } from './web-session';

type SummaryStats = {
  total: number; closed: number; avgFrtSeconds: number | null; avgArtSeconds: number | null;
  ratingCount: number; avgScore: number | null; positiveCount: number;
};
type AgentStats = {
  staffId: string; name: string; email: string; total: number; closed: number;
  avgFrtSeconds: number | null; avgScore: number | null;
};
type Overview = { days: number; summary: SummaryStats; agents: AgentStats[] };

const { sid } = defineProps<{ sid: string }>();
const emit = defineEmits<{ expired: [] }>();
const auth = webSession('staff');
const abort = new AbortController();
const days = ref(7);
const loading = ref(false);
const notice = ref('');
const data = ref<Overview | null>(null);

function formatDuration(seconds: number | null): string {
  if (seconds === null) return '—';
  if (seconds < 60) return `${seconds} 秒`;
  const mins = Math.floor(seconds / 60);
  if (mins < 60) return `${mins} 分 ${seconds % 60} 秒`;
  return `${Math.floor(mins / 60)} 小时 ${mins % 60} 分`;
}

async function load() {
  loading.value = true;
  notice.value = '';
  try {
    const response = await auth.request(`/api/v1/staff/analytics/overview?days=${days.value}`, { signal: abort.signal }, sid);
    if (!response.ok) {
      const error = await responseError(response);
      if (error.status === 401) emit('expired');
      throw error;
    }
    data.value = await response.json();
  } catch {
    if (!abort.signal.aborted) notice.value = '暂时无法加载统计数据，请稍后重试。';
  } finally {
    loading.value = false;
  }
}

function selectDays(next: number) {
  days.value = next;
  void load();
}

onMounted(() => void load());
onUnmounted(() => abort.abort());
</script>

<template>
  <section class="analytics-dashboard" aria-label="客服运营看板">
    <div class="inline-actions" style="margin-bottom: 14px;">
      <el-button v-for="span in [7, 30, 90]" :key="span" size="small" :type="days === span ? 'primary' : 'default'"
        :disabled="loading" @click="selectDays(span)">近 {{ span }} 天</el-button>
    </div>
    <p v-if="notice" class="feedback" aria-live="polite">{{ notice }}</p>
    <template v-if="data">
      <div class="kpi-grid">
        <article class="surface kpi-card">
          <small>会话总量</small>
          <strong>{{ data.summary.total }}</strong>
          <span>已结束 {{ data.summary.closed }}</span>
        </article>
        <article class="surface kpi-card">
          <small>平均首次响应（FRT）</small>
          <strong>{{ formatDuration(data.summary.avgFrtSeconds) }}</strong>
          <span>入队至首条客服回复</span>
        </article>
        <article class="surface kpi-card">
          <small>平均解决时长（ART）</small>
          <strong>{{ formatDuration(data.summary.avgArtSeconds) }}</strong>
          <span>入队至会话结束</span>
        </article>
        <article class="surface kpi-card">
          <small>客户满意度（CSAT）</small>
          <strong>{{ data.summary.avgScore !== null ? `${data.summary.avgScore} / 5` : '暂无评分' }}</strong>
          <span>{{ data.summary.ratingCount ? `${data.summary.ratingCount} 份评价 · 好评率 ${Math.round((data.summary.positiveCount / data.summary.ratingCount) * 100)}%` : '会话结束后收集' }}</span>
        </article>
      </div>
      <section class="surface settings-card" style="margin-top: 16px;">
        <header class="section-head"><div><h2>客服接待排行</h2><p>按统计周期内负责会话量排序。</p></div></header>
        <el-table :data="data.agents" class="data-table" empty-text="当前周期暂无客服接待记录">
          <el-table-column prop="name" label="客服" min-width="120" />
          <el-table-column prop="email" label="邮箱" min-width="180" />
          <el-table-column prop="total" label="接待会话" width="100" align="right" />
          <el-table-column prop="closed" label="已结束" width="90" align="right" />
          <el-table-column label="平均首次响应" width="140" align="right">
            <template #default="{ row }">{{ formatDuration((row as AgentStats).avgFrtSeconds) }}</template>
          </el-table-column>
          <el-table-column label="满意度均分" width="110" align="right">
            <template #default="{ row }">{{ (row as AgentStats).avgScore ?? '—' }}</template>
          </el-table-column>
        </el-table>
      </section>
    </template>
  </section>
</template>
