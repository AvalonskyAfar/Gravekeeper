package com.gravekeeper;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Build;

/** Public-API compatibility layer for prominent ongoing status notifications. */
final class LiveStatusNotificationCompat {
    // Public Android 16 Notification.EXTRA_REQUEST_PROMOTED_ONGOING value.
    // The installed API 36 SDK exposes the capability but not this field constant.
    private static final String EXTRA_REQUEST_PROMOTED_ONGOING =
            "android.requestPromotedOngoing";

    private LiveStatusNotificationCompat() { }

    static Notification build(Notification.Builder builder, String detail,
            boolean requestPromotedOngoing) {
        builder.setOnlyAlertOnce(true)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setShowWhen(false)
                .setStyle(new Notification.BigTextStyle().bigText(detail));
        if (Build.VERSION.SDK_INT >= 36 && requestPromotedOngoing) {
            requestPromotedOngoing(builder, detail);
        }
        return builder.build();
    }

    @SuppressLint("NewApi")
    private static void requestPromotedOngoing(
            Notification.Builder builder, String detail) {
        builder.getExtras().putBoolean(EXTRA_REQUEST_PROMOTED_ONGOING, true);
        builder.setShortCriticalText(compactText(detail));
    }

    static String capabilitySummary(Context context, boolean enabled) {
        if (!enabled) return "默认关闭";
        if (Build.VERSION.SDK_INT < 36) {
            return "已启用普通持续通知；系统低于 Android 16";
        }
        return canPostPromoted(context)
                ? "Android 16 实时更新可用（厂商仍可附加条件）"
                : "系统未授予实时更新展示资格，已退化为普通通知";
    }

    @SuppressLint("NewApi")
    private static boolean canPostPromoted(Context context) {
        NotificationManager manager = (NotificationManager) context.getSystemService(
                Context.NOTIFICATION_SERVICE);
        return manager != null && manager.canPostPromotedNotifications();
    }

    static String compactText(String detail) {
        String value = detail == null ? "" : detail;
        if (value.contains("白名单") || value.contains("放行")) return "已放行";
        if (value.contains("风险")) return "有风险";
        if (value.contains("暂停") || value.contains("关闭")) return "已暂停";
        return "保护中";
    }
}
