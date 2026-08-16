// scripts/pages/login.js
import * as auth from '../auth.js';
import * as ui from '../ui.js';
import * as router from '../router.js';
import * as validation from '../validation.js';
import * as security from '../security.js';
import * as utils from '../utils.js';

export async function init(context) {
  // Apply theme
  ui.applyTheme();

  // Deep‑link redirect
  const redirectParam = new URLSearchParams(window.location.search).get('redirect');
  let redirectTarget = null;
  if (redirectParam) {
    try {
      redirectTarget = decodeURIComponent(redirectParam);
      if (!redirectTarget.startsWith('/')) redirectTarget = null;
    } catch {
      redirectTarget = null;
    }
  }

  // If already authenticated, redirect
  if (app.checkAuth()) {
    if (redirectTarget) {
      window.location.href = redirectTarget;
    } else {
      router.navigateTo('subjects');
    }
    return;
  }

  // DOM refs – scoped to the page root
  const $ = (sel) => context.root.querySelector(sel);
  const loginForm = $('#login-form');
  const emailInput = $('#email');
  const passwordInput = $('#password');
  const toggleBtn = $('#togglePassword');
  const rememberCheck = $('#remember');
  const forgotBtn = $('#forgotBtn');
  const signupBtn = $('#signupBtn');
  const backBtn = $('#backBtn');
  const themeToggle = $('#themeToggle');

  // Setup live validation
  validation.setupLiveValidation('login-form', {
    email: { required: true, email: true },
    password: { required: true, min: 8 }
  });

  // Remembered email
  const rememberedEmail = utils.getLocalStorage('rememberedEmail');
  if (rememberedEmail) {
    emailInput.value = rememberedEmail;
    rememberCheck.checked = true;
  }

  // --- Event listeners ---

  // Theme toggle
  if (themeToggle) {
    themeToggle.addEventListener('click', () => {
      ui.toggleTheme();
    });
  }

  // Toggle password visibility
  if (toggleBtn) {
    toggleBtn.addEventListener('click', () => {
      ui.togglePasswordVisibility('password');
    });
  }

  // Forgot password
  if (forgotBtn) {
    forgotBtn.addEventListener('click', () => {
      let url = 'forgot-password';
      if (redirectTarget) {
        url += `?redirect=${encodeURIComponent(redirectTarget)}`;
      }
      router.navigateTo(url);
    });
  }

  // Signup
  if (signupBtn) {
    signupBtn.addEventListener('click', () => {
      let url = 'signup';
      if (redirectTarget) {
        url += `?redirect=${encodeURIComponent(redirectTarget)}`;
      }
      router.navigateTo(url);
    });
  }

  // Back button
  if (backBtn) {
    backBtn.addEventListener('click', () => {
      router.navigateTo('welcome');
    });
  }

  // Login form submission
  if (loginForm) {
    loginForm.addEventListener('submit', async (event) => {
      event.preventDefault();

      const email = emailInput.value.trim();
      const password = passwordInput.value;
      const remember = rememberCheck.checked;

      // Validate
      if (!validation.validateEmail(email) && !validation.validatePhone(email)) {
        ui.showToast('Enter a valid email or Kenyan phone', 'error');
        return;
      }
      if (!validation.validatePassword(password)) {
        ui.showToast('Password must be 8+ chars with upper, lower, number', 'error');
        return;
      }

      ui.showLoading();
      try {
        const deviceFingerprint = security.generateDeviceFingerprint();
        const deviceInfo = {
          platform: navigator.platform,
          userAgent: navigator.userAgent,
          screen: `${screen.width}x${screen.height}`,
          timezone: new Date().getTimezoneOffset()
        };

        const user = await auth.login(email, password, { deviceFingerprint, deviceInfo });

        if (remember) utils.setLocalStorage('rememberedEmail', email);
        else utils.removeLocalStorage('rememberedEmail');

        ui.hideLoading();
        ui.showToast('Login successful!', 'success');

        if (redirectTarget) {
          window.location.href = redirectTarget;
        } else {
          router.navigateTo('subjects');
        }
      } catch (error) {
        ui.hideLoading();
        ui.showToast(error.message || 'Login failed', 'error');
        if (error.code === 'ACCOUNT_LOCKED') {
          router.navigateTo('locked?reason=time_manipulation');
        }
      }
    });
  }
}

export function destroy() {
  // Cleanup – called when user leaves the page
}