package com.medhurb.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Fires when an alarm scheduled via MedVixNotifications.schedule() /
 * MedVixNotifications.scheduleAlarm() elapses.
 *
 * Responsibilities:
 *
 *   1. Display the notification on the medvix_general channel using the
 *      title/body/route carried in the alarm's Intent extras. Delegates
 *      to MedVixNotifications.displayNotification() so the tap-intent
 *      wiring, route validation, and channel handling stay identical to
 *      the immediate-show and FCM paths.
 *
 *   2. Remove the fired reminder from persisted storage. Without this,
 *      a subsequent BOOT_COMPLETED would re-schedule an alarm for a
 *      notification the user has already seen — MedVixBootReceiver only
 *      prunes reminders whose trigger time has passed, but there is a
 *      window between "alarm fired" and "reboot happened" where the
 *      stale record would still be eligible for re-fire.
 *
 * Fails safely:
 *
 *   • If the Intent is null or missing the id extra, it logs and returns
 *     — we never fabricate a notification.
 *   • displayNotification() is itself guarded against POST_NOTIFICATIONS
 *     being revoked between scheduling and firing, and against the
 *     channel being user-disabled. No extra checks needed here.
 */
public final class MedVixNotificationReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;

        int id = intent.getIntExtra("id", 0);
        if (id == 0) {
            // schedule() rejects id=0 as a sanity check (see bridge), so
            // id=0 here means the intent came from somewhere unexpected.
            Log.w(MedVixNotifications.TAG,
                    "MedVixNotificationReceiver got intent with id=0 — ignoring");
            return;
        }

        String title = intent.getStringExtra("title");
        String body  = intent.getStringExtra("body");
        String route = intent.getStringExtra("route");

        MedVixNotifications.displayNotification(context, id, title, body, route);

        // Consume the reminder: once fired, it must not be re-scheduled
        // on the next reboot.
        MedVixNotifications.removePersistedReminder(context, id);

        Log.i(MedVixNotifications.TAG,
                "Fired scheduled reminder id=" + id);
    }
}