package com.gravekeeper;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;
import android.view.accessibility.AccessibilityManager;

import java.util.List;

/** Single source of truth for this app's accessibility capability. */
public final class AccessibilityCapability {
    private AccessibilityCapability() {}

    public static boolean isEnabled(Context context) {
        AccessibilityManager manager = (AccessibilityManager) context.getSystemService(Context.ACCESSIBILITY_SERVICE);
        if (manager == null) return false;
        List<AccessibilityServiceInfo> services = manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
        String expectedPackage = context.getPackageName();
        String expectedName = GuardAccessibilityService.class.getName();
        for (AccessibilityServiceInfo service : services) {
            if (service.getResolveInfo() == null || service.getResolveInfo().serviceInfo == null) continue;
            if (expectedPackage.equals(service.getResolveInfo().serviceInfo.packageName)
                    && expectedName.equals(service.getResolveInfo().serviceInfo.name)) return true;
        }
        return false;
    }

    public static void openSettings(Context context) {
        Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
        if (!(context instanceof Activity)) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }

    public static String status(Context context) {
        return isEnabled(context) ? "无障碍权限已开启" : "等待开启无障碍权限";
    }
}
