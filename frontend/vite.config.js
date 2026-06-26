import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// In dev, proxy API calls to the gateway so the SPA uses a single same-origin /api prefix
// (the production nginx image does the same proxying).
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': { target: 'http://localhost:8080', changeOrigin: true },
    },
  },
})
