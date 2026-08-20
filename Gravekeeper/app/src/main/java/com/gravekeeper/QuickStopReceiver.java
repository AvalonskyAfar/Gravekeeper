package com.gravekeeper;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.gravekeeper.config.ConfigStore;

import java.io.IOException;

public final class QuickStopReceiver extends BroadcastReceiver {
    public static final String ACTION_QUICK_STOP = "guard.QUICK_STOP";

    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null || !ACTION_QUICK_STOP.equals(intent.getAction())) return;
        try {
            new ConfigStore(context).setProtectionEnabled(false);
        } catch (IOException error) {
            // The accessibility service owns local status reporting. Avoid an
            // unconditional technical-state write when persistence is disabled.
        }
    }
}
