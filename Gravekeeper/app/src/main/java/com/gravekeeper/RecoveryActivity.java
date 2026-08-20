package com.gravekeeper;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.LinearLayout;

/** Reliable launcher recovery entry exposed from the Accessibility service details page. */
public final class RecoveryActivity extends Activity {
    @Override protected void onCreate(Bundle state) {
        UiKit.applyPreferredTheme(this);
        super.onCreate(state);
        UiKit ui = new UiKit(this);
        LinearLayout root = ui.pageColumn();
        root.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(ui.heading("恢复 App 入口", "此页面只恢复桌面图标，不会改变保护、白名单或权限"),
                ui.margins(0, 0, 0, 13));
        root.addView(ui.plainTextSurface("如果此前隐藏了桌面入口，可以在这里恢复。App 无法、也不会从系统应用管理、无障碍服务列表、停止或卸载入口中隐藏。"),
                ui.margins(0, 0, 0, 18));
        android.widget.TextView restore = ui.capsule("恢复桌面入口", false, view -> {
            LowVisibilityManager.setLauncherVisible(this, true);
            ui.message("桌面入口已恢复");
            startActivity(new Intent(this, MainActivity.class));
            finish();
        });
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, -2);
        params.gravity = Gravity.CENTER_HORIZONTAL;
        root.addView(restore, params);
        setContentView(ui.scroll(root));
    }
}
