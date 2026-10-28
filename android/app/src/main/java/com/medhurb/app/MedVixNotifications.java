package com.medhurb.app;

import android.Manifest;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import com.google.firebase.messaging.FirebaseMessaging;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

/**
 * JavaScript bridge exposing the native notification subsystem to the
 * MedVix WebView as window.MedVixNotifications.
 *
 * Design notes:
 *
 *   • Synchronous methods only. Asynchronous results (permission grant,
 *     FCM token refresh) are delivered through
 *     window.MedVixNotificationCallbacks on the JavaScript side. This
 *     matches the addJavascriptInterface synchronous pattern already
 *     used by MedVixStorage.
 *
 *   • State is persisted in SharedPreferences under
 *     "medvix_notification_state". Keys are shared with the receiver,
 *     boot receiver, and FCM service so any of them can read or write
 *     without the Activity being alive.
 *
 *   • Static helpers (displayNotification, scheduleAlarm, …) are public
 *     so the BroadcastReceivers and FCM service can use them without
 *     instantiating the bridge (they run without an Activity).
 *
 *   • Permission flow: {@link #requestPermission()} calls
 *     ActivityCompat.requestPermissions directly. The Activity's
 *     onRequestPermissionsResult MUST forward to
 *     {@link #handlePermissionResult(int, int[])} (added in the
 *     MainActivity patch, next file). Without that forward the JS
 *     callback fires only when permission is already granted.
 */
public final class MedVixNotifications {

    public static final String TAG = "MedVixNotifications";

    // Shared storage — read by bridge, receivers, and FCM service alike.
    public static final String PREFS_NAME        = "medvix_notification_state";
    public static final String KEY_PENDING_ROUTE = "pending_route";
    public static final String KEY_FCM_TOKEN     = "fcm_token";
    public static final String KEY_FCM_TOKEN_AT  = "fcm_token_updated_at";
    public static final String KEY_REMINDERS     = "scheduled_reminders";

    public static final String CHANNEL_ID = "medvix_general";

    /** Request code used for POST_NOTIFICATIONS. Must match the value
     *  used by MainActivity.onRequestPermissionsResult's forward. */
    public static final int REQ_POST_NOTIFICATIONS = 0x4D56_4E31; // "MVN1"

    // Weak reference to the active WebView so background components
    // (FCM service, boot receiver) can notify JS if the app is alive.
    // If the app is not running, they persist to prefs instead.
    private static volatile WeakReference<WebView> sWebViewRef;

    // Static instance pointer so onRequestPermissionsResult can forward
    // without MainActivity needing to hold a typed field. Set on
    // construction, cleared in clearActiveWebView().
    private static volatile MedVixNotifications sInstance;

    private final android.app.Activity activity;

    public MedVixNotifications(android.app.Activity activity) {
        this.activity = activity;
        sInstance = this;
        createChannelIfNeeded(activity);
        fetchTokenOnce();
    }

    // ==================================================================
    //  Channel creation
    // ==================================================================

    public static void createChannelIfNeeded(Context context) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return;

        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "MedVix Notifications",
                NotificationManager.IMPORTANCE_DEFAULT);
        channel.setDescription("Study reminders and MedVix updates");
        nm.createNotificationChannel(channel);
    }

    // ==================================================================
    //  JS bridge — status
    // ==================================================================

    @JavascriptInterface
    public boolean isSupported() {
        return true;
    }

    @JavascriptInterface
    public boolean hasPermission() {
        if (Build.VERSION.SDK_INT < 33) return true;
        return ContextCompat.checkSelfPermission(
                activity, Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED;
    }

    /** True iff the user has not disabled the medvix_general channel. */
    @JavascriptInterface
    public boolean isChannelEnabled() {
        if (Build.VERSION.SDK_INT < 26) return hasPermission();
        NotificationManager nm =
                (NotificationManager) activity.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return false;
        NotificationChannel channel = nm.getNotificationChannel(CHANNEL_ID);
        return channel != null
                && channel.getImportance() != NotificationManager.IMPORTANCE_NONE;
    }

    // ==================================================================
    //  JS bridge — permission
    // ==================================================================

    /**
     * Requests POST_NOTIFICATIONS if not already granted. Async:
     * the eventual result reaches JS through
     * window.MedVixNotificationCallbacks.onPermissionResult(boolean),
     * which is fired by MainActivity.onRequestPermissionsResult ->
     * {@link #handlePermissionResult(int, int[])}.
     */
    @JavascriptInterface
    public void requestPermission() {
        activity.runOnUiThread(() -> {
            if (hasPermission()) {
                firePermissionCallback(true);
                return;
            }
            if (Build.VERSION.SDK_INT < 33) {
                // Pre-13: implicit grant. Fire the callback so JS code
                // paths converge regardless of API level.
                firePermissionCallback(true);
                return;
            }
            ActivityCompat.requestPermissions(
                    activity,
                    new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    REQ_POST_NOTIFICATIONS);
        });
    }

    /**
     * Called from MainActivity.onRequestPermissionsResult. Returns true
     * if this request code belongs to the notification subsystem, so
     * the Activity can decide whether to pass it on to super.
     */
    public static boolean handlePermissionResult(int requestCode, int[] grantResults) {
        if (requestCode != REQ_POST_NOTIFICATIONS) return false;
        MedVixNotifications inst = sInstance;
        if (inst == null) return true; // ours, but no live bridge to notify
        boolean granted = grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        inst.firePermissionCallback(granted);
        return true;
    }

    private void firePermissionCallback(boolean granted) {
        WebView wv = activeWebView();
        if (wv == null) return;
        wv.post(() -> {
            try {
                wv.evaluateJavascript(
                        "(function(){if(window.MedVixNotificationCallbacks"
                                + "&&window.MedVixNotificationCallbacks.onPermissionResult){"
                                + "window.MedVixNotificationCallbacks.onPermissionResult("
                                + granted + ");}})();",
                        null);
            } catch (Exception ignored) {}
        });
    }

    @JavascriptInterface
    public void openNotificationSettings() {
        try {
            Intent intent = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
            intent.putExtra(Settings.EXTRA_APP_PACKAGE, activity.getPackageName());
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(intent);
        } catch (Exception e) {
            Log.e(TAG, "openNotificationSettings failed", e);
        }
    }

    // ==================================================================
    //  JS bridge — local notifications
    // ==================================================================

    @JavascriptInterface
    public boolean show(String title, String body, String route) {
        try {
            createChannelIfNeeded(activity);
            if (!hasPermission()) {
                Log.w(TAG, "show() called without POST_NOTIFICATIONS");
                return false;
            }
            int id = (int) (System.currentTimeMillis() & 0x7FFFFFFF);
            displayNotification(activity, id, title, body, route);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "show() failed", e);
            return false;
        }
    }

    @JavascriptInterface
    public boolean schedule(int id, String title, String body, String route,
                            long triggerAtMillis) {
        try {
            createChannelIfNeeded(activity);
            Reminder r = new Reminder(id, title, body, route, triggerAtMillis);
            savePersistedReminder(activity, r);
            scheduleAlarm(activity, r);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "schedule() failed", e);
            return false;
        }
    }

    @JavascriptInterface
    public boolean cancel(int id) {
        try {
            cancelAlarm(activity, id);
            removePersistedReminder(activity, id);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "cancel() failed", e);
            return false;
        }
    }

    @JavascriptInterface
    public boolean cancelAll() {
        try {
            for (Reminder r : loadPersistedReminders(activity)) {
                cancelAlarm(activity, r.id);
            }
            clearPersistedReminders(activity);
            NotificationManagerCompat.from(activity).cancelAll();
            return true;
        } catch (Exception e) {
            Log.e(TAG, "cancelAll() failed", e);
            return false;
        }
    }

    // ==================================================================
    //  JS bridge — navigation
    // ==================================================================

    @JavascriptInterface
    public String consumePendingRoute() {
        SharedPreferences prefs =
                activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String route = prefs.getString(KEY_PENDING_ROUTE, null);
        if (route == null || route.isEmpty()) return "";
        prefs.edit().remove(KEY_PENDING_ROUTE).apply();
        return validateRoute(route) ? route : "/";
    }

    // ==================================================================
    //  JS bridge — FCM token
    // ==================================================================

    @JavascriptInterface
    public String getCachedFcmToken() {
        return activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_FCM_TOKEN, "");
    }

    @JavascriptInterface
    public void refreshFcmToken() {
        try {
            FirebaseMessaging.getInstance().getToken()
                    .addOnCompleteListener(task -> {
                        if (task.isSuccessful() && task.getResult() != null) {
                            String token = task.getResult();
                            activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                                    .edit()
                                    .putString(KEY_FCM_TOKEN, token)
                                    .putLong(KEY_FCM_TOKEN_AT, System.currentTimeMillis())
                                    .apply();
                            notifyTokenToWebView(token);
                        } else {
                            Log.w(TAG, "refreshFcmToken failed", task.getException());
                        }
                    });
        } catch (Exception e) {
            Log.w(TAG, "FCM unavailable", e);
        }
    }

    private void fetchTokenOnce() {
        try {
            FirebaseMessaging.getInstance().getToken()
                    .addOnCompleteListener(task -> {
                        if (task.isSuccessful() && task.getResult() != null) {
                            String token = task.getResult();
                            activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                                    .edit()
                                    .putString(KEY_FCM_TOKEN, token)
                                    .putLong(KEY_FCM_TOKEN_AT, System.currentTimeMillis())
                                    .apply();
                            notifyTokenToWebView(token);
                        }
                    });
        } catch (Exception e) {
            Log.w(TAG, "Initial FCM token fetch failed", e);
        }
    }

    // ==================================================================
    //  Static helpers — WebView reference
    // ==================================================================

    public static void setActiveWebView(WebView wv) {
        sWebViewRef = new WeakReference<>(wv);
    }

    public static void clearActiveWebView() {
        sWebViewRef = null;
        sInstance = null;
    }

    private static WebView activeWebView() {
        WeakReference<WebView> ref = sWebViewRef;
        return ref != null ? ref.get() : null;
    }

    public static void notifyTokenToWebView(String token) {
        WebView wv = activeWebView();
        if (wv == null) return;
        String escaped;
        try {
            escaped = JSONObject.quote(token);
        } catch (Exception e) {
            return;
        }
        wv.post(() -> {
            try {
                wv.evaluateJavascript(
                        "(function(){if(window.MedVixNotificationCallbacks"
                                + "&&window.MedVixNotificationCallbacks.onFcmToken){"
                                + "window.MedVixNotificationCallbacks.onFcmToken("
                                + escaped + ");}})();",
                        null);
            } catch (Exception ignored) {}
        });
    }

    // ==================================================================
    //  Static helpers — display notification
    // ==================================================================

    public static void displayNotification(Context context, int id,
                                           String title, String body, String route) {
        createChannelIfNeeded(context);

        if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(
                        context, Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
            return;
        }

        Intent openIntent = new Intent(context, MainActivity.class);
        openIntent.setAction("com.medvix.OPEN_NOTIFICATION");
        openIntent.putExtra("medvix_notification_route",
                validateRoute(route) ? route : "/");
        openIntent.putExtra("medvix_notification_id", id);
        openIntent.setFlags(
                Intent.FLAG_ACTIVITY_CLEAR_TOP |
                Intent.FLAG_ACTIVITY_SINGLE_TOP);

        PendingIntent tapIntent = PendingIntent.getActivity(
                context,
                id,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        String safeTitle = (title == null || title.trim().isEmpty())
                ? "MedVix" : title;
        String safeBody = body == null ? "" : body;

        Notification notification = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_medvix_notification)
                .setContentTitle(safeTitle)
                .setContentText(safeBody)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(safeBody))
                .setContentIntent(tapIntent)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build();

        try {
            NotificationManagerCompat.from(context).notify(id, notification);
        } catch (SecurityException e) {
            Log.w(TAG, "notify() rejected", e);
        }
    }

    // ==================================================================
    //  Static helpers — scheduling
    // ==================================================================

    public static void scheduleAlarm(Context context, Reminder r) {
        AlarmManager am =
                (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;

        Intent intent = new Intent(context, MedVixNotificationReceiver.class);
        intent.putExtra("id", r.id);
        intent.putExtra("title", r.title);
        intent.putExtra("body", r.body);
        intent.putExtra("route", r.route);

        PendingIntent pi = PendingIntent.getBroadcast(
                context,
                r.id,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        boolean useExact = true;
        if (Build.VERSION.SDK_INT >= 31) {
            try {
                useExact = am.canScheduleExactAlarms();
            } catch (Exception e) {
                useExact = false;
            }
        }

        try {
            if (useExact) {
                am.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP, r.triggerAtMillis, pi);
            } else {
                am.setAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP, r.triggerAtMillis, pi);
            }
        } catch (SecurityException e) {
            am.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP, r.triggerAtMillis, pi);
        }
    }

    public static void cancelAlarm(Context context, int id) {
        AlarmManager am =
                (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;

        Intent intent = new Intent(context, MedVixNotificationReceiver.class);
        PendingIntent pi = PendingIntent.getBroadcast(
                context,
                id,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        am.cancel(pi);
    }

    // ==================================================================
    //  Static helpers — persistence
    // ==================================================================

    public static List<Reminder> loadPersistedReminders(Context context) {
        List<Reminder> out = new ArrayList<>();
        SharedPreferences prefs =
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String raw = prefs.getString(KEY_REMINDERS, "[]");
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                Reminder r = Reminder.fromJson(o);
                if (r != null) out.add(r);
            }
        } catch (JSONException e) {
            Log.w(TAG, "Corrupt reminders JSON — discarding", e);
        }
        return out;
    }

    public static void savePersistedReminder(Context context, Reminder r) {
        List<Reminder> existing = loadPersistedReminders(context);
        List<Reminder> next = new ArrayList<>();
        for (Reminder x : existing) {
            if (x.id != r.id) next.add(x);
        }
        next.add(r);
        writePersisted(context, next);
    }

    public static void removePersistedReminder(Context context, int id) {
        List<Reminder> next = new ArrayList<>();
        for (Reminder x : loadPersistedReminders(context)) {
            if (x.id != id) next.add(x);
        }
        writePersisted(context, next);
    }

    public static void clearPersistedReminders(Context context) {
        writePersisted(context, new ArrayList<>());
    }

    private static void writePersisted(Context context, List<Reminder> list) {
        JSONArray arr = new JSONArray();
        for (Reminder r : list) {
            try {
                arr.put(r.toJson());
            } catch (JSONException e) {
                Log.w(TAG, "Could not serialise reminder " + r.id, e);
            }
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_REMINDERS, arr.toString())
                .apply();
    }

    // ==================================================================
    //  Route validation — mirrors MainActivity.validateDeferredRoute
    // ==================================================================

    static boolean validateRoute(String route) {
        if (route == null || route.isEmpty()) return false;
        if (!route.startsWith("/")) return false;
        if (route.startsWith("//")) return false;
        if (route.contains("\\")) return false;
        if (route.indexOf('\n') >= 0) return false;
        if (route.indexOf('\r') >= 0) return false;
        Uri uri = Uri.parse(route);
        String path = uri.getPath();
        return path != null && !path.isEmpty();
    }

    // ==================================================================
    //  Reminder DTO
    // ==================================================================

    public static final class Reminder {
        public final int id;
        public final String title;
        public final String body;
        public final String route;
        public final long triggerAtMillis;

        public Reminder(int id, String title, String body, String route,
                        long triggerAtMillis) {
            this.id = id;
            this.title = title;
            this.body = body;
            this.route = route;
            this.triggerAtMillis = triggerAtMillis;
        }

        JSONObject toJson() throws JSONException {
            JSONObject o = new JSONObject();
            o.put("id", id);
            o.put("title", title == null ? "" : title);
            o.put("body", body == null ? "" : body);
            o.put("route", route == null ? "/" : route);
            o.put("triggerAtMillis", triggerAtMillis);
            return o;
        }

        static Reminder fromJson(JSONObject o) {
            try {
                return new Reminder(
                        o.getInt("id"),
                        o.optString("title", ""),
                        o.optString("body", ""),
                        o.optString("route", "/"),
                        o.getLong("triggerAtMillis"));
            } catch (JSONException e) {
                return null;
            }
        }
    }
}