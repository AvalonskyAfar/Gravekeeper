package com.gravekeeper;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;

/** Controls only the documented low-visibility surfaces: launcher and recents. */
public final class LowVisibilityManager {
    private LowVisibilityManager() {}

    public static void setLauncherVisible(Context context, boolean visible) {
        ComponentName launcher = new ComponentName(context.getPackageName(),
                context.getPackageName() + ".Launcher");
        context.getPackageManager().setComponentEnabledSetting(launcher,
                visible ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                        : PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP);
        new AppPreferences(context).setHideLauncher(!visible);
    }

    public static boolean isLauncherVisible(Context context) {
        ComponentName launcher = new ComponentName(context.getPackageName(),
                context.getPackageName() + ".Launcher");
        int state = context.getPackageManager().getComponentEnabledSetting(launcher);
        return state != PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
    }

    public static void applyRecents(Activity activity, boolean hidden) {
        ActivityManager manager = (ActivityManager) activity.getSystemService(Context.ACTIVITY_SERVICE);
        for (ActivityManager.AppTask task : manager.getAppTasks()) {
            try { task.setExcludeFromRecents(hidden); } catch (RuntimeException ignored) { }
        }
        new AppPreferences(activity).setHideRecents(hidden);
    }

    /**
     * Hides future entries and removes only this application's current Android tasks.
     * The accessibility service is deliberately not stopped: Android task removal and
     * service lifetime are separate system concepts.
     */
    public static void hideAndRemoveOwnTasks(Activity activity) {
        new AppPreferences(activity).setHideRecents(true);
        ActivityManager manager =
                (ActivityManager) activity.getSystemService(Context.ACTIVITY_SERVICE);
        boolean requested = false;
        for (ActivityManager.AppTask task : manager.getAppTasks()) {
            try {
                task.setExcludeFromRecents(true);
                task.finishAndRemoveTask();
                requested = true;
            } catch (RuntimeException ignored) { }
        }
        if (!requested && !activity.isFinishing()) {
            activity.finishAndRemoveTask();
        }
    }
}
