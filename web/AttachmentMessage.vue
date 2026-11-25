<script setup lang="ts">
import { onUnmounted, ref } from 'vue';
import { Document, Picture } from '@element-plus/icons-vue';

const props = defineProps<{ name: string; type: string; size: number; getFile: () => Promise<Blob> }>();
const preview = ref('');
const failure = ref('');
const busy = ref(false);
let mounted = true;

async function open(download: boolean) {
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

onUnmounted(() => {
  mounted = false;
  if (preview.value) URL.revokeObjectURL(preview.value);
});
</script>

<template>
  <div class="attachment-message">
    <div class="file-title">
      <span class="file-icon" aria-hidden="true"><el-icon><component :is="type.startsWith('image/') ? Picture : Document" /></el-icon></span>
      <span><strong>{{ name }}</strong><small>{{ Math.max(1, Math.round(size / 1024)) }} KB</small></span>
    </div>
    <div class="inline-actions">
      <el-button v-if="type.startsWith('image/')" link type="primary" :disabled="busy" @click="open(false)">预览图片</el-button>
      <el-button v-else-if="!preview && type === 'application/pdf'" link type="primary" :disabled="busy" @click="open(false)">准备 PDF 预览</el-button>
      <el-button link type="primary" :disabled="busy" @click="open(true)">下载附件</el-button>
    </div>
    <img v-if="preview && type.startsWith('image/')" :src="preview" :alt="name">
    <a v-else-if="preview" :href="preview" target="_blank" rel="noopener noreferrer">在新标签页预览 PDF</a>
    <p v-if="failure" class="feedback" aria-live="polite">{{ failure }}</p>
  </div>
</template>
