// scripts/subscription.js

/**
 * Subscription Management – Backend‑Integrated
 * Handles free trial, subscription plans, expiry checks, eligibility,
 * and free topics for non-subscribers.
 * Stores subscription data in IndexedDB via db.js.
 * All operations that require backend updates will be done through Convex.
 *
 * UI remains unchanged – uses local PLANS constant for display.
 * Backend is used only for eligibility, trial start, status, and purchase.
 */

import * as utils from './utils.js';
import * as db from './db.js';
import * as ui from './ui.js';
import * as payment from './payment.js'; // for plan selection
import { convexHttpClient } from './convex-client.js';
import { getToken, logout } from './auth.js';
import * as security from './security.js';
import * as timeVerifier from './timeVerifier.js';

// ==================== CONSTANTS (ORIGINAL UI PLANS) ====================

const PLANS = {
    trial: {
        id: 'trial',
        name: 'Free Trial',
        price: 0,
        duration: 3 * 60 * 60 * 1000,
        durationText: '3 hours',
        features: [
            'Full access to all subjects',
            'All exam modes',
            'Detailed analytics',
            'No payment required'
        ],
        limitations: [
            'Time‑limited (3 hours)',
            'One trial per user',
            'No certificate generation'
        ],
        ctaText: 'Start Free Trial',
        ctaColor: 'primary'
    },
    monthly: {
        id: 'monthly',
        name: 'Monthly Plan',
        price: 300,
        duration: 30 * 24 * 60 * 60 * 1000,
        durationText: '30 days',
        features: [
            'Unlimited access',
            'All 8 subjects',
            'Detailed analytics',
            'Certificate generation',
            'Priority support'
        ],
        ctaText: 'Subscribe – KES 300',
        ctaColor: 'success',
        popular: false
    },
    quarterly: {
        id: 'quarterly',
        name: 'Quarterly Plan',
        price: 850,
        duration: 90 * 24 * 60 * 60 * 1000,
        durationText: '3 months',
        features: [
            'Unlimited access',
            'All 8 subjects',
            'Detailed analytics',
            'Certificate generation',
            'Priority support',
            'Save KES 50'
        ],
        savings: 'Save KES 50',
        ctaText: 'Subscribe – KES 850',
        ctaColor: 'success',
        popular: true
    },
    yearly: {
        id: 'yearly',
        name: 'Yearly Plan',
        price: 2100,
        duration: 365 * 24 * 60 * 60 * 1000,
        durationText: '1 year',
        features: [
            'Unlimited access',
            'All 8 subjects',
            'Detailed analytics',
            'Certificate generation',
            'Priority support',
            'Save KES 1,500'
        ],
        savings: 'Save KES 1,500',
        ctaText: 'Subscribe – KES 2,100',
        ctaColor: 'success'
    }
};

// ==================== FREE TOPICS ====================
const FREE_TOPICS = {
    anatomy: ['back', 'introduction-anatomy', 'cross-sectional-anatomy'],
    physiology: ['introduction-homeostasis', 'body-fluids-compartments', 'membrane-physiology'],
    biochemistry: ['nucleic-acids', 'bioenergetics', 'metabolism-overview'],
    histology: ['introduction-histology', 'cell-structure', 'adipose-tissue'],
    embryology: ['introduction-embryology', 'gametogenesis', 'fertilization'],
    pathology: ['adaptations', 'intracellular-accumulations', 'hemodynamic-disorders'],
    pharmacology: ['drug-metabolism', 'drug-interactions', 'pharmacodynamics'],
    microbiology: ['bacterial-structure', 'bacterial-physiology', 'sterilization-disinfection']
};

// ==================== STATE ====================

let subscriptionStatus = null;

// ==================== HELPER: ONLINE CHECK ====================

function requireOnline() {
    if (!navigator.onLine) {
        throw new Error('You need to be online to perform this action.');
    }
}

// ==================== SUBSCRIPTION MANAGEMENT FUNCTIONS ====================

export function getSubscription() {
    return subscriptionStatus;
}

export async function setSubscription(sub) {
    subscriptionStatus = sub;
    try {
        await db.saveSubscription(sub);
    } catch (e) {
        utils.setLocalStorage('subscription', sub);
    }
}

export async function clearSubscription() {
    subscriptionStatus = null;
    try {
        await db.deleteSubscription();
    } catch (e) {
        utils.removeLocalStorage('subscription');
    }
}

// ==================== INITIALIZATION ====================

export async function initSubscription() {
    const sub = await getSubscriptionStatus(false);
    if (sub) subscriptionStatus = sub;
    else subscriptionStatus = null;
    return subscriptionStatus;
}

export function fallbackLoadSubscription() {
    subscriptionStatus = utils.getLocalStorage('subscription', null);
}

// ==================== SUBSCRIPTION STATUS ====================

export async function getSubscriptionStatus(forceRefresh = false) {
    if (forceRefresh || navigator.onLine) {
        try {
            const token = getToken();
            if (!token) return null;
            const result = await convexHttpClient.action("subscriptions/queries:getSubscriptionStatus", { token });
            console.log('[Subscription] Raw backend response:', JSON.stringify(result, null, 2));

            if (result && result.success && result.data) {
                const backendSub = result.data;
                const isActive = backendSub.isActive !== undefined
                    ? backendSub.isActive
                    : (backendSub.expiryDate && backendSub.expiryDate > Date.now());
                const normalizedSub = {
                    _id: backendSub._id,
                    plan: backendSub.plan,
                    isActive: isActive,
                    expiryDate: backendSub.expiryDate,
                    status: backendSub.status,
                    startDate: backendSub.startDate,
                    autoRenew: backendSub.autoRenew ?? false,
                    paymentMethod: backendSub.paymentMethod ?? null,
                };
                console.log('[Subscription] Normalized sub:', normalizedSub);
                await setSubscription(normalizedSub);
                // ✅ After successful backend sync, reset time verifier (trust server time)
                timeVerifier.resetTimeVerifier();
                return normalizedSub;
            } else if (result && !result.success) {
                if (result.error === 'invalid_token' || result.message?.toLowerCase().includes('token')) {
                    await logout();
                    window.location.href = '/pages/login.html';
                    return null;
                }
                console.warn('Backend returned error:', result.message);
            }
        } catch (err) {
            console.warn('Failed to fetch subscription from backend, falling back to cache', err);
        }
    }

    try {
        const cached = await db.getSubscription();
        if (cached) return cached;
    } catch (e) {}
    return utils.getLocalStorage('subscription', null);
}

export async function hasActiveSubscription() {
    if (!timeVerifier.verifyTime()) return false;
    const sub = await getSubscriptionStatus();
    if (!sub) {
        console.log('[Subscription] No subscription object');
        return false;
    }
    const { isActive, expiryDate } = sub;
    console.log(`[Subscription] Checking active: isActive=${isActive}, expiry=${expiryDate}, now=${Date.now()}`);
    if (isActive !== undefined && isActive !== null) {
        return isActive && (expiryDate ? expiryDate > Date.now() : true);
    }
    return expiryDate && expiryDate > Date.now();
}

export async function isTrialActive() {
    if (!timeVerifier.verifyTime()) return false;
    const sub = await getSubscriptionStatus();
    return sub && sub.plan === 'trial' && sub.isActive && new Date(sub.expiryDate).getTime() > Date.now();
}

export async function isPaidSubscription() {
    if (!timeVerifier.verifyTime()) return false;
    const sub = await getSubscriptionStatus();
    return sub && sub.plan !== 'trial' && sub.isActive && new Date(sub.expiryDate).getTime() > Date.now();
}

// ==================== TRIAL MANAGEMENT ====================

export async function checkTrialEligibility() {
    requireOnline();
    try {
        const token = getToken();
        if (!token) throw new Error('Not authenticated');
        const deviceFingerprint = security.getDeviceFingerprint();
        const result = await convexHttpClient.action("subscriptions/queries:checkTrialEligibility", {
            token,
            deviceFingerprint
        });
        if (!result.success) {
            if (result.error === 'invalid_token' || result.message?.toLowerCase().includes('token')) {
                await logout();
                window.location.href = '/pages/login.html';
                throw new Error('Session expired. Please login again.');
            }
            throw new Error(result.message);
        }
        return result.data.eligible;
    } catch (err) {
        console.error('Failed to check trial eligibility', err);
        throw new Error('Could not verify trial eligibility');
    }
}

export async function startFreeTrial({ deviceFingerprint }) {
    requireOnline();
    const token = getToken();
    if (!token) throw new Error('Not authenticated');
    try {
        const result = await convexHttpClient.action("subscriptions/actions:startFreeTrial", {
            token,
            deviceFingerprint
        });
        if (!result.success) {
            if (result.error === 'invalid_token' || result.message?.toLowerCase().includes('token')) {
                await logout();
                window.location.href = '/pages/login.html';
                throw new Error('Session expired. Please login again.');
            }
            throw new Error(result.message);
        }
        const subscriptionData = result.data;
        const normalizedSub = {
            _id: subscriptionData.subscriptionId,
            plan: subscriptionData.plan,
            isActive: true,
            expiryDate: subscriptionData.expiryDate,
            status: 'active'
        };
        await setSubscription(normalizedSub);
        timeVerifier.resetTimeVerifier();
        return normalizedSub;
    } catch (err) {
        console.error('Failed to start trial', err);
        throw new Error(err.message || 'Could not start trial');
    }
}

export async function getTrialRemaining() {
    if (!timeVerifier.verifyTime()) return null;
    const sub = await getSubscriptionStatus();
    if (!sub || sub.plan !== 'trial' || !sub.isActive) return null;
    const now = Date.now();
    const expiry = new Date(sub.expiryDate).getTime();
    const remainingMs = expiry - now;
    if (remainingMs <= 0) return null;
    return utils.formatTime(Math.floor(remainingMs / 1000));
}

// ==================== PLAN MANAGEMENT ====================

export async function getSubscriptionPlans() {
    return Object.values(PLANS);
}

export function selectPlan(planId) {
    const plan = Object.values(PLANS).find(p => p.id === planId);
    payment.setSelectedPlan(plan);
}

export function setCustomPlan(amount) {
    const plan = {
        id: 'custom',
        name: 'Custom Amount',
        price: amount,
        duration: amount,
        durationText: 'Custom',
        features: ['Pay as you wish', 'Flexible access'],
        ctaText: `Pay KES ${amount}`,
        ctaColor: 'primary'
    };
    payment.setSelectedPlan(plan);
}

export async function purchaseSubscription(planId, phoneNumber, customAmount = null) {
    requireOnline();
    const token = getToken();
    if (!token) throw new Error('Not authenticated');
    try {
        const result = await convexHttpClient.action("subscriptions/actions:purchaseSubscription", {
            token,
            planName: planId,
            deviceFingerprint: security.getDeviceFingerprint(),
            phoneNumber,
            customAmount,
        });
        if (!result.success) {
            if (result.error === 'invalid_token' || result.message?.toLowerCase().includes('token')) {
                await logout();
                window.location.href = '/pages/login.html';
                throw new Error('Session expired. Please login again.');
            }
            throw new Error(result.message);
        }
        return result.data;
    } catch (err) {
        console.error('Purchase failed', err);
        throw new Error(err.message || 'Purchase failed');
    }
}

export async function cancelSubscription() {
    requireOnline();
    const sub = await getSubscriptionStatus();
    if (!sub) throw new Error('No active subscription');
    const token = getToken();
    if (!token) throw new Error('Not authenticated');
    try {
        const result = await convexHttpClient.action("subscriptions/actions:cancelSubscription", { token });
        if (!result.success) {
            if (result.error === 'invalid_token' || result.message?.toLowerCase().includes('token')) {
                await logout();
                window.location.href = '/pages/login.html';
                throw new Error('Session expired. Please login again.');
            }
            throw new Error(result.message);
        }
        await getSubscriptionStatus(true);
        return { success: true };
    } catch (err) {
        console.error('Cancel failed', err);
        throw new Error(err.message || 'Cancel failed');
    }
}

// ==================== FREE TOPICS HELPERS ====================

export function isTopicFree(subject, topicId) {
    return FREE_TOPICS[subject]?.includes(topicId) ?? false;
}

export function areAllTopicsFree(config) {
    if (!config || !config.subject || !config.topics || config.topics.length === 0) return false;
    return config.topics.every(t => isTopicFree(config.subject, t.id));
}

// ==================== ACCESS CONTROL ====================

export async function canTakeExam(config) {
    if (await hasActiveSubscription()) return true;
    return areAllTopicsFree(config);
}

export async function canViewAnalytics() {
    return await hasActiveSubscription();
}

export async function canExportResults() {
    return await isPaidSubscription();
}

// ==================== TIME CALCULATIONS ====================

export async function calculateRemainingTime() {
    if (!timeVerifier.verifyTime()) return 0;
    const sub = await getSubscriptionStatus();
    if (!sub || !sub.isActive) return 0;
    const now = Date.now();
    const expiry = new Date(sub.expiryDate).getTime();
    return Math.max(0, Math.floor((expiry - now) / 1000));
}

export async function formatRemainingTime() {
    const seconds = await calculateRemainingTime();
    if (seconds <= 0) return 'Expired';
    const days = Math.floor(seconds / 86400);
    const hours = Math.floor((seconds % 86400) / 3600);
    const minutes = Math.floor((seconds % 3600) / 60);
    if (days > 0) return `${days}d ${hours}h`;
    if (hours > 0) return `${hours}h ${minutes}m`;
    return `${minutes}m`;
}

export async function isExpiringSoon(hours = 24) {
    const seconds = await calculateRemainingTime();
    return seconds > 0 && seconds < hours * 3600;
}

export async function activatePlan(subscriptionData) {
    await setSubscription(subscriptionData);
    timeVerifier.resetTimeVerifier();
    return subscriptionData;
}

// ==================== SYNC LOCAL COPY ====================

export async function syncSubscription(forceOnline = false) {
    return getSubscriptionStatus(forceOnline);
}

// ==================== REFRESH SUBSCRIPTION ====================

export async function refreshSubscription() {
    return await syncSubscription(true);
}

// ==================== EXPOSE GLOBALLY ====================

window.subscription = {
    getSubscriptionStatus,
    hasActiveSubscription,
    isTrialActive,
    isPaidSubscription,
    checkTrialEligibility,
    startFreeTrial,
    getTrialRemaining,
    getSubscriptionPlans,
    selectPlan,
    setCustomPlan,
    purchaseSubscription,
    cancelSubscription,
    isTopicFree,
    areAllTopicsFree,
    canTakeExam,
    canViewAnalytics,
    canExportResults,
    calculateRemainingTime,
    formatRemainingTime,
    isExpiringSoon,
    syncSubscription,
    // Extra exports for app.js compatibility
    setSubscription,
    getSubscription,
    clearSubscription,
    initSubscription,
    fallbackLoadSubscription,
    refreshSubscription
};