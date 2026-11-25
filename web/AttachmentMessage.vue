<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue';
import { Document, Picture } from '@element-plus/icons-vue';

const props = defineProps<{ name: string; type: string; size: number; getFile: () => Promise<Blob> }>();
const isImage = props.type.startsWith('image/');
const root = ref<HTMLElement>();
const preview = ref('');
const failure = ref('');
const busy = ref(false);
const viewerOpen = ref(false);
let mounted = true;
let observer: IntersectionObserver | undefined;

async function open(download: boolean) {
  if (busy.value) return;
  busy.value = true;
  failure.value = '';
  try {
    const file = await props.getFile();
    if (!mounted) return;
    const url = URL.createObjectURL(file);
    if (download) {
      const link = document.createElement('a');
      link.href = url;
      link.download = props.name;
      link.click();
      window.setTimeout(() => URL.revokeObjectURL(url), 30_000);
    } else {
      if (preview.value) URL.revokeObjectURL(preview.value);
      preview.value = url;
    }
  } catch {
    if (mounted) failure.value = '附件暂时无法读取，请重试。';
  } finally {
    if (mounted) busy.value = false;
  }
}

onMounted(() => {
  if (!isImage || !root.value) return;
  if ('IntersectionObserver' in window) {
    observer = new IntersectionObserver((entries) => {
      if (entries.some((e) => e.isIntersecting)) {
        observer?.disconnect();
        observer = undefined;
        if (!preview.value) void open(false);
      }
    });
    observer.observe(root.value);
  } else {
    void open(false);
  }
});

onUnmounted(() => {
  mounted = false;
  observer?.disconnect();
  if (preview.value) URL.revokeObjectURL(preview.value);
});
</script>

<template>
  <div ref="root" class="attachment-message">
    <div v-if="isImage && preview" class="attachment-image-wrap">
      <img :src="preview" :alt="name" class="attachment-thumb" @click="viewerOpen = true">
      <div class="inline-actions attachment-image-bar">
        <small>{{ name }} · {{ Math.max(1, Math.round(size / 1024)) }} KB</small>
        <el-button link type="primary" :disabled="busy" @click="viewerOpen = true">预览图片</el-button>
        <el-button link type="primary" :disabled="busy" @click="open(true)">下载</el-button>
      </div>
      <el-image-viewer v-if="viewerOpen" :url-list="[preview]" @close="viewerOpen = false" />
    </div>
    <template v-else>
      <div class="file-title">
        <span class="file-icon" aria-hidden="true"><el-icon><component :is="isImage ? Picture : Document" /></el-icon></span>
        <span><strong>{{ name }}</strong><small>{{ Math.max(1, Math.round(size / 1024)) }} KB</small></span>
      </div>
      <div class="inline-actions">
        <el-button v-if="isImage" link type="primary" :disabled="busy" @click="open(false)">{{ busy ? '加载中…' : '预览图片' }}</el-button>
        <el-button v-else-if="!preview && type === 'application/pdf'" link type="primary" :disabled="busy" @click="open(false)">准备 PDF 预览</el-button>
        <el-button link type="primary" :disabled="busy" @click="open(true)">下载附件</el-button>
      </div>
      <a v-if="preview && !isImage" :href="preview" target="_blank" rel="noopener noreferrer">在新标签页预览 PDF</a>
    </template>
    <p v-if="failure" class="feedback" aria-live="polite">{{ failure }}</p>
  </div>
</template>
