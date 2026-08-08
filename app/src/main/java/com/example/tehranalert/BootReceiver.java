package com.example.tehranalert;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction()) && SourceStore.isMonitoring(context)) {
            Intent service = new Intent(context, AlertService.class).setAction(AlertService.ACTION_START);
            context.startForegroundService(service);
        }
    }
}
