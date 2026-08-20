package com.gravekeeper;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Dialog;
import android.app.NotificationManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.SparseArray;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.gravekeeper.config.ConfigStore;
import com.gravekeeper.config.GuardConfig;
import com.gravekeeper.performance.PerformanceProbe;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.IdentityHashMap;
import java.util.Locale;

/** Formal native shell for the approved main, settings, tutorial and more pages. */
public final class MainActivity extends Activity {
    public static final String EXTRA_OPEN_TUTORIAL =
            "com.gravekeeper.extra.OPEN_TUTORIAL";
    // Neighbor-page warmup pacing. After an animated settle we wait a short while so
    // the page's first content frame and the user's first touch on it are not
    // competing with a hidden page build; the ACTION_DOWN cancel then protects any
    // scroll that begins earlier. On a plain swap (replace, includes cold start) the
    // host is already idle, so neighbors build on the next available frame instead of
    // waiting — this is what removes the "jank on first drag right after entering"
    // symptom, because by the time the user touches, the neighbor is already cached.
    private static final long NEIGHBOR_WARMUP_SETTLE_DELAY_MS = 250L;
    private static final long NEIGHBOR_WARMUP_STEP_MS = 120L;

    enum Page {
        MAIN, SETTINGS, ADVANCED, WHITELIST, TUTORIAL, TUTORIAL_CHILD,
        MORE, MORE_DETAIL, PERFORMANCE
    }

    private enum MoreDetail {
        ABOUT, PRIVACY, OPEN_SOURCE, FAQ
    }

    private static final class MoreEntry {
        final MoreDetail detail;
        final String title;
        final String summary;

        MoreEntry(MoreDetail detail, String title, String summary) {
            this.detail = detail;
            this.title = title;
            this.summary = summary;
        }
    }

    private static final String[] OFF_ON = {"关闭", "开启"};
    private static final String[] MEDIA_LEVELS = {"关闭", "提醒", "划走"};
    private static final String[] STRENGTH_LEVELS = {"关闭", "轻度", "标准", "严格"};
    private static final int REQUEST_NOTIFICATION_PERMISSION = 7001;

    private UiKit ui;
    private ConfigStore store;
    private GestureHost host;
    private Page page = Page.MAIN;
    private MoreDetail detailId;
    private String detailTitle;
    private String detailCopy;
    private String tutorialChild;
    private final EnumMap<Page, View> pageCache = new EnumMap<>(Page.class);
    private final IdentityHashMap<View, Page> pageIdentities = new IdentityHashMap<>();
    private final SparseArray<Drawable.ConstantState> tutorialIllustrationStates =
            new SparseArray<>();
    private UiKit.PowerSwitch mainPower;
    private TextView mainProtectionStatus;
    private boolean resumedAfterCreate;
    private boolean themeTransitionPending;
    private Runnable pendingNotificationAction;
    private UiKit.SegmentControl pendingNotificationControl;
    private boolean waitingForNotificationSettings;

    @Override protected void onCreate(Bundle state) {
        UiKit.applyPreferredTheme(this);
        super.onCreate(state);
        AppPreferences preferences = new AppPreferences(this);
        if (!preferences.consented()) {
            startActivity(new Intent(this, FirstLaunchActivity.class));
            finish();
            return;
        }
        store = new ConfigStore(this);
        ui = new UiKit(this);
        preloadTutorialIllustrations();
        host = new GestureHost();
        ui.applySystemInsets(host);
        setContentView(host);
        LowVisibilityManager.applyRecents(this, ui.prefs.hideRecents());
        if (state != null) {
            try { page = Page.valueOf(state.getString("page", Page.MAIN.name())); }
            catch (IllegalArgumentException ignored) { page = Page.MAIN; }
            detailTitle = state.getString("detail_title");
            detailCopy = state.getString("detail_copy");
            try {
                String savedDetailId = state.getString("detail_id");
                detailId = savedDetailId == null ? null : MoreDetail.valueOf(savedDetailId);
            } catch (IllegalArgumentException ignored) {
                detailId = null;
            }
            tutorialChild = state.getString("tutorial_child");
        } else if (getIntent().getBooleanExtra(EXTRA_OPEN_TUTORIAL, false)) {
            page = Page.TUTORIAL;
        }
        show(page, 0, false);
        UiKit.playPreparedThemeTransition(this, host);
        boolean firstPermissionPrompt = getIntent().getBooleanExtra(EXTRA_OPEN_TUTORIAL, false)
                || !getSharedPreferences("guard_consent", MODE_PRIVATE)
                .getBoolean("accessibility_prompt_completed", false);
        if (firstPermissionPrompt && !AccessibilityCapability.isEnabled(this)) {
            host.postDelayed(this::showAccessibilityPrompt, 180);
        }
        BackNavigation.register(this, this::handleBack);
    }

    @Override protected void onResume() {
        super.onResume();
        if (host == null || store == null) return;
        enforceAccessibilityState();
        if (resumedAfterCreate && page == Page.TUTORIAL_CHILD
                && "无障碍权限".equals(tutorialChild)
                && !host.isTransitioning()) {
            host.replace(renderIdentified(page));
        }
        if (AccessibilityCapability.isEnabled(this)) {
            getSharedPreferences("guard_consent", MODE_PRIVATE).edit()
                    .putBoolean("accessibility_prompt_completed", true).apply();
        }
        if (waitingForNotificationSettings) {
            waitingForNotificationSettings = false;
            if (canPostNotifications()) {
                completePendingNotificationAction();
            } else {
                cancelPendingNotificationAction();
                ui.message("通知权限未开启，相关设置保持关闭");
            }
        }
        resumedAfterCreate = true;
    }

    private void showAccessibilityPrompt() {
        if (isFinishing() || AccessibilityCapability.isEnabled(this)) return;
        getSharedPreferences("guard_consent", MODE_PRIVATE).edit()
                .putBoolean("accessibility_prompt_shown", true).apply();
        ui.confirm("需要无障碍权限",
                "未开启无障碍权限时，保护服务无法读取屏幕或执行保护动作。请先在系统设置中开启守目人。",
                "打开设置", false, () -> AccessibilityCapability.openSettings(this));
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        if (host != null) host.stabilizeForNavigation();
        state.putString("page", page.name());
        state.putString("detail_id", detailId == null ? null : detailId.name());
        state.putString("detail_title", detailTitle);
        state.putString("detail_copy", detailCopy);
        state.putString("tutorial_child", tutorialChild);
        super.onSaveInstanceState(state);
    }

    private View render(Page destination) {
        try {
            switch (destination) {
                case MAIN: return mainPage();
                case SETTINGS: return normalSettingsPage();
                case ADVANCED: return advancedSettingsPage();
                case WHITELIST: return new WhitelistAccountsPage(this, store, ui).build();
                case TUTORIAL: return tutorialPage();
                case TUTORIAL_CHILD: return tutorialChildPage();
                case MORE: return morePage();
                case MORE_DETAIL: return detailPage();
                case PERFORMANCE: return performancePage();
                default: throw new IllegalStateException("未知页面");
            }
        } catch (IOException | JSONException error) {
            LinearLayout root = ui.pageColumn();
            root.addView(ui.pageTitle("无法读取设置"), ui.margins(0, 0, 0, 16));
            root.addView(ui.plainTextSurface("本机配置未能通过校验。应用不会在配置不明确时继续写入。\n\n"
                    + safeMessage(error)), ui.matchWrap());
            return root;
        }
    }

    private View obtainPage(Page destination) {
        View cached = pageCache.remove(destination);
        if (cached != null && cached.getParent() == null
                && pageIdentities.get(cached) == destination) {
            cached.setTranslationX(0);
            cached.setTranslationY(0);
            return cached;
        }
        if (cached != null && cached.getParent() == null) pageIdentities.remove(cached);
        return renderIdentified(destination);
    }

    private View renderIdentified(Page destination) {
        View rendered = render(destination);
        pageIdentities.put(rendered, destination);
        return rendered;
    }

    private void cachePage(Page destination, View view) {
        if (view == null || view.getParent() != null) return;
        if (destination == null || pageIdentities.get(view) != destination) {
            pageIdentities.remove(view);
            return;
        }
        if (destination != Page.MAIN && destination != Page.SETTINGS
                && destination != Page.ADVANCED && destination != Page.MORE) {
            pageIdentities.remove(view);
            return;
        }
        view.setTranslationX(0);
        view.setTranslationY(0);
        pageCache.put(destination, view);
    }

    private void invalidatePageCache() {
        for (View cached : pageCache.values()) {
            if (cached != null && cached.getParent() == null) pageIdentities.remove(cached);
        }
        pageCache.clear();
    }

    private View mainPage() throws IOException, JSONException {
        GuardConfig config = store.load();
        boolean accessibilityReady = AccessibilityCapability.isEnabled(this);
        boolean protectionRunning = config.protectionEnabled && accessibilityReady;
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(ui.p.bg);

        LinearLayout centre = ui.column();
        centre.setGravity(Gravity.CENTER);
        centre.setClipChildren(false);
        centre.setClipToPadding(false);
        centre.setPadding(ui.dp(24), ui.dp(48), ui.dp(24), ui.dp(86));
        TextView label = ui.text("保护总开关", 14, ui.p.muted, true);
        label.setGravity(Gravity.CENTER);
        centre.addView(label, ui.margins(0, 0, 0, 15));
        TextView status = ui.text(protectionRunning ? "保护已开启"
                : accessibilityReady ? "保护已关闭" : "请先开启无障碍权限",
                13, ui.p.muted, false);
        status.setGravity(Gravity.CENTER);
        final UiKit.PowerSwitch[] powerHolder = new UiKit.PowerSwitch[1];
        UiKit.PowerSwitch power = ui.powerSwitch(protectionRunning,
                value -> saveProtection(value, powerHolder[0], status));
        powerHolder[0] = power;
        mainPower = power;
        mainProtectionStatus = status;
        // UI-001 remains an 80dp capsule; the larger view only reserves room for its soft shadow.
        centre.addView(power, new LinearLayout.LayoutParams(-1, ui.dp(92)));
        centre.addView(status, ui.margins(0, 2, 0, 0));
        root.addView(centre, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        nav.setGravity(Gravity.CENTER);
        nav.addView(navItem(MainNavItem.BOOK, "教程", () -> show(Page.TUTORIAL, 1, true)),
                new LinearLayout.LayoutParams(0, ui.dp(72), 1));
        nav.addView(navItem(MainNavItem.SLIDERS, "设置", () -> show(Page.SETTINGS, -2, true)),
                new LinearLayout.LayoutParams(0, ui.dp(72), 1));
        nav.addView(navItem(MainNavItem.MORE, "更多", () -> show(Page.MORE, -1, true)),
                new LinearLayout.LayoutParams(0, ui.dp(72), 1));
        FrameLayout.LayoutParams navParams =
                new FrameLayout.LayoutParams(-1, ui.dp(72), Gravity.BOTTOM);
        navParams.leftMargin = ui.dp(18);
        navParams.rightMargin = ui.dp(18);
        root.addView(nav, navParams);
        return root;
    }

    private View navItem(int icon, String label, Runnable action) {
        MainNavItem item = new MainNavItem(icon, label);
        ui.attachPress(item, item, ignored -> action.run());
        return item;
    }

    @SuppressLint("ViewConstructor")
    private final class MainNavItem extends View {
        static final int BOOK = 0;
        static final int SLIDERS = 1;
        static final int MORE = 2;

        private final int icon;
        private final String label;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final RectF oval = new RectF();

        MainNavItem(int icon, String label) {
            super(MainActivity.this);
            this.icon = icon;
            this.label = label;
            setClickable(true);
            setFocusable(true);
            setBackgroundColor(Color.TRANSPARENT);
            setContentDescription(label);
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float cx = getWidth() / 2f;
            float unit = ui.dp(1);
            paint.setColor(ui.p.muted);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(ui.dp(1.7f));
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            if (icon == BOOK) drawBook(canvas, cx, unit);
            else if (icon == SLIDERS) drawSliders(canvas, cx, unit);
            else drawMore(canvas, cx, unit);

            paint.setStyle(Paint.Style.FILL);
            paint.setTypeface(Typeface.create("sans", Typeface.BOLD));
            float scale = ui.prefs.largeText() ? 1.13f : 1f;
            paint.setTextSize(getResources().getDisplayMetrics().scaledDensity * 12.5f * scale);
            paint.setTextAlign(Paint.Align.CENTER);
            canvas.drawText(label, cx, ui.dp(57), paint);
        }

        private void drawBook(Canvas canvas, float cx, float unit) {
            float top = ui.dp(11);
            float bottom = ui.dp(31);
            float half = ui.dp(12);
            path.reset();
            path.moveTo(cx, top + unit * 3);
            path.cubicTo(cx - unit * 4, top, cx - half, top, cx - half, top);
            path.lineTo(cx - half, bottom - unit * 3);
            path.cubicTo(cx - unit * 6, bottom - unit * 4, cx - unit * 2, bottom - unit, cx, bottom);
            path.close();
            canvas.drawPath(path, paint);
            path.reset();
            path.moveTo(cx, top + unit * 3);
            path.cubicTo(cx + unit * 4, top, cx + half, top, cx + half, top);
            path.lineTo(cx + half, bottom - unit * 3);
            path.cubicTo(cx + unit * 6, bottom - unit * 4, cx + unit * 2, bottom - unit, cx, bottom);
            path.close();
            canvas.drawPath(path, paint);
            canvas.drawLine(cx, top + unit * 3, cx, bottom, paint);
        }

        private void drawSliders(Canvas canvas, float cx, float unit) {
            float left = cx - ui.dp(12);
            float right = cx + ui.dp(12);
            float[] ys = {ui.dp(13), ui.dp(21), ui.dp(29)};
            float[] knobs = {cx - ui.dp(5), cx + ui.dp(6), cx - ui.dp(1)};
            for (int i = 0; i < ys.length; i++) {
                canvas.drawLine(left, ys[i], right, ys[i], paint);
                paint.setStyle(Paint.Style.FILL);
                oval.set(knobs[i] - ui.dp(2.4f), ys[i] - ui.dp(2.4f),
                        knobs[i] + ui.dp(2.4f), ys[i] + ui.dp(2.4f));
                canvas.drawOval(oval, paint);
                paint.setStyle(Paint.Style.STROKE);
            }
        }

        private void drawMore(Canvas canvas, float cx, float unit) {
            canvas.drawCircle(cx, ui.dp(21), ui.dp(12), paint);
            paint.setStyle(Paint.Style.FILL);
            for (int i = -1; i <= 1; i++) {
                canvas.drawCircle(cx + i * ui.dp(5), ui.dp(21), ui.dp(1.35f), paint);
            }
        }
    }

    private View normalSettingsPage() throws IOException, JSONException {
        JSONObject json = store.loadJson();
        JSONObject defaults = store.defaultJson();
        LinearLayout root = ui.pageColumn();

        addHeading(root, "保护强度", "强度越高对疑似营销内容越敏感；严格模式可能误伤正常内容");
        LinearLayout strength = ui.surface();
        JSONArray platforms = json.getJSONArray("platforms");
        for (int i = 0; i < platforms.length(); i++) {
            JSONObject platform = platforms.getJSONObject(i);
            JSONObject base = platformById(defaults, platform.getString("id"));
            int selected = strengthLevel(platform, base);
            String id = platform.getString("id");
            String name = shortPlatformName(platform.optString("name"));
            strength.addView(ui.controlRow(name, null, STRENGTH_LEVELS, selected,
                    value -> updatePlatformStrength(id, value)));
        }
        root.addView(strength, ui.margins(0, 0, 0, 22));

        addHeading(root, "内容类型策略", "对风险内容采用的动作");
        LinearLayout content = ui.surface();
        addSharedMediaRow(content, json, "短视频", "short_video");
        addSharedMediaRow(content, json, "直播", "live");
        content.addView(ui.divider(), fullDivider());
        content.addView(ui.entry("白名单账户", "管理不会触发保护的直播账户名称", "←",
                ignored -> show(Page.WHITELIST, 1, true)));
        root.addView(content, ui.margins(0, 0, 0, 22));

        addHeading(root, "状态通知", "在系统通知栏显示保护是否正在工作");
        LinearLayout statusSurface = ui.surface();
        boolean notifications = json.optBoolean("status_notification_enabled", false);
        final UiKit.SegmentControl[] statusNotificationControl = {null};
        LinearLayout statusNotificationRow = ui.controlRow(
                "允许常驻状态通知", "开启后状态栏常驻显示保护运行图标", OFF_ON,
                notifications ? 1 : 0,
                value -> updateStatusNotification(value == 1,
                        statusNotificationControl[0]));
        statusNotificationControl[0] =
                (UiKit.SegmentControl) statusNotificationRow.getChildAt(1);
        statusSurface.addView(statusNotificationRow);
        root.addView(statusSurface, ui.margins(0, 0, 0, 24));

        TextView restore = ui.capsule("恢复全部默认值", false, ignored ->
                ui.confirm("恢复全部默认值？",
                        "保护总开关、正常设置、高级设置和开发者精确值都会恢复。白名单账户也会清空。",
                        "恢复", true, () -> {
                            store.resetToDefaults();
                            WhitelistAccountsPage.clearUiState(this);
                            invalidatePageCache();
                            ui.message("已恢复全部默认值");
                            host.replace(renderIdentified(Page.SETTINGS));
                        }));
        root.addView(restore, ui.margins(30, 0, 30, 28));

        FrameLayout advanced = ui.pageLinkAdjacent("高级设置",
                ignored -> show(Page.ADVANCED, -1, true));
        root.addView(advanced, ui.matchWrap());
        return ui.scroll(root);
    }

    private void addSharedMediaRow(LinearLayout surface, JSONObject json, String title, String key)
            throws JSONException {
        JSONArray platforms = json.getJSONArray("platforms");
        int current = mediaLevel(platforms.getJSONObject(0).getJSONObject(key));
        boolean conflict = false;
        for (int i = 1; i < platforms.length(); i++) {
            if (mediaLevel(platforms.getJSONObject(i).getJSONObject(key)) != current) {
                conflict = true;
                break;
            }
        }
        final boolean hasConflict = conflict;
        final UiKit.SegmentControl[] holder = new UiKit.SegmentControl[1];
        LinearLayout row = ui.controlRow(title, null, MEDIA_LEVELS, current, value -> {
            if (!hasConflict) {
                updateSharedMedia(key, value);
                return;
            }
            final boolean[] confirmed = {false};
            Dialog dialog = ui.confirm("覆盖平台细分设置？",
                    "此项会让抖音和快手的" + title
                            + "改为同一策略。取消后保留高级设置中的细分值。",
                    "统一设置", false, () -> {
                        confirmed[0] = true;
                        updateSharedMedia(key, value);
                    });
            dialog.setOnDismissListener(ignored -> {
                if (!confirmed[0] && holder[0] != null) {
                    holder[0].setSelected(current, true);
                }
            });
        });
        holder[0] = (UiKit.SegmentControl) row.getChildAt(1);
        surface.addView(row);
    }

    private View advancedSettingsPage() throws IOException, JSONException {
        JSONObject json = store.loadJson();
        LinearLayout root = ui.pageColumn();
        root.addView(ui.pageTitle("高级设置"), ui.margins(0, 0, 0, 16));

        addHeading(root, "内容类型策略", "分别设置抖音和快手的短视频与直播");
        LinearLayout policies = ui.surface();
        JSONArray platforms = json.getJSONArray("platforms");
        for (int i = 0; i < platforms.length(); i++) {
            JSONObject platform = platforms.getJSONObject(i);
            String id = platform.getString("id");
            TextView platformName = ui.text(shortPlatformName(platform.optString("name")),
                    14.5f, ui.p.ink, true);
            platformName.setGravity(Gravity.CENTER);
            policies.addView(platformName, ui.margins(0, 7, 0, 5));
            for (String key : new String[] {"short_video", "live"}) {
                String name = "short_video".equals(key) ? "短视频" : "直播";
                int level = mediaLevel(platform.getJSONObject(key));
                policies.addView(ui.controlRow(name, null, MEDIA_LEVELS, level,
                        value -> updatePlatformMedia(id, key, value)));
            }
            if (i < platforms.length() - 1) {
                policies.addView(ui.divider(), fullDivider());
            }
        }
        policies.addView(ui.divider(), fullDivider());
        JSONObject signals = json.getJSONObject("signals");
        policies.addView(ui.controlRow("全球购风险增强", null, OFF_ON,
                signals.optBoolean("global_purchase_standalone_enabled", true) ? 1 : 0,
                value -> mutate(candidate -> candidate.getJSONObject("signals")
                        .put("global_purchase_standalone_enabled", value == 1))));
        policies.addView(ui.controlRow("负向语境保护", "降低科普、曝光和劝阻购买内容的误判", OFF_ON,
                Math.abs(signals.optDouble("negative_context_bias", 0)) > 0.001 ? 1 : 0,
                value -> updateNegativeContext(value == 1)));
        policies.addView(ui.controlRow("组合规则", "综合多个特征判断，减少误判", OFF_ON,
                allRulesEnabled(signals.optJSONArray("runtime_rules")) ? 1 : 0,
                value -> updateAllRules(value == 1)));
        root.addView(policies, ui.margins(0, 0, 0, 22));

        addHeading(root, "通知选项", "管理通知栏中的快捷操作和系统增强功能");
        LinearLayout notification = ui.surface();
        final UiKit.SegmentControl[] quickStopControl = {null};
        LinearLayout quickStopRow = ui.controlRow(
                "通知快捷停止", "在通知中显示停止保护操作", OFF_ON,
                json.optBoolean("notification_quick_stop", false) ? 1 : 0,
                value -> updateQuickStop(value == 1, quickStopControl[0]));
        quickStopControl[0] = (UiKit.SegmentControl) quickStopRow.getChildAt(1);
        notification.addView(quickStopRow);
        final UiKit.SegmentControl[] vendorActivityControl = {null};
        LinearLayout vendorActivityRow = ui.controlRow(
                "厂商实时活动", "在支持的系统上显示增强状态", OFF_ON,
                json.optBoolean("vendor_live_activity_enabled", false) ? 1 : 0,
                value -> updateVendorLiveActivity(value == 1,
                        vendorActivityControl[0]));
        vendorActivityControl[0] =
                (UiKit.SegmentControl) vendorActivityRow.getChildAt(1);
        notification.addView(vendorActivityRow);
        root.addView(notification, ui.margins(0, 0, 0, 22));

        addHeading(root, "性能与电量", "控制分析的频率和触发条件");
        LinearLayout performance = ui.surface();
        performance.addView(ui.controlRow("低功耗模式", "减少检查频率，省电但响应略慢", OFF_ON,
                "DEGRADED".equals(json.optString("active_performance_profile")) ? 1 : 0,
                value -> mutate(candidate -> candidate.put("active_performance_profile",
                        value == 1 ? "DEGRADED" : "NORMAL"))));
        boolean battery = json.getJSONObject("load_protection")
                .optBoolean("pause_on_battery_low", true);
        performance.addView(ui.controlRow("低电量保护", "电量低时自动暂停保护", OFF_ON,
                battery ? 1 : 0, value -> mutate(candidate -> candidate
                        .getJSONObject("load_protection")
                        .put("pause_on_battery_low", value == 1))));
        performance.addView(ui.divider(), fullDivider());
        performance.addView(ui.entry("本机性能检查", "在你的手机上快速检测响应速度", "→",
                ignored -> show(Page.PERFORMANCE, -1, true)));
        root.addView(performance, ui.margins(0, 0, 0, 22));

        addHeading(root, "本机技术状态", "只保存在本机，可随时关闭或清除");
        LinearLayout local = ui.surface();
        local.addView(ui.controlRow("记录本机技术状态", null, OFF_ON,
                json.optBoolean("local_technical_status_enabled", true) ? 1 : 0,
                value -> mutate(candidate ->
                        candidate.put("local_technical_status_enabled", value == 1))));
        root.addView(local, ui.margins(0, 0, 0, 14));
        TextView clear = ui.capsule("清除本机技术状态", false, ignored ->
                ui.confirm("清除本机技术状态？",
                        "将删除性能检查和运行诊断留下的本机记录，不会改变保护设置。",
                        "清除", true, this::clearLocalTechnicalState));
        root.addView(clear, ui.margins(30, 0, 30, 10));
        return ui.scroll(root);
    }

    private View tutorialPage() {
        LinearLayout root = ui.pageColumn();
        TextView title = ui.text("权限与使用教程",
                ReadingLayoutSpec.TUTORIAL_PAGE_TITLE_SP, ui.p.ink, true);
        title.setGravity(Gravity.CENTER);
        root.addView(title, ui.margins(10, 0, 10, 5));
        TextView hint = ui.text("完成必要设置后，保护服务才能按预期运行",
                ReadingLayoutSpec.TUTORIAL_PAGE_SUBTITLE_SP, ui.p.muted, false);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(ui.dp(10), 0, ui.dp(10), 0);
        root.addView(hint, ui.margins(0, 0, 0, 18));
        for (int index = 0; index < TutorialPageCatalog.PAGE_COUNT; index++) {
            tutorialCard(root, TutorialPageCatalog.page(index));
        }
        return ui.scroll(root);
    }

    @SuppressLint("UseCompatLoadingForDrawables")
    private void tutorialCard(LinearLayout root, TutorialPageCatalog.PageSpec pageSpec) {
        // The tutorial index uses the same S1 card language as the rest of the
        // main interface.  Only the long-form child pages are unframed.
        LinearLayout card = ui.surface();
        Drawable.ConstantState state = tutorialIllustrationStates.get(pageSpec.illustrationRes);
        Drawable drawable = state == null
                ? getDrawable(pageSpec.illustrationRes)
                : state.newDrawable(getResources()).mutate();
        FrameLayout media = ui.recessedMediaFrame(drawable,
                pageSpec.rootLabel + "教程插图");
        card.addView(media, new LinearLayout.LayoutParams(-1,
                ui.dp(ReadingLayoutSpec.TUTORIAL_MEDIA_HEIGHT_DP)));
        card.addView(ui.divider(), fullDivider());
        card.addView(ui.entry(pageSpec.rootLabel, null, null, ignored -> {
            tutorialChild = pageSpec.rootLabel;
            show(Page.TUTORIAL_CHILD, 1, true);
        }));
        root.addView(card, ui.margins(0, 0, 0, 22));
    }

    private View tutorialChildPage() {
        TutorialPageCatalog.PageSpec pageSpec = TutorialPageCatalog.find(tutorialChild);
        if (pageSpec == null) {
            LinearLayout root = ui.pageColumn();
            TextView unavailable = tutorialText(
                    "教程内容不可用。请返回教程列表后重试。",
                    ReadingLayoutSpec.TUTORIAL_SECTION_BODY_SP, false, ui.p.ink);
            unavailable.setGravity(Gravity.CENTER_HORIZONTAL | Gravity.TOP);
            unavailable.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
            root.addView(unavailable,
                    ui.margins(0, 20, 0, 0));
            return ui.scroll(root);
        }
        return markdownTutorialPage(pageSpec);
    }

    /** Mirrors the approved Markdown: body paragraphs, bold subheads, then real actions. */
    private View markdownTutorialPage(TutorialPageCatalog.PageSpec pageSpec) {
        LinearLayout root = ui.pageColumn();
        boolean recentTaskAction = "隐藏 App".equals(pageSpec.rootLabel);

        TextView title = ui.text(pageSpec.childTitle,
                ReadingLayoutSpec.TUTORIAL_PAGE_TITLE_SP, ui.p.ink, true);
        title.setGravity(Gravity.CENTER);
        title.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
        root.addView(title, ui.margins(10, 0, 10, 0));

        if (pageSpec.lead != null && !pageSpec.lead.isBlank()) {
            root.addView(markdownTutorialParagraph(pageSpec.lead, ui.p.ink),
                    ui.margins(10, 38, 10, 0));
        }

        if (recentTaskAction) {
            root.addView(ui.capsule("从后台任务中清除", false,
                    ignored -> LowVisibilityManager.hideAndRemoveOwnTasks(this)),
                    ui.margins(28, 26, 28, 0));
        }

        if (pageSpec.groupDetail != null && !pageSpec.groupDetail.isBlank()) {
            root.addView(markdownTutorialParagraph(pageSpec.groupDetail, ui.p.ink),
                    ui.margins(10, recentTaskAction ? 36 : 22, 10, 0));
        }

        for (int index = 0; index < pageSpec.sections.length; index++) {
            root.addView(markdownTutorialSection(pageSpec.sections[index]),
                    ui.margins(10, index == 0 ? 34 : 32, 10, 0));
        }

        if (pageSpec.noteBody != null && !pageSpec.noteBody.isBlank()) {
            root.addView(markdownTutorialNote(pageSpec.noteTitle, pageSpec.noteBody),
                    ui.margins(10, 34, 10, 0));
        }

        if (pageSpec.closingBody != null && !pageSpec.closingBody.isBlank()) {
            root.addView(markdownTutorialParagraph(pageSpec.closingBody, ui.p.ink),
                    ui.margins(10, 34, 10, 0));
        }

        if (pageSpec.accessibilityAction) {
            boolean enabled = AccessibilityCapability.isEnabled(this);
            TextView state = ui.text(AccessibilityCapability.status(this),
                    ReadingLayoutSpec.TUTORIAL_STATUS_SP,
                    enabled ? ui.p.green : ui.p.red, true);
            state.setGravity(Gravity.CENTER);
            state.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
            root.addView(state, ui.margins(10, 34, 10, 0));
            root.addView(ui.capsule(enabled ? "打开系统无障碍设置" : "去开启无障碍权限",
                            false, ignored -> AccessibilityCapability.openSettings(this)),
                    ui.margins(28, 18, 28, 8));
        }
        return ui.scroll(root);
    }

    private TextView markdownTutorialParagraph(String value, int color) {
        TextView paragraph = tutorialText(keepClosingPunctuationWithPrevious(value),
                ReadingLayoutSpec.TUTORIAL_SECTION_BODY_SP, false, color);
        paragraph.setLineSpacing(ui.dp(3), 1.28f);
        // Chinese article copy should consume the available row before wrapping.
        // BALANCED deliberately shortens earlier lines to equalise line lengths,
        // which left visible unused space on tutorial pages.
        paragraph.setBreakStrategy(
                android.graphics.text.LineBreaker.BREAK_STRATEGY_SIMPLE);
        paragraph.setHyphenationFrequency(
                android.text.Layout.HYPHENATION_FREQUENCY_NONE);
        return paragraph;
    }

    /** Prevent Chinese closing punctuation from being stranded on a line by itself. */
    private String keepClosingPunctuationWithPrevious(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        String closingPunctuation = "，。！？；：、）】》”’";
        StringBuilder protectedText = new StringBuilder(value.length() + 8);
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (index > 0 && closingPunctuation.indexOf(current) >= 0) {
                protectedText.append('\u2060');
            }
            protectedText.append(current);
        }
        return protectedText.toString();
    }

    private LinearLayout markdownTutorialSection(TutorialPageCatalog.SectionSpec section) {
        LinearLayout block = ui.column();
        TextView heading = tutorialText(section.heading,
                ReadingLayoutSpec.TUTORIAL_SECTION_TITLE_SP, true, ui.p.ink);
        block.addView(heading, ui.matchWrap());
        block.addView(markdownTutorialParagraph(section.body, ui.p.ink),
                ui.margins(0, 10, 0, 0));
        return block;
    }

    private TextView markdownTutorialNote(String heading, String body) {
        String prefix = heading == null ? "" : heading;
        String protectedBody = keepClosingPunctuationWithPrevious(body);
        android.text.SpannableString value =
                new android.text.SpannableString(prefix + protectedBody);
        if (!prefix.isEmpty()) {
            value.setSpan(new android.text.style.StyleSpan(Typeface.BOLD), 0,
                    prefix.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        TextView note = markdownTutorialParagraph("", ui.p.muted);
        note.setText(value);
        return note;
    }

    /** Second reviewed tutorial child: original copy in an unframed article layout. */
    private View launcherIconTutorialPage(TutorialPageCatalog.PageSpec pageSpec) {
        LinearLayout root = ui.pageColumn();

        TextView title = ui.text(pageSpec.childTitle,
                ReadingLayoutSpec.TUTORIAL_PAGE_TITLE_SP, ui.p.ink, true);
        title.setGravity(Gravity.CENTER);
        title.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
        root.addView(title, ui.margins(10, 0, 10, 0));

        TextView subtitle = tutorialText(pageSpec.subtitle,
                ReadingLayoutSpec.TUTORIAL_PAGE_SUBTITLE_SP, false, ui.p.muted);
        subtitle.setGravity(Gravity.CENTER);
        subtitle.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
        root.addView(subtitle, ui.margins(12, 6, 12, 0));

        LinearLayout leadBlock = new LinearLayout(this);
        leadBlock.setOrientation(LinearLayout.HORIZONTAL);
        leadBlock.setGravity(Gravity.CENTER_VERTICAL);
        View accent = new View(this);
        accent.setBackgroundColor(ui.p.blue);
        leadBlock.addView(accent,
                new LinearLayout.LayoutParams(ui.dp(3), ui.dp(72)));
        TextView lead = tutorialText(pageSpec.lead,
                ReadingLayoutSpec.TUTORIAL_LEAD_SP, false, ui.p.ink);
        leadBlock.addView(lead, ui.margins(16, 0, 0, 0));
        root.addView(leadBlock, ui.margins(10, 38, 10, 0));

        TextView groupTitle = tutorialText(pageSpec.groupTitle,
                ReadingLayoutSpec.TUTORIAL_GROUP_TITLE_SP, true, ui.p.ink);
        root.addView(groupTitle, ui.margins(10, 42, 10, 0));
        TextView groupDetail = tutorialText(pageSpec.groupDetail,
                ReadingLayoutSpec.TUTORIAL_PAGE_SUBTITLE_SP, false, ui.p.muted);
        root.addView(groupDetail, ui.margins(10, 6, 10, 0));

        for (int index = 0; index < pageSpec.sections.length; index++) {
            root.addView(launcherArticleSection(pageSpec.sections[index]),
                    ui.margins(10, index == 0 ? 34 : 32, 10, 0));
        }

        LinearLayout.LayoutParams noteDivider = new LinearLayout.LayoutParams(-1, ui.dp(1));
        noteDivider.setMargins(ui.dp(10), ui.dp(38), ui.dp(10), 0);
        root.addView(ui.divider(), noteDivider);
        TextView noteTitle = tutorialText(pageSpec.noteTitle,
                ReadingLayoutSpec.TUTORIAL_SECTION_TITLE_SP, true, ui.p.ink);
        root.addView(noteTitle, ui.margins(10, 26, 10, 0));
        TextView noteBody = tutorialText(pageSpec.noteBody,
                ReadingLayoutSpec.TUTORIAL_SECTION_BODY_SP, false, ui.p.muted);
        root.addView(noteBody, ui.margins(10, 8, 10, 12));
        return ui.scroll(root);
    }

    private LinearLayout launcherArticleSection(TutorialPageCatalog.SectionSpec section) {
        LinearLayout article = ui.column();
        TextView heading = tutorialText(section.heading,
                ReadingLayoutSpec.TUTORIAL_SECTION_TITLE_SP, true, ui.p.ink);
        article.addView(heading, ui.matchWrap());
        TextView body = tutorialText(section.body.replace("；", "；\n"),
                ReadingLayoutSpec.TUTORIAL_SECTION_BODY_SP, false, ui.p.ink);
        body.setLineSpacing(ui.dp(3), 1.28f);
        article.addView(body, ui.margins(0, 10, 0, 0));
        return article;
    }

    /** First reviewed tutorial child: an unframed editorial explanation of accessibility. */
    private View accessibilityTutorialPage(TutorialPageCatalog.PageSpec pageSpec) {
        LinearLayout root = ui.pageColumn();

        TextView title = ui.text(pageSpec.childTitle,
                ReadingLayoutSpec.TUTORIAL_PAGE_TITLE_SP, ui.p.ink, true);
        title.setGravity(Gravity.CENTER);
        title.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
        root.addView(title, ui.margins(10, 0, 10, 0));

        TextView subtitle = tutorialText(pageSpec.subtitle,
                ReadingLayoutSpec.TUTORIAL_PAGE_SUBTITLE_SP, false, ui.p.muted);
        subtitle.setGravity(Gravity.CENTER);
        subtitle.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
        root.addView(subtitle, ui.margins(12, 6, 12, 0));

        boolean enabled = AccessibilityCapability.isEnabled(this);
        TextView state = ui.text(AccessibilityCapability.status(this),
                ReadingLayoutSpec.TUTORIAL_STATUS_SP,
                enabled ? ui.p.green : ui.p.red, true);
        state.setGravity(Gravity.CENTER);
        state.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
        root.addView(state, ui.margins(0, 16, 0, 0));

        TextView lead = tutorialLead(pageSpec.lead);
        root.addView(lead, ui.margins(8, 28, 8, 16));
        root.addView(accessibilityFlow(), ui.margins(10, 0, 10, 28));

        TextView groupTitle = tutorialText(pageSpec.groupTitle,
                ReadingLayoutSpec.TUTORIAL_GROUP_TITLE_SP, true, ui.p.ink);
        root.addView(groupTitle, ui.margins(10, 0, 10, 0));
        TextView groupDetail = tutorialText(pageSpec.groupDetail,
                ReadingLayoutSpec.TUTORIAL_PAGE_SUBTITLE_SP, false, ui.p.muted);
        root.addView(groupDetail, ui.margins(10, 5, 10, 8));

        // The first two rows are capabilities.  "未开启时" is a consequence,
        // so it receives its own warning hierarchy instead of a third peer number.
        int capabilityCount = Math.min(2, pageSpec.sections.length);
        for (int index = 0; index < capabilityCount; index++) {
            TutorialPageCatalog.SectionSpec section = pageSpec.sections[index];
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.TOP);

            String number = index < 9 ? "0" + (index + 1) : String.valueOf(index + 1);
            TextView numberView = ui.text(number,
                    ReadingLayoutSpec.TUTORIAL_EYEBROW_SP, ui.p.blue, true);
            numberView.setGravity(Gravity.START | Gravity.TOP);
            row.addView(numberView, new LinearLayout.LayoutParams(ui.dp(38), -2));

            LinearLayout copy = ui.column();
            TextView heading = tutorialText(section.heading,
                    ReadingLayoutSpec.TUTORIAL_SECTION_TITLE_SP, true, ui.p.ink);
            copy.addView(heading, ui.matchWrap());
            TextView body = tutorialText(section.body,
                    ReadingLayoutSpec.TUTORIAL_SECTION_BODY_SP, false, ui.p.ink);
            copy.addView(body, ui.margins(0, 6, 0, 0));
            row.addView(copy, new LinearLayout.LayoutParams(0, -2, 1f));
            root.addView(row, ui.margins(10, 18, 10, 18));

            if (index < capabilityCount - 1) {
                LinearLayout.LayoutParams divider =
                        new LinearLayout.LayoutParams(-1, ui.dp(1));
                divider.setMargins(ui.dp(48), 0, ui.dp(10), 0);
                root.addView(ui.divider(), divider);
            }
        }

        if (pageSpec.sections.length > capabilityCount) {
            LinearLayout.LayoutParams divider =
                    new LinearLayout.LayoutParams(-1, ui.dp(1));
            divider.setMargins(ui.dp(48), ui.dp(2), ui.dp(10), 0);
            root.addView(ui.divider(), divider);

            TutorialPageCatalog.SectionSpec unavailable =
                    pageSpec.sections[capabilityCount];
            LinearLayout warning = ui.column();
            TextView warningTitle = tutorialText(unavailable.heading,
                    ReadingLayoutSpec.TUTORIAL_SECTION_TITLE_SP, true, ui.p.red);
            warning.addView(warningTitle, ui.matchWrap());
            TextView warningBody = tutorialText(unavailable.body,
                    ReadingLayoutSpec.TUTORIAL_SECTION_BODY_SP, false, ui.p.ink);
            warning.addView(warningBody, ui.margins(0, 6, 0, 0));
            root.addView(warning, ui.margins(48, 22, 10, 0));
        }

        LinearLayout boundary = new LinearLayout(this);
        boundary.setOrientation(LinearLayout.HORIZONTAL);
        boundary.setGravity(Gravity.TOP);
        View accent = new View(this);
        accent.setBackgroundColor(ui.p.blue);
        LinearLayout.LayoutParams accentParams =
                new LinearLayout.LayoutParams(ui.dp(3), -1);
        accentParams.rightMargin = ui.dp(14);
        boundary.addView(accent, accentParams);

        LinearLayout boundaryCopy = ui.column();
        TextView boundaryTitle = tutorialText(pageSpec.noteTitle,
                ReadingLayoutSpec.TUTORIAL_SECTION_TITLE_SP, true, ui.p.blue);
        boundaryCopy.addView(boundaryTitle, ui.matchWrap());
        TextView boundaryBody = tutorialText(pageSpec.noteBody,
                ReadingLayoutSpec.TUTORIAL_SECTION_BODY_SP, false, ui.p.ink);
        boundaryCopy.addView(boundaryBody, ui.margins(0, 7, 0, 0));
        boundary.addView(boundaryCopy, new LinearLayout.LayoutParams(0, -2, 1f));
        root.addView(boundary, ui.margins(10, 26, 10, 0));

        root.addView(ui.capsule(enabled ? "打开系统无障碍设置" : "去开启无障碍权限", false,
                ignored -> AccessibilityCapability.openSettings(this)),
                ui.margins(28, 28, 28, 8));
        return ui.scroll(root);
    }

    /** Flat three-step reading path; it conveys sequence without introducing S1 cards. */
    private View accessibilityFlow() {
        LinearLayout flow = new LinearLayout(this);
        flow.setOrientation(LinearLayout.HORIZONTAL);
        flow.setGravity(Gravity.CENTER_VERTICAL);
        flow.addView(accessibilityFlowStep("必要信息", "当前屏幕"),
                new LinearLayout.LayoutParams(0, -2, 1f));
        flow.addView(accessibilityFlowArrow(),
                new LinearLayout.LayoutParams(ui.dp(28), -2));
        flow.addView(accessibilityFlowStep("本地识别", "设备内完成"),
                new LinearLayout.LayoutParams(0, -2, 1f));
        flow.addView(accessibilityFlowArrow(),
                new LinearLayout.LayoutParams(ui.dp(28), -2));
        flow.addView(accessibilityFlowStep("提醒／划走", "按你的设置"),
                new LinearLayout.LayoutParams(0, -2, 1f));
        return flow;
    }

    private LinearLayout accessibilityFlowStep(String title, String detail) {
        LinearLayout step = ui.column();
        step.setGravity(Gravity.CENTER_HORIZONTAL);
        TextView titleView = tutorialText(title,
                ReadingLayoutSpec.TUTORIAL_SECTION_TITLE_SP, true, ui.p.ink);
        titleView.setGravity(Gravity.CENTER);
        titleView.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
        step.addView(titleView, ui.matchWrap());
        TextView detailView = tutorialText(detail,
                ReadingLayoutSpec.TUTORIAL_EYEBROW_SP, false, ui.p.muted);
        detailView.setGravity(Gravity.CENTER);
        detailView.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
        step.addView(detailView, ui.margins(0, 4, 0, 0));
        return step;
    }

    private TextView accessibilityFlowArrow() {
        TextView arrow = ui.text("→", 18f, ui.p.blue, true);
        arrow.setGravity(Gravity.CENTER);
        arrow.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
        return arrow;
    }

    private TextView tutorialLead(String value) {
        TextView lead = tutorialText(value, ReadingLayoutSpec.TUTORIAL_LEAD_SP,
                true, ui.p.ink);
        lead.setGravity(Gravity.CENTER_HORIZONTAL | Gravity.TOP);
        lead.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
        lead.setPadding(ui.dp(10), ui.dp(6), ui.dp(10), ui.dp(6));
        return lead;
    }

    private LinearLayout tutorialSections(TutorialPageCatalog.SectionSpec[] sections) {
        LinearLayout content = ui.column();
        content.setPadding(ui.dp(10), 0, ui.dp(10), 0);
        for (int index = 0; index < sections.length; index++) {
            TutorialPageCatalog.SectionSpec section = sections[index];
            LinearLayout block = ui.column();
            int vertical = ui.dp(ReadingLayoutSpec.TUTORIAL_SECTION_VERTICAL_DP);
            block.setPadding(0, vertical, 0, vertical);

            TextView heading = tutorialText(section.heading,
                    ReadingLayoutSpec.TUTORIAL_SECTION_TITLE_SP, true, ui.p.ink);
            block.addView(heading, ui.matchWrap());
            TextView body = tutorialText(section.body,
                    ReadingLayoutSpec.TUTORIAL_SECTION_BODY_SP, false, ui.p.ink);
            block.addView(body, ui.margins(0, 6, 0, 0));
            content.addView(block, ui.matchWrap());
            if (index < sections.length - 1) {
                LinearLayout.LayoutParams divider =
                        new LinearLayout.LayoutParams(-1, ui.dp(1));
                divider.setMargins(0, ui.dp(4), 0, ui.dp(4));
                content.addView(ui.divider(), divider);
            }
        }
        return content;
    }

    private LinearLayout tutorialCallout(String heading, String value) {
        LinearLayout content = ui.column();
        content.setPadding(ui.dp(10), ui.dp(8), ui.dp(10), ui.dp(8));
        TextView headingView = tutorialText(heading,
                ReadingLayoutSpec.TUTORIAL_SECTION_TITLE_SP, true, ui.p.blue);
        content.addView(headingView, ui.matchWrap());
        TextView body = tutorialText(value,
                ReadingLayoutSpec.TUTORIAL_SECTION_BODY_SP, false, ui.p.ink);
        content.addView(body, ui.margins(0, 6, 0, 0));
        return content;
    }

    private TextView tutorialText(String value, float size, boolean medium, int color) {
        TextView view = ui.text(value, size, color, medium);
        view.setGravity(Gravity.START | Gravity.TOP);
        view.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        view.setLineSpacing(ui.dp(2), 1.22f);
        view.setLetterSpacing(0.01f);
        return view;
    }

    @SuppressLint("UseCompatLoadingForDrawables")
    private void preloadTutorialIllustrations() {
        for (int index = 0; index < TutorialPageCatalog.PAGE_COUNT; index++) {
            int resource = TutorialPageCatalog.page(index).illustrationRes;
            if (tutorialIllustrationStates.get(resource) != null) continue;
            Drawable drawable = getDrawable(resource);
            if (drawable != null && drawable.getConstantState() != null) {
                tutorialIllustrationStates.put(resource, drawable.getConstantState());
            }
        }
    }

    private View morePage() {
        LinearLayout root = ui.pageColumn();
        root.addView(ui.pageTitle("更多"), ui.margins(0, 0, 0, 16));

        addHeading(root, "显示与阅读", "只改变应用界面的显示，不影响识别与保护逻辑");
        LinearLayout display = ui.surface();
        display.addView(ui.utilityControlRow("界面主题", "选择界面的明暗方式",
                new String[] {"跟随系统", "浅色", "深色"}, ui.prefs.theme(), value -> {
                    ui.prefs.setTheme(value);
                    themeTransitionPending = true;
                    recreateAfterControlCommit();
                }));
        display.addView(ui.utilityControlRow("字体放大", "进一步放大应用内文字", OFF_ON,
                ui.prefs.largeText() ? 1 : 0, value -> {
                    ui.prefs.setLargeText(value == 1);
                    recreateAfterControlCommit();
                }));
        display.addView(ui.utilityControlRow("增强对比度", "让文字、分隔和控件更易辨认", OFF_ON,
                ui.prefs.highContrast() ? 1 : 0, value -> {
                    ui.prefs.setHighContrast(value == 1);
                    recreateAfterControlCommit();
                }));
        root.addView(display, ui.margins(0, 0, 0, 22));

        addHeading(root, "交互体验", "调整界面反馈，不改变保护服务的动作");
        LinearLayout interaction = ui.surface();
        interaction.addView(ui.utilityControlRow("减少动态效果", "缩短非必要景深与回弹", OFF_ON,
                ui.prefs.reduceMotion() ? 1 : 0,
                value -> ui.prefs.setReduceMotion(value == 1)));
        interaction.addView(ui.utilityControlRow("震动反馈", "点击按钮时提供轻触确认", OFF_ON,
                ui.prefs.haptics() ? 1 : 0,
                value -> ui.prefs.setHaptics(value == 1)));
        root.addView(interaction, ui.margins(0, 0, 0, 22));

        addInfoGroup(root, "应用说明", new MoreEntry[] {
                new MoreEntry(MoreDetail.ABOUT, "关于守目人", "产品定位、版本、支持范围与兼容性"),
                new MoreEntry(MoreDetail.PRIVACY, "隐私与保护", "本地数据、权限、工作原理与使用边界"),
                new MoreEntry(MoreDetail.OPEN_SOURCE, "开源与致谢", "随应用分发的组件、许可与已确认致谢"),
                new MoreEntry(MoreDetail.FAQ, "常见问题", "查看常见使用问题与处理建议")
        });

        FrameLayout developer = ui.pageLinkAdjacent("开发者模式", ignored -> ui.confirm("进入开发者模式？",
                "这里包含精确数值和工程诊断选项。修改不当可能降低识别效果、增加耗电或导致保护行为异常。",
                "仍然进入", true, () -> ui.startSpatial(
                        new Intent(this, DeveloperOptionsActivity.class), false)));
        root.addView(developer, ui.matchWrap());
        return ui.scroll(root);
    }

    private void addInfoGroup(LinearLayout root, String heading, MoreEntry[] items) {
        addHeading(root, heading, null);
        LinearLayout surface = ui.surface();
        for (int i = 0; i < items.length; i++) {
            MoreEntry item = items[i];
            surface.addView(ui.entry(item.title, item.summary, "→",
                    ignored -> openDetail(item)));
            if (i < items.length - 1) {
                surface.addView(ui.divider(), fullDivider());
            }
        }
        root.addView(surface, ui.margins(0, 0, 0, 22));
    }

    private void openDetail(MoreEntry entry) {
        detailId = entry.detail;
        detailTitle = entry.title;
        switch (entry.detail) {
            case ABOUT:
                detailCopy = BrandIdentity.FULL_NAME + "\n产品定位：" + entry.summary
                        + "。当前版本 " + BuildConfig.VERSION_NAME + "（版本代码 "
                        + BuildConfig.VERSION_CODE + "），最低 Android 11，当前支持手机竖屏。具体功能支持范围和更新记录以本页后续内容为准。";
                break;
            case PRIVACY:
                detailCopy = "屏幕内容只在设备内存中参与 OCR、模型与规则判断，不写入截图文件，也不上传截图、OCR 文字、账户名称、视频内容、判断结果或性能数据。本机仅保存用户设置和明确允许保留的技术状态。\n\n"
                        + "无障碍权限用于读取当前屏幕可见内容、确认目标应用和内容形态，并在策略要求时执行自动向上划走；通知权限仅用于显示保护运行状态。本应用不申请联网、悬浮窗、开机自启或屏幕录制权限。\n\n"
                        + "保护判断由本地 OCR、模型和规则共同完成，不能替代人工判断，也不保证识别所有内容。";
                break;
            case OPEN_SOURCE:
                detailCopy = "守目人（Gravekeeper）由 AvalonskyAfar 开发并维护。\n\n"
                        + "随应用分发的第三方组件：\n"
                        + "· LiteRT 2.1.4（Google AI Edge 开源运行时）：在设备本地运行视觉模型。\n"
                        + "  仓库：github.com/google-ai-edge/LiteRT\n\n"
                        + "· Google ML Kit 中文文字识别 16.0.1：在设备本地执行中文 OCR。\n"
                        + "  资料：developers.google.com/ml-kit/vision/text-recognition/v2/android\n\n"
                        + "· JUnit 4.13.2（开源测试框架）：仅用于开发阶段单元测试，不随 APK 发布。\n\n"
                        + "以上组件的许可文本以随 APK 分发的第三方许可清单为准。\n\n"
                        + "研究参考：\n"
                        + "在开发过程中，对以下开源方案进行了调研参考，用于理解手势生命周期、"
                        + "滚动物理和事件所有权的设计思路，但未直接引入它们作为运行时依赖：\n\n"
                        + "AndroidX ViewPager2 / ViewDragHelper / NestedScrolling、"
                        + "EverythingMe overscroll-decor、"
                        + "Material Components for Android、SwipeRevealLayout、SwipeBackLayout\n\n"
                        + "研究原则是提取机制，不复制视觉风格或默认动画曲线。\n\n"
                        + "特别感谢：\n"
                        + "感谢 api.uniprep.world 在项目全程免费提供 AI 编程 API 服务。"
                        + "该项目经历了从数据标注、模型训练到 Android 工程落地的完整周期，"
                        + "开发过程中的代码编写、调试与审计均依赖该 API 的免费支持。"
                        + "在预算极其有限的情况下，这项无偿贡献对项目的完成起到了至关重要的作用。\n\n"
                        + "声明：\n"
                        + "本应用完全离线运行，不申请联网权限，不连接任何服务器，不上传任何截图、"
                        + "识别数据或用户隐私。模型权重为自研训练所得，不以开源形式分发，"
                        + "不依赖任何外部推理服务。";
                break;
            case FAQ:
                detailCopy = "目前尚无已知的常见问题。\n\n"
                        + "如果在使用过程中遇到问题或有建议，请发送邮件至 hjtdyx1@outlook.com，我们会尽快回复。";
                break;
        }
        show(Page.MORE_DETAIL, -1, true);
    }

    private View detailPage() {
        LinearLayout root = ui.pageColumn();
        root.addView(ui.pageTitle(detailTitle == null ? "应用信息" : detailTitle),
                ui.margins(0, 0, 0, 16));
        root.addView(ui.plainTextSurface(
                detailCopy == null ? "内容正在整理。" : detailCopy));
        return ui.scroll(root);
    }

    private View performancePage() {
        LinearLayout root = ui.pageColumn();
        root.addView(ui.pageTitle("本机性能检查"), ui.margins(0, 0, 0, 16));
        LinearLayout result = ui.plainTextSurface(
                "尚未检查。测试会在本机执行短时视觉模型与 OCR 基准，期间可能出现短暂发热或耗电增加。结果只用于提供本机建议。");
        root.addView(result, ui.margins(0, 0, 0, 20));
        TextView run = ui.capsule("开始本机性能检查", false,
                ignored -> runPerformance(result));
        root.addView(run, ui.margins(28, 0, 28, 0));
        return ui.scroll(root);
    }

    private void runPerformance(LinearLayout resultSurface) {
        resultSurface.removeAllViews();
        TextView running = ui.text("正在检查，请保持应用位于前台…", 14,
                ui.p.ink, false);
        running.setGravity(Gravity.CENTER);
        resultSurface.addView(running);
        new Thread(() -> {
            try {
                GuardConfig config = store.load();
                PerformanceProbe.Result result = PerformanceProbe.run(this, config);
                PerformanceProbe.persist(this, result,
                        config.localTechnicalStatusEnabled);
                runOnUiThread(() -> showPerformanceResult(resultSurface, result));
            } catch (IOException error) {
                runOnUiThread(() -> {
                    ui.error("性能检查失败：" + safeMessage(error));
                    resultSurface.removeAllViews();
                    resultSurface.addView(ui.text(
                            "检查失败。配置无法读取，未保存结果。",
                            14, ui.p.ink, false));
                });
            }
        }, "gk-performance-probe").start();
    }

    private void showPerformanceResult(LinearLayout surface,
            PerformanceProbe.Result result) {
        surface.removeAllViews();
        String level;
        switch (result.level) {
            case RECOMMENDED: level = "通过：适合使用标准配置"; break;
            case DEGRADED: level = "有建议：适合启用低功耗模式"; break;
            case HIGH_RISK: level = "风险较高：建议降低分析频率"; break;
            default: level = "设备不支持或结果无法确认"; break;
        }
        String text = level
                + "\n\n视觉模型 P50/P95：" + result.p50Ms + "/" + result.p95Ms + " ms"
                + "\nOCR P50/P95：" + result.ocrP50Ms + "/" + result.ocrP95Ms + " ms"
                + "\n持续处理：" + String.format(Locale.ROOT, "%.1f", result.sustainedFps)
                + " 次/秒\n峰值内存：" + result.peakPssMb + " MB"
                + (result.note == null || result.note.isBlank()
                        ? "" : "\n\n" + result.note);
        surface.addView(ui.text(text, 14, ui.p.ink, false));
    }

    private void addHeading(LinearLayout root, String title, String detail) {
        root.addView(ui.heading(title, detail), ui.margins(0, 0, 0, 10));
    }

    private LinearLayout.LayoutParams fullDivider() {
        LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(-1, ui.dp(1));
        params.setMargins(-ui.dp(ui.surfacePaddingDp()), ui.dp(5),
                -ui.dp(ui.surfacePaddingDp()), ui.dp(5));
        return params;
    }

    private void show(Page destination, int direction, boolean animate) {
        host.stabilizeForNavigation();
        if (page == destination && host.getChildCount() > 0) return;
        View incoming = obtainPage(destination);
        if (!animate || host.getChildCount() == 0) {
            page = destination;
            host.replace(incoming);
        } else {
            host.transition(destination, incoming, direction);
        }
    }

    private void saveProtection(boolean enabled, UiKit.PowerSwitch power, TextView status) {
        if (enabled && !AccessibilityCapability.isEnabled(this)) {
            if (power != null) power.setEnabledState(false, true);
            if (status != null) status.setText("请先开启无障碍权限");
            ui.confirm("需要无障碍权限", "未开启无障碍权限时，保护服务无法读取屏幕或执行保护动作。请先在系统设置中开启守目人。",
                    "打开设置", false, () -> AccessibilityCapability.openSettings(this));
            return;
        }
        try {
            store.setProtectionEnabled(enabled);
            invalidatePageCache();
            status.setText(enabled ? "保护已开启" : "保护已关闭");
            ui.message(enabled ? "保护已开启" : "保护已关闭");
        } catch (IOException error) {
            ui.error("保存保护总开关失败：" + safeMessage(error));
            if (power != null) power.setEnabledState(!enabled, true);
        }
    }

    private void enforceAccessibilityState() {
        boolean enabled = AccessibilityCapability.isEnabled(this);
        try {
            GuardConfig config = store.load();
            if (config.protectionEnabled && !enabled) {
                store.setProtectionEnabled(false);
                config = store.load();
            }
            if (page == Page.MAIN && mainPower != null && mainProtectionStatus != null) {
                boolean running = config.protectionEnabled && enabled;
                mainPower.setEnabledState(running, true);
                mainProtectionStatus.setText(running ? "保护已开启" : enabled ? "保护已关闭" : "请先开启无障碍权限");
            }
        } catch (IOException ignored) {
            if (mainPower != null) mainPower.setEnabledState(false, true);
        }
    }

    private void recreateAfterControlCommit() {
        View decor = getWindow().getDecorView();
        decor.removeCallbacks(recreateRunnable);
        decor.postDelayed(recreateRunnable, MotionSpec.controlDuration(ui) + 32L);
    }

    private final Runnable recreateRunnable = () -> {
        if (!isFinishing() && !isChangingConfigurations()) {
            if (themeTransitionPending) UiKit.prepareThemeTransition(this);
            recreate();
        }
    };

    private interface JsonMutation {
        void apply(JSONObject json) throws JSONException, IOException;
    }

    private void mutate(JsonMutation change) {
        try {
            JSONObject json = store.loadJson();
            change.apply(json);
            store.save(json);
            invalidatePageCache();
        } catch (IOException | JSONException error) {
            ui.error("保存设置失败：" + safeMessage(error));
            host.replace(renderIdentified(page));
        }
    }

    private void updatePlatformStrength(String id, int value) {
        mutate(json -> {
            JSONObject platform = platformById(json, id);
            JSONObject base = platformById(store.defaultJson(), id);
            platform.put("enabled", value != 0);
            if (value != 0) {
                double offset = value == 1 ? -0.08 : value == 3 ? 0.08 : 0.0;
                platform.put("risk_bias",
                        base.optDouble("risk_bias", 0.0) + offset);
            }
        });
    }

    private void updateSharedMedia(String key, int level) {
        Runnable commit = () -> mutate(json -> {
            JSONArray platforms = json.getJSONArray("platforms");
            for (int i = 0; i < platforms.length(); i++) {
                applyMediaLevel(
                        platforms.getJSONObject(i).getJSONObject(key), level);
            }
        });
        commit.run();
        promptNotificationPermissionIfNotifyAndNeeded(level);
    }

    private void updatePlatformMedia(String id, String key, int level) {
        Runnable commit = () -> mutate(json -> applyMediaLevel(
                platformById(json, id).getJSONObject(key), level));
        commit.run();
        promptNotificationPermissionIfNotifyAndNeeded(level);
    }

    /**
     * The media strategy is always saved first (the commit above), then — if the
     * user chose "提醒" — a purely informational dialog nudges them to grant the
     * notification permission. Unlike {@link #runWithNotificationPermission}, this
     * does not gate the action: the user's chosen strategy is committed regardless
     * of the permission outcome, because the system will silently drop alerts when
     * permission is absent and the user can enable it later.
     */
    private void promptNotificationPermissionIfNotifyAndNeeded(int level) {
        if (level != 1 || canPostNotifications()) return;
        ui.confirm("开启通知以接收提醒",
                "将内容策略设为【提醒】后，守目人会在检测到风险时通过通知栏发出提醒。"
                        + "当前系统通知权限未开启，提醒会被静默忽略。你可以在系统设置中手动开启。",
                "去开启", false,
                () -> openNotificationSettingsForPendingAction());
    }

    private void updateStatusNotification(boolean enabled,
            UiKit.SegmentControl control) {
        Runnable commit = () -> mutate(json -> {
            json.put("status_notification_enabled", enabled);
            if (!enabled && !json.optBoolean(
                    "vendor_live_activity_enabled", false)) {
                json.put("notification_quick_stop", false);
            }
        });
        if (enabled) runWithNotificationPermission(control, commit);
        else commit.run();
    }

    private void updateQuickStop(boolean enabled, UiKit.SegmentControl control) {
        Runnable commit = () -> mutate(json -> {
            json.put("notification_quick_stop", enabled);
            if (enabled && !json.optBoolean("status_notification_enabled", false)
                    && !json.optBoolean("vendor_live_activity_enabled", false)) {
                json.put("status_notification_enabled", true);
            }
        });
        if (enabled) runWithNotificationPermission(control, commit);
        else commit.run();
    }

    private void updateVendorLiveActivity(boolean enabled,
            UiKit.SegmentControl control) {
        Runnable commit = () -> mutate(json ->
                json.put("vendor_live_activity_enabled", enabled));
        if (enabled) runWithNotificationPermission(control, commit);
        else commit.run();
    }

    private void runWithNotificationPermission(UiKit.SegmentControl control,
            Runnable action) {
        if (canPostNotifications()) {
            action.run();
            return;
        }
        if (control != null) control.setSelected(0, true);
        pendingNotificationAction = action;
        pendingNotificationControl = control;

        boolean runtimeMissing = Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED;
        boolean requestedBefore = getSharedPreferences("guard_permissions", MODE_PRIVATE)
                .getBoolean("notification_permission_requested", false);
        if (runtimeMissing && (!requestedBefore
                || shouldShowRequestPermissionRationale(
                Manifest.permission.POST_NOTIFICATIONS))) {
            final boolean[] launched = {false};
            Dialog prompt = ui.confirm("需要通知权限",
                    "守目人需要通知权限来发送提醒和运行状态。只有你授予系统通知权限后，相关功能才会真正生效。",
                    "授予权限", false, () -> {
                        launched[0] = true;
                        getSharedPreferences("guard_permissions", MODE_PRIVATE).edit()
                                .putBoolean("notification_permission_requested", true).apply();
                        requestPermissions(
                                new String[] {Manifest.permission.POST_NOTIFICATIONS},
                                REQUEST_NOTIFICATION_PERMISSION);
                    });
            prompt.setOnDismissListener(ignored -> {
                if (!launched[0]) cancelPendingNotificationAction();
            });
            return;
        }
        final boolean[] launched = {false};
        Dialog prompt = ui.confirm("通知权限未开启",
                "系统当前不允许守目人显示通知。你可以前往系统通知设置手动开启。",
                "打开设置", false, () -> {
                    launched[0] = true;
                    openNotificationSettingsForPendingAction();
                });
        prompt.setOnDismissListener(ignored -> {
            if (!launched[0]) cancelPendingNotificationAction();
        });
    }

    private boolean canPostNotifications() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) return false;
        NotificationManager manager = getSystemService(NotificationManager.class);
        return manager != null && manager.areNotificationsEnabled();
    }

    private void openNotificationSettingsForPendingAction() {
        waitingForNotificationSettings = true;
        Intent settings = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
        settings.putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
        startActivity(settings);
    }

    private void completePendingNotificationAction() {
        Runnable action = pendingNotificationAction;
        UiKit.SegmentControl control = pendingNotificationControl;
        pendingNotificationAction = null;
        pendingNotificationControl = null;
        if (control != null) control.setSelected(1, true);
        if (action != null) action.run();
    }

    private void cancelPendingNotificationAction() {
        if (pendingNotificationControl != null) {
            pendingNotificationControl.setSelected(0, true);
        }
        pendingNotificationAction = null;
        pendingNotificationControl = null;
    }

    @Override public void onRequestPermissionsResult(int requestCode,
            String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_NOTIFICATION_PERMISSION) return;
        if (grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED
                && canPostNotifications()) {
            completePendingNotificationAction();
        } else {
            cancelPendingNotificationAction();
            ui.message("未获得通知权限，相关设置保持关闭");
        }
    }

    private void updateNegativeContext(boolean enabled) {
        mutate(json -> {
            JSONObject current = json.getJSONObject("signals");
            double defaultBias = store.defaultJson().getJSONObject("signals")
                    .optDouble("negative_context_bias", -0.22);
            current.put("negative_context_bias", enabled ? defaultBias : 0.0);
        });
    }

    private void updateAllRules(boolean enabled) {
        mutate(json -> {
            JSONArray rules = json.getJSONObject("signals")
                    .getJSONArray("runtime_rules");
            for (int i = 0; i < rules.length(); i++) {
                rules.getJSONObject(i).put("enabled", enabled);
            }
        });
    }

    private void updateLauncherVisibility(boolean hide) {
        if (!hide) {
            LowVisibilityManager.setLauncherVisible(this, true);
            ui.message("已恢复桌面入口");
            return;
        }
        ui.confirm("隐藏桌面入口？",
                "隐藏后桌面上不会再显示入口。你仍可从系统无障碍服务的守目人详情页打开恢复页面。",
                "确认隐藏", true, () -> {
                    LowVisibilityManager.setLauncherVisible(this, false);
                    ui.message("桌面入口已隐藏");
                    page = Page.MORE;
                    host.replace(renderIdentified(Page.MORE));
                });
    }

    private void clearLocalTechnicalState() {
        getSharedPreferences("guard_performance", MODE_PRIVATE)
                .edit().clear().apply();
        getSharedPreferences("guard_telemetry", MODE_PRIVATE)
                .edit().clear().apply();
        ui.message("本机技术状态已清除");
    }

    private static JSONObject platformById(JSONObject root, String id)
            throws JSONException {
        JSONArray platforms = root.getJSONArray("platforms");
        for (int i = 0; i < platforms.length(); i++) {
            JSONObject platform = platforms.getJSONObject(i);
            if (id.equals(platform.optString("id"))) return platform;
        }
        throw new JSONException("找不到平台：" + id);
    }

    private static int strengthLevel(JSONObject platform, JSONObject defaults) {
        if (!platform.optBoolean("enabled", true)) return 0;
        double delta = platform.optDouble("risk_bias", 0)
                - defaults.optDouble("risk_bias", 0);
        if (delta <= -0.04) return 1;
        if (delta >= 0.04) return 3;
        return 2;
    }

    private static int mediaLevel(JSONObject media) throws JSONException {
        if (!media.optBoolean("enabled", true)) return 0;
        for (String band : new String[] {"low", "medium", "high"}) {
            if ("SWIPE".equals(media.getJSONObject(band)
                    .optString("action"))) return 2;
        }
        return 1;
    }

    private static void applyMediaLevel(JSONObject media, int level)
            throws JSONException {
        media.put("enabled", level != 0);
        for (String band : new String[] {"low", "medium", "high"}) {
            String action;
            if (level == 0) action = "IGNORE";
            else if (level == 1) action = "NOTIFY";
            else action = "low".equals(band) ? "NOTIFY" : "SWIPE";
            media.getJSONObject(band).put("action", action);
        }
    }

    private static boolean allRulesEnabled(JSONArray rules) {
        if (rules == null || rules.length() == 0) return false;
        for (int i = 0; i < rules.length(); i++) {
            JSONObject rule = rules.optJSONObject(i);
            if (rule == null || !rule.optBoolean("enabled", false)) return false;
        }
        return true;
    }

    private static String shortPlatformName(String value) {
        if (value.contains("抖音")) return "抖音";
        if (value.contains("快手")) return "快手";
        return value.replace("系列", "");
    }

    private static String safeMessage(Throwable error) {
        return error.getMessage() == null
                ? error.getClass().getSimpleName() : error.getMessage();
    }

    @SuppressLint("GestureBackNavigation")
    @Override public void onBackPressed() {
        handleBack();
    }

    private boolean handleBack() {
        host.stabilizeForNavigation();
        Page target = backTarget(page);
        if (target != null) {
            show(target, backDirection(page), true);
            return true;
        }
        if (page == Page.MAIN) {
            finish();
            return true;
        }
        return false;
    }

    static Page backTarget(Page current) {
        switch (current) {
            case ADVANCED: return Page.SETTINGS;
            case WHITELIST: return Page.SETTINGS;
            case PERFORMANCE: return Page.ADVANCED;
            case TUTORIAL_CHILD: return Page.TUTORIAL;
            case MORE_DETAIL: return Page.MORE;
            case SETTINGS:
            case TUTORIAL:
            case MORE: return Page.MAIN;
            case MAIN:
            default: return null;
        }
    }

    static int backDirection(Page current) {
        switch (current) {
            case SETTINGS: return 2;
            case WHITELIST:
            case TUTORIAL:
            case TUTORIAL_CHILD: return -1;
            default: return 1;
        }
    }

    private final class GestureHost extends FrameLayout {
        private final int slop;
        private final int edge;
        private float downX;
        private float downY;
        private boolean dragging;
        private boolean horizontal;
        private int direction;
        private Page dragTarget;
        private View outgoing;
        private View incoming;
        private VelocityTracker velocity;
        private boolean transitioning;
        private boolean segmentGesture;
        private boolean blockVerticalReturnForStream;
        private boolean axisLocked;
        private boolean streamRejected;
        private boolean waitingForScrollBoundary;
        private Page settlingSource;
        private Page settlingTarget;
        private ValueAnimator settleAnimator;
        private boolean settleCompletes;
        private final ArrayList<Runnable> warmupCallbacks = new ArrayList<>();
        private int warmupGeneration;

        GestureHost() {
            super(MainActivity.this);
            slop = ViewConfiguration.get(MainActivity.this)
                    .getScaledTouchSlop();
            edge = ui.dp(24);
            setBackgroundColor(ui.p.bg);
            setClipChildren(true);
        }

        @Override public boolean dispatchTouchEvent(MotionEvent event) {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                // Page construction is performed on the UI thread. A new touch is a
                // stronger signal than the warmup timer: the user is about to scroll
                // or interact, so defer all neighbor work until the next navigation.
                cancelPageWarmups();
            }
            return super.dispatchTouchEvent(event);
        }

        boolean isTransitioning() {
            return transitioning || dragging;
        }

        void stabilizeForNavigation() {
            if (transitioning) {
                boolean complete = settleCompletes;
                cancelSettleAnimator();
                completeSettle(complete);
            } else if (dragging) {
                cancelSettlingAndAdopt();
            }
        }

        void replace(View view) {
            cancelSettleAnimator();
            for (int index = 0; index < getChildCount(); index++) {
                View child = getChildAt(index);
                if (child != view) pageIdentities.remove(child);
            }
            removeAllViews();
            view.setTranslationX(0);
            view.setTranslationY(0);
            addView(view, new FrameLayout.LayoutParams(-1, -1));
            transitioning = false;
            dragging = false;
            outgoing = null;
            incoming = null;
            dragTarget = null;
            settlingSource = null;
            settlingTarget = null;
            settleCompletes = false;
            // Plain swap (includes cold start and restore): the host is idle with no
            // gesture or settle in flight, so build neighbors on the next frame.
            postWarmNeighbor(page, true);
        }

        void transition(Page to, View view, int routeDirection) {
            stabilizeForNavigation();
            if (getChildCount() == 0 || routeDirection == 0) {
                page = to;
                replace(view);
                return;
            }
            transitioning = true;
            settlingSource = page;
            settlingTarget = to;
            dragTarget = to;
            outgoing = getChildAt(getChildCount() - 1);
            incoming = view;
            addView(view, new FrameLayout.LayoutParams(-1, -1));
            boolean verticalRoute = Math.abs(routeDirection) == 2;
            horizontal = !verticalRoute;
            float distance = verticalRoute
                    ? Math.max(1, getHeight()) : Math.max(1, getWidth());
            float sign = routeDirection > 0 ? 1f : -1f;
            direction = sign > 0 ? 1 : -1;
            if (verticalRoute) view.setTranslationY(-sign * distance);
            else view.setTranslationX(-sign * distance);
            startSettle(true);
        }

        @Override public boolean onInterceptTouchEvent(MotionEvent event) {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                if (transitioning) cancelSettlingAndAdopt();
                downX = event.getX();
                downY = event.getY();
                dragging = false;
                axisLocked = false;
                streamRejected = false;
                waitingForScrollBoundary = false;
                blockVerticalReturnForStream = currentPageCanScrollTowardTop();
                segmentGesture = touchesSegment(event.getRawX(), event.getRawY());
                if (velocity != null) velocity.recycle();
                velocity = VelocityTracker.obtain();
                velocity.addMovement(event);
                return false;
            }
            // A track owns a horizontal gesture, but it must not permanently
            // shield the page from a vertical gesture that starts on the same
            // rectangle.  The old unconditional return made a DOWN on K1/K5
            // lock the event stream to the child even after the finger clearly
            // moved vertically, so ScrollView and the page-level pull-down
            // route could never receive the gesture.  Once the direction is
            // unambiguous, release the track reservation and let the normal
            // parent arbitration continue.
            if (segmentGesture) {
                if (action != MotionEvent.ACTION_MOVE) return false;
                float segmentDx = event.getX() - downX;
                float segmentDy = event.getY() - downY;
                if (Math.max(Math.abs(segmentDx), Math.abs(segmentDy)) < slop
                        || Math.abs(segmentDx) > Math.abs(segmentDy)) {
                    return false;
                }
                segmentGesture = false;
            }
            if (velocity != null) velocity.addMovement(event);
            if (action != MotionEvent.ACTION_MOVE) return false;
            if (waitingForScrollBoundary) {
                if (currentPageCanScrollTowardTop()) return false;
                waitingForScrollBoundary = false;
                axisLocked = false;
                blockVerticalReturnForStream = false;
                downX = event.getX();
                downY = event.getY();
                resetVelocity(event);
                return false;
            }
            float dx = event.getX() - downX;
            float dy = event.getY() - downY;
            return tryBeginDrag(dx, dy);
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                if (transitioning) cancelSettlingAndAdopt();
                downX = event.getX();
                downY = event.getY();
                dragging = false;
                axisLocked = false;
                streamRejected = false;
                waitingForScrollBoundary = false;
                blockVerticalReturnForStream = currentPageCanScrollTowardTop();
                if (velocity != null) velocity.recycle();
                velocity = VelocityTracker.obtain();
                velocity.addMovement(event);
                return true;
            }
            if (velocity != null) velocity.addMovement(event);
            if (!dragging && action == MotionEvent.ACTION_MOVE) {
                float dx = event.getX() - downX;
                float dy = event.getY() - downY;
                tryBeginDrag(dx, dy);
            }
            if (!dragging) return true;
            float delta = horizontal
                    ? event.getX() - downX : event.getY() - downY;
            if (action == MotionEvent.ACTION_MOVE) {
                updateDrag(delta);
                return true;
            }
            if (action == MotionEvent.ACTION_UP
                    || action == MotionEvent.ACTION_CANCEL) {
                float speed = 0;
                if (velocity != null) {
                    velocity.computeCurrentVelocity(1000);
                    speed = horizontal
                            ? velocity.getXVelocity() : velocity.getYVelocity();
                }
                float distance = horizontal ? getWidth() : getHeight();
                boolean finish = action == MotionEvent.ACTION_UP
                        && (Math.abs(delta) > distance * 0.28f
                        || (Math.abs(speed) > 850
                        && Math.signum(speed) == Math.signum(direction)));
                endDrag(finish);
                return true;
            }
            return true;
        }

        @Override public void requestDisallowInterceptTouchEvent(boolean disallowIntercept) {
            // A segment track owns the gesture that starts on it so its thumb
            // remains fully finger-following. Other descendants cannot block
            // the approved global page routes, including pull-down to main.
            super.requestDisallowInterceptTouchEvent(segmentGesture && disallowIntercept);
        }

        private boolean touchesSegment(float rawX, float rawY) {
            return touchesSegment(this, Math.round(rawX), Math.round(rawY));
        }

        private boolean touchesSegment(View candidate, int rawX, int rawY) {
            if (!candidate.isShown()) return false;
            if (candidate instanceof UiKit.SegmentControl
                    || candidate instanceof UiKit.PowerSwitch
                    || candidate instanceof UiKit.CompactToggle
                    || candidate instanceof UiKit.PreciseValueControl) {
                Rect bounds = new Rect();
                return candidate.getGlobalVisibleRect(bounds) && bounds.contains(rawX, rawY);
            }
            if (!(candidate instanceof ViewGroup)) return false;
            ViewGroup group = (ViewGroup) candidate;
            for (int i = group.getChildCount() - 1; i >= 0; i--) {
                if (touchesSegment(group.getChildAt(i), rawX, rawY)) return true;
            }
            return false;
        }

        @Override public boolean performClick() {
            super.performClick();
            return true;
        }

        private Page targetFor(float dx, float dy, boolean horizontalMove) {
            if (!horizontalMove) {
                if (page == Page.MAIN && dy < 0) return Page.SETTINGS;
                if ((page == Page.SETTINGS || page == Page.ADVANCED)
                        && dy > 0 && !blockVerticalReturnForStream) return Page.MAIN;
                return null;
            }
            if (page == Page.MAIN) {
                return dx > 0 ? Page.TUTORIAL : Page.MORE;
            }
            if (page == Page.SETTINGS && dx < 0) return Page.ADVANCED;
            if (page == Page.ADVANCED && dx > 0) return Page.SETTINGS;
            if (page == Page.WHITELIST && dx < 0) return Page.SETTINGS;
            if (page == Page.TUTORIAL) {
                if (dx < 0) return Page.MAIN;
                tutorialChild = "无障碍权限";
                return Page.TUTORIAL_CHILD;
            }
            if (page == Page.TUTORIAL_CHILD && dx < 0) {
                return Page.TUTORIAL;
            }
            if (page == Page.MORE && dx > 0) return Page.MAIN;
            if (page == Page.MORE_DETAIL && dx > 0) return Page.MORE;
            if (page == Page.PERFORMANCE && dx > 0) return Page.ADVANCED;
            return null;
        }

        private void beginDrag(Page target, float delta) {
            if (transitioning) cancelSettlingAndAdopt();
            dragging = true;
            dragTarget = target;
            settlingSource = page;
            outgoing = getChildAt(getChildCount() - 1);
            incoming = obtainPage(target);
            direction = delta >= 0 ? 1 : -1;
            float distance = horizontal
                    ? Math.max(1, getWidth()) : Math.max(1, getHeight());
            if (horizontal) incoming.setTranslationX(-direction * distance);
            else incoming.setTranslationY(-direction * distance);
            addView(incoming, 0, new FrameLayout.LayoutParams(-1, -1));
            updateDrag(delta);
        }

        private void updateDrag(float delta) {
            float distance = horizontal
                    ? Math.max(1, getWidth()) : Math.max(1, getHeight());
            float bounded = Math.min(distance, Math.max(-distance, delta));
            if (Math.signum(bounded) != Math.signum(direction)) bounded = 0;
            if (horizontal) {
                outgoing.setTranslationX(bounded);
                incoming.setTranslationX(bounded - direction * distance);
            } else {
                outgoing.setTranslationY(bounded);
                incoming.setTranslationY(bounded - direction * distance);
            }
        }

        private void endDrag(boolean finish) {
            transitioning = true;
            settlingTarget = dragTarget;
            startSettle(finish);
        }

        private void completeSettle(boolean finished) {
            if (!transitioning) return;
            View discarded = finished ? outgoing : incoming;
            Page discardedPage = finished ? settlingSource : settlingTarget;
            if (discarded != null && discarded.getParent() == this) removeView(discarded);
            View active = finished ? incoming : outgoing;
            if (active != null) {
                active.setTranslationX(0);
                active.setTranslationY(0);
            }
            dragging = false;
            transitioning = false;
            if (finished) page = settlingTarget;
            cachePage(discardedPage, discarded);
            outgoing = null;
            incoming = null;
            dragTarget = null;
            settlingSource = null;
            settlingTarget = null;
            settleAnimator = null;
            settleCompletes = false;
            if (velocity != null) {
                velocity.recycle();
                velocity = null;
            }
            postWarmNeighbor(page);
        }

        private boolean currentPageCanScrollTowardTop() {
            if (page != Page.SETTINGS && page != Page.ADVANCED) return false;
            View active = getChildCount() == 0 ? null : getChildAt(getChildCount() - 1);
            ScrollView scroll = findScrollView(active);
            return scroll != null && (scroll.getScrollY() > 0
                    || scroll.canScrollVertically(-1));
        }

        private ScrollView findScrollView(View candidate) {
            if (candidate instanceof ScrollView) return (ScrollView) candidate;
            if (!(candidate instanceof ViewGroup)) return null;
            ViewGroup group = (ViewGroup) candidate;
            for (int index = group.getChildCount() - 1; index >= 0; index--) {
                ScrollView match = findScrollView(group.getChildAt(index));
                if (match != null) return match;
            }
            return null;
        }

        private boolean tryBeginDrag(float dx, float dy) {
            if (streamRejected) return false;
            if (!axisLocked) {
                if (Math.max(Math.abs(dx), Math.abs(dy)) < slop) return false;
                horizontal = Math.abs(dx) > Math.abs(dy);
                axisLocked = true;
                if (!horizontal && dy > 0
                        && (page == Page.SETTINGS || page == Page.ADVANCED)
                        && currentPageCanScrollTowardTop()) {
                    waitingForScrollBoundary = true;
                    return false;
                }
                if (horizontal && (downX < edge || downX > getWidth() - edge)) {
                    streamRejected = true;
                    return false;
                }
                Page target = targetFor(dx, dy, horizontal);
                if (target == null) {
                    streamRejected = true;
                    return false;
                }
                beginDrag(target, horizontal ? dx : dy);
            }
            return dragging;
        }

        private void startSettle(boolean complete) {
            cancelSettleAnimator();
            settleCompletes = complete;
            final float distance = horizontal
                    ? Math.max(1, getWidth()) : Math.max(1, getHeight());
            final float outgoingStart = horizontal ? outgoing.getTranslationX()
                    : outgoing.getTranslationY();
            final float incomingStart = horizontal ? incoming.getTranslationX()
                    : incoming.getTranslationY();
            final float outgoingTarget = complete ? direction * distance : 0f;
            final float incomingTarget = complete ? 0f : -direction * distance;
            float remaining = Math.max(Math.abs(outgoingTarget - outgoingStart),
                    Math.abs(incomingTarget - incomingStart)) / distance;
            ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
            settleAnimator = animator;
            animator.setDuration(MotionSpec.settleDuration(ui, remaining));
            animator.setInterpolator(ui.motionInterpolator());
            animator.addUpdateListener(value -> {
                float progress = (float) value.getAnimatedValue();
                float out = outgoingStart + (outgoingTarget - outgoingStart) * progress;
                float in = incomingStart + (incomingTarget - incomingStart) * progress;
                if (horizontal) {
                    outgoing.setTranslationX(out);
                    incoming.setTranslationX(in);
                } else {
                    outgoing.setTranslationY(out);
                    incoming.setTranslationY(in);
                }
            });
            animator.addListener(new AnimatorListenerAdapter() {
                private boolean cancelled;
                @Override public void onAnimationCancel(Animator animation) { cancelled = true; }
                @Override public void onAnimationEnd(Animator animation) {
                    if (!cancelled && settleAnimator == animation) completeSettle(complete);
                }
            });
            animator.start();
        }

        private void cancelSettleAnimator() {
            ValueAnimator active = settleAnimator;
            settleAnimator = null;
            if (active != null) active.cancel();
        }

        private void resetVelocity(MotionEvent event) {
            if (velocity != null) velocity.recycle();
            velocity = VelocityTracker.obtain();
            velocity.addMovement(event);
        }

        /**
         * A new gesture arriving during settle adopts the destination as a stable
         * committed page, cancels both property animators, and immediately exposes
         * the real destination view. This prevents a stale animation from eating
         * the next DOWN or leaving page/visual state out of sync.
         */
        private void cancelSettlingAndAdopt() {
            if (!transitioning && !dragging) return;
            cancelSettleAnimator();
            Page target = settlingTarget != null ? settlingTarget : dragTarget;
            float distance = horizontal ? Math.max(1, getWidth()) : Math.max(1, getHeight());
            float incomingOffset = incoming == null ? distance : Math.abs(horizontal
                    ? incoming.getTranslationX() : incoming.getTranslationY());
            boolean adoptIncoming = incoming != null && incomingOffset <= distance * 0.5f;
            View adoptedView = adoptIncoming ? incoming : outgoing;
            View discarded = adoptIncoming ? outgoing : incoming;
            Page adoptedPage = adoptIncoming ? target : settlingSource;
            Page discardedPage = adoptIncoming ? settlingSource : target;
            if (discarded != null && discarded.getParent() == this) removeView(discarded);
            if (adoptedView != null && adoptedView.getParent() == this) {
                adoptedView.setTranslationX(0);
                adoptedView.setTranslationY(0);
                page = adoptedPage;
            }
            cachePage(discardedPage, discarded);
            outgoing = null;
            incoming = null;
            dragTarget = null;
            dragging = false;
            transitioning = false;
            settlingSource = null;
            settlingTarget = null;
            settleCompletes = false;
            if (velocity != null) { velocity.recycle(); velocity = null; }
            postWarmNeighbor(page);
        }

        private void postWarmNeighbor(Page stable, boolean immediate) {
            cancelPageWarmups();
            Page[] targets = stable == Page.MAIN
                    ? new Page[] {Page.SETTINGS, Page.TUTORIAL, Page.MORE}
                    : stable == Page.SETTINGS ? new Page[] {Page.ADVANCED, Page.MAIN}
                    : stable == Page.ADVANCED ? new Page[] {Page.SETTINGS}
                    : new Page[0];
            final int generation = warmupGeneration;
            for (int index = 0; index < targets.length; index++) {
                Page target = targets[index];
                if (pageCache.containsKey(target)) continue;
                final Runnable[] holder = new Runnable[1];
                holder[0] = () -> {
                    warmupCallbacks.remove(holder[0]);
                    if (generation != warmupGeneration || page != stable
                            || isTransitioning() || pageCache.containsKey(target)) return;
                    View warmed = renderIdentified(target);
                    cachePage(target, warmed);
                };
                warmupCallbacks.add(holder[0]);
                if (immediate) {
                    // Build on the next available UI-loop cycle. The host is idle here
                    // (plain swap, cold start, restore), so this never fights a gesture
                    // frame. View.post was chosen over postOnAnimation deliberately:
                    // GestureHost.cancelPageWarmups() removes pending warmups via
                    // removeCallbacks(action), which unsticks Handler-queued runnables
                    // but not Choreographer frame callbacks. With post(), a DOWN that
                    // arrives before the build runs still cancels it cleanly.
                    post(holder[0]);
                } else {
                    postDelayed(holder[0], NEIGHBOR_WARMUP_SETTLE_DELAY_MS
                            + index * NEIGHBOR_WARMUP_STEP_MS);
                }
            }
        }

        private void postWarmNeighbor(Page stable) {
            postWarmNeighbor(stable, false);
        }

        private void cancelPageWarmups() {
            warmupGeneration++;
            for (Runnable callback : warmupCallbacks) removeCallbacks(callback);
            warmupCallbacks.clear();
        }
    }
}
