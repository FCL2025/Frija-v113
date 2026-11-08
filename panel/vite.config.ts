import path from 'node:path'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'
import { defineConfig } from 'vite'

// https://vite.dev/config/
export default defineConfig({
  // 建置產物會被打進 jar 的 server/panel/web/，由任意路徑提供，
  // 所以所有資源 URL 一律相對路徑。
  base: './',
  plugins: [react(), tailwindcss()],
  resolve: {
    alias: {
      '@': path.resolve(import.meta.dirname, './src'),
    },
  },
  build: {
    // 主 bundle（React + shadcn + TanStack）約 530KB，編輯器另外切成一塊 lazy-load；
    // 兩塊都從 jar 直接提供，沒有網路成本，不需要為了警告再細切。
    chunkSizeWarningLimit: 800,
  },
  server: {
    proxy: {
      // `npm run dev` 時把 /api 轉給本機的嵌入式 HttpServer（tms.PanelPort，預設 8080）。
      '/api': {
        target: 'http://127.0.0.1:8080',
        changeOrigin: false,
      },
    },
  },
})
