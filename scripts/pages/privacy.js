// scripts/pages/privacy.js
import * as ui from '../ui.js';
import * as router from '../router.js';

let $;

export async function init(context) {
  $ = (sel) => context.root.querySelector(sel);

  ui.applyTheme();

  // Attach event listeners
  const themeToggle = $('#themeToggle');
  if (themeToggle) {
    themeToggle.addEventListener('click', ui.toggleTheme);
  }

  const backToSignupBtn = $('#backToSignupBtn');
  if (backToSignupBtn) {
    backToSignupBtn.addEventListener('click', () => router.navigateTo('signup'));
  }

  const goToWelcomeBtn = $('#goToWelcomeBtn');
  if (goToWelcomeBtn) {
    goToWelcomeBtn.addEventListener('click', () => router.navigateTo('welcome'));
  }

  const backHomeBtn = $('#backHomeBtn');
  if (backHomeBtn) {
    backHomeBtn.addEventListener('click', () => router.navigateTo('index'));
  }

  console.log('[Privacy] Initialized');
}

export function destroy() {
  // Cleanup if needed
}