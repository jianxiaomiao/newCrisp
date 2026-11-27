import vue from '@vitejs/plugin-vue';
import { defineConfig } from 'vite';

export default defineConfig({
  publicDir: false,
  plugins: [
    vue(),
    {
      name: 'newcrisp-pages',
      generateBundle: {
        order: 'post',
        handler(_options, bundle) {
          const entry = Object.values(bundle).find((chunk) => chunk.type === 'chunk' && chunk.isEntry && chunk.name === 'main');
          if (!entry) throw new Error('Missing Vue entry');
          const styleLinks = Object.keys(bundle).filter((name) => name.endsWith('.css'))
            .map((name) => `<link rel="stylesheet" href="/${name}">`).join('\n');
          if (!styleLinks) throw new Error('Missing NewCrisp stylesheet');
          for (const [page, title] of [
            ['index', '多租户在线客服服务台'],
            ['platform', '平台管理台'],
            ['staff', '客服工作台'],
            ['customer', '在线客服'],
            ['guest', '访客客服'],
          ]) {
            this.emitFile({
              type: 'asset',
              fileName: `${page}.html`,
              source: `<!doctype html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>${title} · NewCrisp</title>
${styleLinks}
<script type="module" src="/${entry.fileName}"></script>
</head>
<body class="chat-page"><div id="app"></div></body>
</html>`,
            });
          }
        },
      },
    },
  ],
  build: {
    outDir: 'target/classes/static',
    emptyOutDir: true,
    cssCodeSplit: false,
    rolldownOptions: {
      input: { main: 'web/main.ts', 'web-session': 'web/web-session.ts', widget: 'web/widget.ts' },
      preserveEntrySignatures: 'strict',
      output: {
        entryFileNames: (entry) => entry.name === 'web-session' ? 'web-session.js'
          : entry.name === 'widget' ? 'widget.js' : 'assets/[name]-[hash].js',
      },
    },
  },
});
