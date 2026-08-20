package com.gravekeeper;

import android.app.Activity;
import android.annotation.SuppressLint;
import android.os.Build;

/** Framework predictive-back bridge without adding a UI framework dependency. */
final class BackNavigation {
    private BackNavigation() {}

    static void register(Activity activity, Runnable action) {
        if (Build.VERSION.SDK_INT >= 33) Api33.register(activity, action);
    }

    @SuppressLint("NewApi")
    private static final class Api33 {
        private Api33() {}

        static void register(Activity activity, Runnable action) {
            activity.getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                    action::run);
        }
    }
}
