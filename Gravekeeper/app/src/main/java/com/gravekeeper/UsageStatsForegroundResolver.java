package com.gravekeeper;

import android.app.AppOpsManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.os.Process;

public final class UsageStatsForegroundResolver {
    public static final class Result {
        public final String packageName;
        public final long timestampMs;

        Result(String packageName, long timestampMs) {
            this.packageName = packageName == null ? "" : packageName;
            this.timestampMs = timestampMs;
        }
    }

    private static String cachedPackage = "";
    private static long cachedEventAt;
    private static long lastQueryWallTime;

    private UsageStatsForegroundResolver() {}

    public static boolean hasAccess(Context context) {
        AppOpsManager ops = (AppOpsManager) context.getSystemService(Context.APP_OPS_SERVICE);
        int mode = ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(), context.getPackageName());
        return mode == AppOpsManager.MODE_ALLOWED;
    }

    public static synchronized Result resolve(Context context, long initialLookbackMs) {
        if (!hasAccess(context)) return new Result("", 0L);
        UsageStatsManager manager = (UsageStatsManager) context.getSystemService(
                Context.USAGE_STATS_SERVICE);
        long end = System.currentTimeMillis();
        long start = lastQueryWallTime == 0L
                ? Math.max(0L, end - initialLookbackMs)
                : Math.max(0L, lastQueryWallTime - 2000L);
        UsageEvents events = manager.queryEvents(start, end);
        UsageEvents.Event event = new UsageEvents.Event();
        while (events.hasNextEvent()) {
            events.getNextEvent(event);
            int type = event.getEventType();
            if ((type == UsageEvents.Event.ACTIVITY_RESUMED
                    || type == UsageEvents.Event.MOVE_TO_FOREGROUND)
                    && event.getTimeStamp() >= cachedEventAt
                    && event.getPackageName() != null) {
                cachedEventAt = event.getTimeStamp();
                cachedPackage = event.getPackageName();
            }
        }
        lastQueryWallTime = end;
        return new Result(cachedPackage, cachedEventAt);
    }
}
