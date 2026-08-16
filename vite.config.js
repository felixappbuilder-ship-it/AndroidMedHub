import { defineConfig } from 'vite';
import { resolve } from 'path';
import { glob } from 'glob';
import obfuscator from 'vite-plugin-javascript-obfuscator';

export default defineConfig({
  root: '.',
  publicDir: 'public',
  plugins: [
    obfuscator({
      compact: true,
      controlFlowFlattening: false,
      deadCodeInjection: false,
      debugProtection: false,
      debugProtectionInterval: false,
      disableConsoleOutput: false,
      identifierNamesGenerator: 'hexadecimal',
      log: false,
      numbersToExpressions: false,
      renameGlobals: false,
      selfDefending: true,
      simplify: true,
      splitStrings: false,
      stringArray: true,
      stringArrayEncoding: ['base64'],
      stringArrayThreshold: 0.75,
      transformObjectKeys: false,
      unicodeEscapeSequence: false
    })
  ],
  build: {
    outDir: 'dist',
    rollupOptions: {
      input: Object.fromEntries(
        glob.sync(['index.html', 'pages/**/*.html']).map(file => [
          file.replace(/\.html$/, '').replace(/\//g, '-'),
          resolve(__dirname, file)
        ])
      ),
      output: {
        entryFileNames: 'scripts/[name].js',
        chunkFileNames: 'scripts/[name].js',
        assetFileNames: 'css/[name].[ext]',
        // 👇 Add this to force page scripts into separate chunks
manualChunks(id) {
    if (id.includes('/scripts/pages/')) {
        const match = id.match(/\/scripts\/pages\/(.+)\.js$/);
        if (match) {
            return `pages/${match[1]}`;
        }
    }
}
      }
    },
  },
  server: {
    port: 3001,
    open: '/index.html',
  },
});