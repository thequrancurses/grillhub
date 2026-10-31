package pl.grillhub.kitchen;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Starts the order listener again after the tablet reboots or the app is updated. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        try {
            context.startForegroundService(new Intent(context, OrderService.class));
        } catch (Exception e) { /* ignore */ }
    }
}
