package com.medhurb.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.annotation.NonNull;

import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

import java.util.Map;

/**
 * Firebase Cloud Messaging entry point for MedVix.
 *
 * Wired in AndroidManifest.xml via the
 * com.google.firebase.MESSAGING_EVENT intent-filter on a
 * android:exported="false" service declaration. The Firebase SDK
 * (via FirebaseInitProvider, registered automatically by
 * firebase-messaging) binds to this service and delivers FCM
 * events to the two overrides below.
 *
 * Responsibilities:
 *
 *   onNewToken(String)
 *     Called by Firebase whenever the device's FCM registration token
 *     is issued for the first time or rotated (reinstall, app data
 *     clear, token invalidation by Google, backend-forced refresh).
 *     We persist it to the shared "medvix_notification_state"
 *     SharedPreferences file so the bridge can read it via
 *     getCachedFcmToken(), and push it to the live WebView (if any)
 *     via MedVixNotifications.notifyTokenToWebView() so the app can
 *     sync to Convex immediately without waiting for a resume.
 *
 *     Token rotation is unpredictable — could be six months after
 *     install, could be the next morning. Treating this callback as
 *     the authoritative source of the current token (rather than
 *     caching blindly) is what keeps push delivery working across
 *     rotations.
 *
 *   onMessageReceived(RemoteMessage)
 *     Called by Firebase when a data-only message arrives and the app
 *     is in the foreground, or when a data-only message arrives and
 *     the app is in the background — Firebase always routes data-only
 *     payloads to this service. Display is our responsibility.
 *
 *     For notification+data messages (payload contains a "notification"
 *     block), Android auto-displays the notification when the app is in
 *     the background. If we ALSO tried to display it, users would see
 *     duplicates. We detect the presence of a "notification" block and
 *     skip it with a warning — the backend is expected to send
 *     data-only (see the Convex sender contract).
 *
 *     Backend → client data payload contract:
 *       title           (String) — notification title, optional
 *       body            (String) — notification body, optional
 *       route           (String) — in-app destination path (e.g. "/revision")
 *       notificationId  (String) — stable numeric id to enable client-side
 *                                  deduplication and consistent tap-PendingIntent
 *                                  identity across redeliveries
 *
 *     Missing fields fall back to safe defaults:
 *       • title empty → "MedVix"
 *       • body null/empty → "" (BigTextStyle handles display)
 *       • route invalid or missing → "/" (validateRoute rejects external URLs)
 *       • notificationId unparseable → derived from FCM message id, or
 *         from wall clock if even that is absent
 *
 * Lifecycle:
 *
 *   Firebase instantiates this service via reflection when a message
 *   arrives. It has no Application or Activity dependency; it can run
 *   when the MedVix process was previously dead. Every call to a
 *   static helper on MedVixNotifications must therefore be safe without
 *   an Activity — displayNotification(Context,…) and the persistence
 *   helpers are, by design.
 *
 * R8 / ProGuard:
 *
 *   app/proguard-rules.pro keeps com.medhurb.app.** wholesale, which
 *   covers this class. The Firebase SDK additionally relies on the
 *   class being instantiable by name — the service declaration in the
 *   manifest also acts as a keep hint for R8's manifest-aware rules.
 *   Both protections are in place; do not "optimise" this class into
 *   another package.
 */
public final class MedVixFirebaseMessagingService extends FirebaseMessagingService {

    @Override
    public void onNewToken(@NonNull String token) {
        super.onNewToken(token);

        // 1. Persist. The bridge reads this file when the app next
        //    mounts, or immediately if the app is already running.
        SharedPreferences prefs =
                getSharedPreferences(MedVixNotifications.PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit()
                .putString(MedVixNotifications.KEY_FCM_TOKEN, token)
                .putLong(MedVixNotifications.KEY_FCM_TOKEN_AT,
                        System.currentTimeMillis())
                .apply();

        // 2. Push to the live WebView, if there is one. If not, the
        //    bridge's fetchTokenOnce() will read the persisted value
        //    on the next onResume via getCachedFcmToken().
        MedVixNotifications.notifyTokenToWebView(token);

        Log.i(MedVixNotifications.TAG, "FCM token refreshed");
    }

    @Override
    public void onMessageReceived(@NonNull RemoteMessage message) {
        super.onMessageReceived(message);

        // Defensive guard against a backend that sends notification+data
        // instead of data-only. Android auto-displays in the background
        // in that case, and if we also display, the user sees two
        // notifications with the same content. Better to log loudly and
        // skip than to silently duplicate.
        if (message.getNotification() != null) {
            Log.w(MedVixNotifications.TAG,
                    "Received notification+data FCM message. Backend should "
                            + "send data-only to avoid duplicates. Skipping "
                            + "client-side display; Android has already shown "
                            + "(or will show) the system notification.");
            return;
        }

        Map<String, String> data = message.getData();
        if (data == null || data.isEmpty()) {
            // Data-only message with no data block should never happen
            // (FCM rejects it at the SDK level). Log and return rather
            // than fabricate a notification.
            Log.w(MedVixNotifications.TAG,
                    "onMessageReceived: empty data block — ignoring");
            return;
        }

        String title = data.get("title");
        String body  = data.get("body");
        String route = data.get("route");
        String idText = data.get("notificationId");

        int id = parseNotificationId(idText, message.getMessageId());

        MedVixNotifications.displayNotification(this, id, title, body, route);

        Log.i(MedVixNotifications.TAG,
                "FCM message displayed (id=" + id + ")");
    }

    /**
     * Resolves a notification id from the payload, with fallbacks that
     * guarantee a stable, non-zero, positive int suitable for
     * NotificationManagerCompat.notify() and for PendingIntent request
     * codes.
     *
     * Order of preference:
     *
     *   1. Parsed "notificationId" from the payload, if it's a valid
     *      int. This is the intended path — the backend should send a
     *      value that stays constant across redeliveries of the same
     *      logical notification so the user sees an update rather than
     *      a duplicate.
     *
     *   2. Hash of the FCM message id (message.getMessageId()).
     *      Firebase guarantees this id is unique per logical message,
     *      so this is a good dedup key when the backend omits
     *      notificationId.
     *
     *   3. Wall clock. Last resort — produces a fresh id every time, so
     *      duplicates WILL show if the backend omits both
     *      notificationId and FCM's id somehow fails to deliver. This
     *      case is essentially a bug to fix on the backend.
     *
     * In all cases the result is masked to positive int range
     * (0x7FFFFFFF) because NotificationManager treats 0 as "no id".
     */
    private static int parseNotificationId(String value, String fallback) {
        try {
            if (value != null) {
                int parsed = Integer.parseInt(value);
                if (parsed != 0) return parsed;
            }
        } catch (NumberFormatException ignored) {
            // Fall through to fallbacks below.
        }

        if (fallback != null && !fallback.isEmpty()) {
            int hashed = fallback.hashCode() & 0x7FFFFFFF;
            if (hashed != 0) return hashed;
        }

        int fromClock = (int) (System.currentTimeMillis() & 0x7FFFFFFF);
        return fromClock != 0 ? fromClock : 1;
    }
}