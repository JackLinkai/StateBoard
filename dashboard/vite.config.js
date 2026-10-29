import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// 开发模式：npm run dev，API 代理到本机 ASC 服务（默认 8080）
// 构建产物：npm run build，输出到 Spring Boot 静态资源目录，
// 随 asc-server 一起启动，直接访问 http://localhost:8080 即可打开面板。
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/v1': 'http://localhost:8080',
    },
  },
  build: {
    outDir: '../src/main/resources/static',
    emptyOutDir: true,
  },
})
