package ir.delneveshte.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Explicit, non-exported alarm target; no receiver action can supply an unapproved SMS. */
public final class AlarmReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        PendingResult pending = goAsync();
        Context app = context.getApplicationContext();
        new Thread(() -> {
            try { SmsRuntime.onAlarm(app); }
            finally { pending.finish(); }
        }, "delneveshte-alarm").start();
    }
}
