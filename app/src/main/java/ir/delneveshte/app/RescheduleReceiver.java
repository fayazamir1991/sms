package ir.delneveshte.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Re-registers inexact alarms after boot or clock changes; never directly sends a text. */
public final class RescheduleReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)
                && !Intent.ACTION_TIME_CHANGED.equals(action)
                && !Intent.ACTION_TIMEZONE_CHANGED.equals(action)) return;
        PendingResult pending = goAsync();
        Context app = context.getApplicationContext();
        new Thread(() -> {
            try {
                if (Intent.ACTION_TIME_CHANGED.equals(action)
                        || Intent.ACTION_TIMEZONE_CHANGED.equals(action)) {
                    new AppStore(app).requeueFuture();
                }
                SmsRuntime.reconcile(app);
            } finally { pending.finish(); }
        }, "delneveshte-restore").start();
    }
}
