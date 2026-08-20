package com.gravekeeper;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class LiveStatusNotificationCompatTest {
    @Test public void compactTextUsesStableShortStates() {
        assertEquals("已放行", LiveStatusNotificationCompat.compactText(
                "白名单账号已放行，等待内容切换"));
        assertEquals("有风险", LiveStatusNotificationCompat.compactText(
                "直播风险 82%"));
        assertEquals("已暂停", LiveStatusNotificationCompat.compactText(
                "设备温度较高，已暂停检测"));
        assertEquals("保护中", LiveStatusNotificationCompat.compactText(
                "正在保护：抖音系列"));
    }
}
