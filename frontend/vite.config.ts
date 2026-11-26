import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react-swc'
import svgr from 'vite-plugin-svgr'

export default defineConfig({
  base: '/',
  plugins: [react(), svgr()],
  server: {
    open: true,
    port: 3000,
  },
  build: {
    outDir: 'build',
    rolldownOptions: {
      output: {
        // Libraries change less often than the planner, so they stay cached between releases
        codeSplitting: {
          groups: [
            { name: 'react', test: /node_modules[\/](react|react-dom|react-router|scheduler)[\/]/ },
            { name: 'vendor', test: /node_modules/ },
          ],
        },
      },
    },
  },
})
