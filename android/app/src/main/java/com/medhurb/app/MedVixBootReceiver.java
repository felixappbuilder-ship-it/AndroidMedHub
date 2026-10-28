package com.medhurb.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * Restores scheduled reminders after a device reboot or an in-place
 * Play Store update.
 *
 * Why both actions:
 *
 *   • ACTION_BOOT_COMPLETED — a device power cycle wipes AlarmManager.
 *     Every pending alarm is gone. Without this receiver, the user's
 *     reminders silently vanish until they next open the app.
 *
 *   • ACTION_MY_PACKAGE_REPLACED — a Play Store in-place update kills
 *     the app process and clears its alarm registrations the same way
 *     a reboot does. This action fires immediately after the update is
 *     applied, which is the app's only chance to re-arm the alarms
 *     before the user notices they're missing.
 *
 * Policy:
 *
 *   • Reminders whose fire time is still in the future are re-scheduled
 *     verbatim via MedVixNotifications.scheduleAlarm() — the exact same
 *     helper the JS bridge uses, so the alarm Intent format and PendingIntent
 *     request codes always match. This is what makes cancellation by id
 *     work consistently across "scheduled today" and "restored after
 *     reboot".
 *
 *   • Reminders whose fire time already passed while the device was off
 *     are DROPPED, not fired. A "Time to study!" reminder that was
 *     meant for Tuesday morning should not appear Thursday evening
 *     just because the phone was dead. Firing stale notifications is
 *     worse than dropping them.
 *
 *   • The persisted list is rewritten to contain only the survivors,
 *     so a second reboot doesn't re-examine reminders we've already
 *     determined are past-due.
 *
 * Safety:
 *
 *   • The receiver is exported="false" in the manifest, but both
 *     BOOT_COMPLETED and MY_PACKAGE_REPLACED are protected system
 *     broadcasts — the OS delivers them to non-exported receivers
 *     because they originate from the system UID.
 *
 *   • RECEIVE_BOOT_COMPLETED must be declared in the manifest without
 *     tools:node="remove" — which is why we un-removed it during
 *     Checkpoint 2. Verify it survives in the merged manifest if
 *     reminders stop being restored on a real device.
 */
public final class MedVixBootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;

        String action = intent.getAction();
        boolean isBoot     = Intent.ACTION_BOOT_COMPLETED.equals(action);
        boolean isReplaced = Intent.ACTION_MY_PACKAGE_REPLACED.equals(action);

        if (!isBoot && !isReplaced) {
            // Some other broadcast landed here — refuse to act. The
            // manifest's intent-filter only declares these two, but a
            // manifest merger or a future edit could deliver others.
            return;
        }

        List<MedVixNotifications.Reminder> persisted =
                MedVixNotifications.loadPersistedReminders(context);

        long now = System.currentTimeMillis();
        List<MedVixNotifications.Reminder> kept = new ArrayList<>();
        int restored = 0;
        int dropped  = 0;

        for (MedVixNotifications.Reminder r : persisted) {
            if (r.triggerAtMillis > now) {
                MedVixNotifications.scheduleAlarm(context, r);
                kept.add(r);
                restored++;
            } else {
                // Past-due during downtime — silently drop.
                dropped++;
            }
        }

        // Rewrite the persisted list to contain only survivors. This is
        // a full overwrite, not an incremental update, because we've
        // just classified every record and any incremental path would
        // race with an Activity that's also writing (the Activity cannot
        // be alive during BOOT_COMPLETED, but MY_PACKAGE_REPLACED can
        // race the first cold launch after the update).
        MedVixNotifications.clearPersistedReminders(context);
        for (MedVixNotifications.Reminder r : kept) {
            MedVixNotifications.savePersistedReminder(context, r);
        }

        Log.i(MedVixNotifications.TAG,
                "Boot restore (" + action + "): "
                        + restored + " restored, " + dropped + " dropped");
    }
}