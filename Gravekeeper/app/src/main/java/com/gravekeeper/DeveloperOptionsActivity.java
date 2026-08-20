package com.gravekeeper;

import android.app.Activity;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.VelocityTracker;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.gravekeeper.config.ConfigStore;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** Formal developer options. Account-recognition engineering parameters remain frozen. */
public final class DeveloperOptionsActivity extends Activity implements UiKit.SpatialGestureGate {
    private static final int REQUEST_DIAGNOSTIC_DESTINATION = 4101;
    private static final String[] OFF_ON = {"关闭", "开启"};

    private UiKit ui;
    private ConfigStore store;
    private FrameLayout stage;
    private String detailKey;
    private float touchX;
    private float touchY;
    private int touchSlop;
    private boolean pageGestureEligible;
    private boolean pageDragging;
    private float pageDragX;
    private View pageOutgoing;
    private View pageIncoming;
    private VelocityTracker pageVelocity;
    private int pageTransitionGeneration;

    private interface NumberMutation {
        void apply(JSONObject json, double value) throws JSONException, IOException;
    }

    private interface BooleanMutation {
        void apply(JSONObject json, boolean value) throws JSONException, IOException;
    }

    @Override protected void onCreate(Bundle state) {
        UiKit.applyPreferredTheme(this);
        super.onCreate(state);
        ui = new UiKit(this);
        store = new ConfigStore(this);
        touchSlop = ViewConfiguration.get(this).getScaledTouchSlop();
        stage = new FrameLayout(this);
        stage.setBackgroundColor(ui.p.bg);
        ui.applySystemInsets(stage);
        setContentView(ui.spatialRoot(stage));
        showRoot(false);
        BackNavigation.register(this, this::handleBack);
    }

    private View rootPage() {
        LinearLayout root = ui.pageColumn();
        TextView title = ui.text("开发者选项", 19.2f, ui.p.ink, true);
        title.setGravity(Gravity.CENTER);
        root.addView(title, ui.margins(0, 2, 0, 4));
        TextView introduction = ui.text(
                "精确参数会直接影响识别稳定性、耗电和保护动作",
                12.5f, ui.p.muted, false);
        introduction.setGravity(Gravity.CENTER);
        root.addView(introduction, ui.margins(12, 0, 12, 22));

        addRootGroup(root, "媒体与证据", "只显示开发者层独有的精确设置",
                new String[][] {
                        {"media", "媒体精确策略", "抖音与快手的短视频、直播参数"},
                        {"identity", "内容切换与证据隔离", "内容身份、确认帧和重置窗口"},
                        {"foreground", "前台 App 与窗口判断", "目标证据、UsageStats 与窗口边界"}
                });
        addRootGroup(root, "执行与保护", null,
                new String[][] {
                        {"swipe", "自动划走与结果验证", "机械坐标、持续时间、重试与熔断"},
                        {"performance", "性能与故障保护精确值", "采样基准、错误暂停和设备阈值"},
                        {"signals", "风险偏置、词表与价格规则", "底层权重、基础词汇与结构化规则"}
                });
        addRootGroup(root, "调试与配置", null,
                new String[][] {
                        {"overlay", "状态覆盖层", "调试信息显示范围与不透明度"},
                        {"config", "配置管理", "校验、导入导出和分区恢复"},
                        {"diagnostics", "本机只读诊断", "权限、版本、耗时和错误状态"}
                });
        TextView boundary = ui.text(
                "账户识别机制与直播白名单语义沿用 0.5.4，不开放账号区域、锚点、正则、前缀匹配或 OCR 容错参数",
                11.8f, ui.p.muted, false);
        boundary.setGravity(Gravity.CENTER);
        boundary.setPadding(ui.dp(15), ui.dp(6), ui.dp(15), 0);
        root.addView(boundary, ui.margins(0, 0, 0, 0));
        return ui.scroll(root);
    }

    private void addRootGroup(LinearLayout root, String title, String detail,
            String[][] entries) {
        root.addView(ui.heading(title, detail), ui.margins(0, 0, 0, 10));
        LinearLayout surface = ui.surface();
        for (int i = 0; i < entries.length; i++) {
            String key = entries[i][0];
            surface.addView(ui.entry(entries[i][1], entries[i][2], "→",
                    ignored -> showDetail(key, true)));
            if (i < entries.length - 1) {
                surface.addView(ui.divider(), fullDivider());
            }
        }
        root.addView(surface, ui.margins(0, 0, 0, 22));
    }

    private View detailPage(String key) {
        LinearLayout root = ui.pageColumn();
        try {
            JSONObject json = store.loadJson();
            switch (key) {
                case "media": buildMedia(root, json); break;
                case "identity": buildIdentity(root, json); break;
                case "foreground": buildForeground(root, json); break;
                case "swipe": buildSwipe(root, json); break;
                case "performance": buildPerformance(root, json); break;
                case "signals": buildSignals(root, json); break;
                case "overlay": buildOverlay(root, json); break;
                case "config": buildConfig(root); break;
                case "diagnostics": buildDiagnostics(root); break;
                default: addPageTitle(root, "开发者选项", null);
            }
        } catch (IOException | JSONException error) {
            addPageTitle(root, "无法读取开发者设置", null);
            root.addView(ui.plainTextSurface(safeMessage(error)));
        }
        return ui.scroll(root);
    }

    private void buildMedia(LinearLayout root, JSONObject json) throws JSONException {
        addPageTitle(root, "媒体精确策略", "分别调整抖音和快手的短视频、直播底层参数");
        JSONArray platforms = json.getJSONArray("platforms");
        for (int i = 0; i < platforms.length(); i++) {
            JSONObject platform = platforms.getJSONObject(i);
            String id = platform.getString("id");
            String platformName = id.equals("douyin") ? "抖音" : id.equals("kuaishou") ? "快手" : platform.optString("name");
            for (String mediaKey : new String[] {"short_video", "live"}) {
                JSONObject media = platform.getJSONObject(mediaKey);
                String mediaName = mediaKey.equals("short_video") ? "短视频" : "直播";
                String sectionTitle = platformName + mediaName;
                LinearLayout surface = ui.surface();
                addNumber(surface, "低风险阈值", null,
                        media.getJSONObject("low").getDouble("threshold"), 0.01, 0.99, 0.01, 2, "",
                        (candidate, value) -> media(candidate, id, mediaKey)
                                .getJSONObject("low").put("threshold", value));
                addNumber(surface, "中风险阈值", null,
                        media.getJSONObject("medium").getDouble("threshold"), 0.01, 0.99, 0.01, 2, "",
                        (candidate, value) -> media(candidate, id, mediaKey)
                                .getJSONObject("medium").put("threshold", value));
                addNumber(surface, "高风险阈值", null,
                        media.getJSONObject("high").getDouble("threshold"), 0.01, 0.99, 0.01, 2, "",
                        (candidate, value) -> media(candidate, id, mediaKey)
                                .getJSONObject("high").put("threshold", value));
                addNumber(surface, "截屏间隔", "越短越及时，也会增加耗电",
                        media.getLong("capture_interval_ms"), 500, 30000, 100, 0, "ms",
                        (candidate, value) -> media(candidate, id, mediaKey)
                                .put("capture_interval_ms", Math.round(value)));
                addNumber(surface, "OCR 间隔", "控制文字识别的最低等待时间",
                        media.getLong("ocr_interval_ms"), 500, 60000, 100, 0, "ms",
                        (candidate, value) -> media(candidate, id, mediaKey)
                                .put("ocr_interval_ms", Math.round(value)));
                addNumber(surface, "证据帧数", "达到帧数后才完成风险确认",
                        media.getInt("evidence_frames"), 1, 20, 1, 0, "帧",
                        (candidate, value) -> media(candidate, id, mediaKey)
                                .put("evidence_frames", Math.round(value)));
                addNumber(surface, "证据失效时间", "超过此时间后旧证据不再聚合",
                        media.getLong("evidence_reset_gap_ms"), 1000, 120000, 500, 0, "ms",
                        (candidate, value) -> media(candidate, id, mediaKey)
                                .put("evidence_reset_gap_ms", Math.round(value)));
                String aggregation = media.getString("evidence_aggregation");
                int aggregationIndex = aggregation.equals("AVERAGE") ? 1 : aggregation.equals("LATEST") ? 2 : 0;
                surface.addView(ui.controlRow("证据聚合", null,
                        new String[] {"MAX", "AVERAGE", "LATEST"}, aggregationIndex,
                        value -> saveBoolean((candidate, ignored) -> media(candidate, id, mediaKey)
                                .put("evidence_aggregation", value == 0 ? "MAX" : value == 1 ? "AVERAGE" : "LATEST"), true)));
                addNumber(surface, "媒体风险偏置", "只调整此媒体形态的最终风险分数",
                        media.getDouble("risk_bias"), -1, 1, 0.01, 2, "",
                        (candidate, value) -> media(candidate, id, mediaKey).put("risk_bias", value));
                addSection(root, sectionTitle,
                        "阈值必须保持低风险不高于中风险，中风险不高于高风险", surface);
            }
        }
    }

    private void buildIdentity(LinearLayout root, JSONObject json) throws JSONException {
        addPageTitle(root, "内容切换与证据隔离", "控制新内容确认和旧证据释放的工程边界");
        JSONObject identity = json.getJSONObject("content_identity");
        LinearLayout content = ui.surface();
        addBoolean(content, "内容身份识别", null, identity.optBoolean("enabled", true),
                (candidate, value) -> candidate.getJSONObject("content_identity").put("enabled", value));
        addNumber(content, "视觉变化阈值", "画面变化超过此值时建立新候选",
                identity.getDouble("visual_change_threshold"), 0.02, 0.80, 0.01, 2, "",
                objectField("content_identity", "visual_change_threshold"));
        addNumber(content, "候选相似阈值", "连续候选足够相似才视为同一内容",
                identity.getDouble("candidate_similarity_threshold"), 0.01, 0.50, 0.01, 2, "",
                objectField("content_identity", "candidate_similarity_threshold"));
        addNumber(content, "确认帧数", "候选连续出现多少帧后确认切换",
                identity.getInt("confirmation_frames"), 1, 5, 1, 0, "帧",
                roundedObjectField("content_identity", "confirmation_frames"));
        addNumber(content, "最短重置间隔", "限制短时间内重复释放证据",
                identity.getLong("minimum_reset_interval_ms"), 0, 30000, 100, 0, "ms",
                roundedObjectField("content_identity", "minimum_reset_interval_ms"));
        addSection(root, "内容身份", "在画面切换后建立新的证据窗口", content);

        JSONObject scroll = json.getJSONObject("content_change_events").getJSONObject("target_scroll");
        LinearLayout scrolling = ui.surface();
        addBoolean(scrolling, "目标滚动事件", null, scroll.optBoolean("enabled", true),
                (candidate, value) -> targetScroll(candidate).put("enabled", value));
        addBoolean(scrolling, "要求最近触摸", null, scroll.optBoolean("requires_recent_touch", true),
                (candidate, value) -> targetScroll(candidate).put("requires_recent_touch", value));
        addNumber(scrolling, "触摸有效时间", null, scroll.getLong("recent_touch_window_ms"),
                0, 10000, 100, 0, "ms",
                (candidate, value) -> targetScroll(candidate).put("recent_touch_window_ms", Math.round(value)));
        addNumber(scrolling, "滚动去抖时间", null, scroll.getLong("debounce_ms"),
                0, 10000, 100, 0, "ms",
                (candidate, value) -> targetScroll(candidate).put("debounce_ms", Math.round(value)));
        addNumber(scrolling, "来源最小宽度", null, scroll.getDouble("minimum_source_width_ratio"),
                0.20, 1, 0.01, 2, "",
                (candidate, value) -> targetScroll(candidate).put("minimum_source_width_ratio", value));
        addNumber(scrolling, "来源最小高度", null, scroll.getDouble("minimum_source_height_ratio"),
                0.20, 1, 0.01, 2, "",
                (candidate, value) -> targetScroll(candidate).put("minimum_source_height_ratio", value));
        addSection(root, "滚动事件", "用最近触摸和主页面证据约束内容切换事件", scrolling);
    }

    private void buildForeground(LinearLayout root, JSONObject json) throws JSONException {
        addPageTitle(root, "前台 App 与窗口判断", "账户识别内部参数固定，不在任何设置页面开放");
        JSONObject foreground = json.getJSONObject("foreground_detection");
        LinearLayout evidence = ui.surface();
        addBoolean(evidence, "UsageStats 后备", null,
                foreground.optBoolean("usage_stats_fallback_enabled", true),
                (candidate, value) -> candidate.getJSONObject("foreground_detection")
                        .put("usage_stats_fallback_enabled", value));
        addNumber(evidence, "目标证据有效时间", null,
                foreground.getLong("recent_target_evidence_ttl_ms"), 500, 86400000, 60000, 0, "ms",
                roundedObjectField("foreground_detection", "recent_target_evidence_ttl_ms"));
        addNumber(evidence, "UsageStats 回看时间", null,
                foreground.getLong("usage_event_lookback_ms"), 60000, 604800000, 60000, 0, "ms",
                roundedObjectField("foreground_detection", "usage_event_lookback_ms"));
        addNumber(evidence, "事件最大年龄", null,
                foreground.getLong("usage_event_max_age_ms"), 1000, 300000, 1000, 0, "ms",
                roundedObjectField("foreground_detection", "usage_event_max_age_ms"));
        addSection(root, "前台目标证据", "控制目标 App 证据保留和 UsageStats 后备", evidence);

        JSONObject window = json.getJSONObject("multi_window");
        LinearLayout bounds = ui.surface();
        addBoolean(bounds, "全屏窗口", null, window.optBoolean("fullscreen_enabled", true),
                objectBoolean("multi_window", "fullscreen_enabled"));
        addBoolean(bounds, "分屏窗口", null, window.optBoolean("split_screen_enabled", true),
                objectBoolean("multi_window", "split_screen_enabled"));
        addBoolean(bounds, "画中画窗口", null, window.optBoolean("picture_in_picture_enabled", false),
                objectBoolean("multi_window", "picture_in_picture_enabled"));
        addBoolean(bounds, "悬浮窗口", null, window.optBoolean("floating_window_enabled", false),
                objectBoolean("multi_window", "floating_window_enabled"));
        addBoolean(bounds, "要求目标窗口聚焦", null, window.optBoolean("require_target_window_focused", true),
                objectBoolean("multi_window", "require_target_window_focused"));
        addNumber(bounds, "全屏最小宽度", null, window.getDouble("minimum_fullscreen_width_ratio"),
                0.50, 1, 0.01, 2, "", objectField("multi_window", "minimum_fullscreen_width_ratio"));
        addNumber(bounds, "全屏最小高度", null, window.getDouble("minimum_fullscreen_height_ratio"),
                0.50, 1, 0.01, 2, "", objectField("multi_window", "minimum_fullscreen_height_ratio"));
        addNumber(bounds, "分屏最小跨度", null, window.getDouble("minimum_split_span_ratio"),
                0.50, 1, 0.01, 2, "", objectField("multi_window", "minimum_split_span_ratio"));
        addNumber(bounds, "手势边缘留白", null, window.getDouble("gesture_edge_padding_ratio"),
                0, 0.20, 0.01, 2, "", objectField("multi_window", "gesture_edge_padding_ratio"));
        addSection(root, "窗口边界", "只开放窗口模式和几何阈值", bounds);

        JSONArray ignoredPackages = foreground.getJSONArray("ignored_overlay_packages");
        LinearLayout packageEditor = ui.column();
        EditText packages = new EditText(this);
        packages.setText(joinLines(ignoredPackages));
        packages.setTextSize(ui.scaledTextSp(14));
        packages.setTextColor(ui.p.ink);
        packages.setHintTextColor(ui.p.muted);
        packages.setGravity(Gravity.TOP | Gravity.START);
        packages.setMinLines(3);
        packages.setMaxLines(7);
        packages.setPadding(ui.dp(13), ui.dp(11), ui.dp(13), ui.dp(11));
        packages.setBackground(ui.inputFieldDrawable());
        packages.setContentDescription("忽略覆盖层包名");
        packageEditor.addView(packages, new LinearLayout.LayoutParams(-1, -2));
        TextView savePackages = ui.capsule("保存包名列表", false,
                ignored -> saveIgnoredOverlayPackages(packages.getText().toString()));
        packageEditor.addView(savePackages, ui.margins(28, 14, 28, 0));
        addSection(root, "忽略覆盖层包名", "每行一个包名，保存时统一校验", packageEditor);
    }

    private void buildSwipe(LinearLayout root, JSONObject json) throws JSONException {
        addPageTitle(root, "自动划走与结果验证", "精确控制手势轨迹、验证窗口、重试和熔断");
        LinearLayout mechanics = ui.surface();
        addNumber(mechanics, "动作冷却时间", null, json.getLong("swipe_cooldown_ms"),
                500, 30000, 100, 0, "ms", rootRoundedField("swipe_cooldown_ms"));
        addNumber(mechanics, "手势持续时间", null, json.getLong("swipe_duration_ms"),
                100, 3000, 50, 0, "ms", rootRoundedField("swipe_duration_ms"));
        JSONObject gesture = json.getJSONObject("swipe_gesture");
        addNumber(mechanics, "横向位置", null, gesture.getDouble("x_ratio"),
                0.05, 0.95, 0.01, 2, "", objectField("swipe_gesture", "x_ratio"));
        addNumber(mechanics, "起点高度", null, gesture.getDouble("start_y_ratio"),
                0.05, 0.95, 0.01, 2, "", objectField("swipe_gesture", "start_y_ratio"));
        addNumber(mechanics, "终点高度", null, gesture.getDouble("end_y_ratio"),
                0.05, 0.95, 0.01, 2, "", objectField("swipe_gesture", "end_y_ratio"));
        addSection(root, "机械参数", "坐标使用屏幕宽高比例", mechanics);

        JSONObject verification = json.getJSONObject("swipe_verification");
        LinearLayout verify = ui.surface();
        addBoolean(verify, "结果验证", null, verification.optBoolean("enabled", true),
                objectBoolean("swipe_verification", "enabled"));
        addBoolean(verify, "要求已知媒体", null,
                verification.optBoolean("swipe_requires_known_media", true),
                objectBoolean("swipe_verification", "swipe_requires_known_media"));
        addNumber(verify, "验证超时", null, verification.getLong("timeout_ms"),
                1000, 30000, 100, 0, "ms", roundedObjectField("swipe_verification", "timeout_ms"));
        addNumber(verify, "视觉变化阈值", null, verification.getDouble("visual_change_threshold"),
                0.02, 0.80, 0.01, 2, "", objectField("swipe_verification", "visual_change_threshold"));
        addNumber(verify, "候选相似阈值", null, verification.getDouble("candidate_similarity_threshold"),
                0.01, 0.50, 0.01, 2, "", objectField("swipe_verification", "candidate_similarity_threshold"));
        addNumber(verify, "确认帧数", null, verification.getInt("confirmation_frames"),
                1, 5, 1, 0, "帧", roundedObjectField("swipe_verification", "confirmation_frames"));
        addNumber(verify, "最大重试次数", null, verification.getInt("maximum_retries"),
                0, 3, 1, 0, "次", roundedObjectField("swipe_verification", "maximum_retries"));
        addNumber(verify, "失败熔断时间", null, verification.getLong("failure_circuit_breaker_ms"),
                1000, 600000, 1000, 0, "ms", roundedObjectField("swipe_verification", "failure_circuit_breaker_ms"));
        addNumber(verify, "用户触摸避让", null, verification.getLong("avoid_user_touch_ms"),
                0, 10000, 100, 0, "ms", roundedObjectField("swipe_verification", "avoid_user_touch_ms"));
        addSection(root, "结果验证", "动作后确认内容已切换，失败时受重试和熔断限制", verify);
    }

    private void buildPerformance(LinearLayout root, JSONObject json) throws JSONException {
        addPageTitle(root, "性能与故障保护精确值", "高级设置中的产品级开关不会在此重复出现");
        JSONObject load = json.getJSONObject("load_protection");
        LinearLayout failures = ui.surface();
        addNumber(failures, "连续错误上限", null, load.getInt("max_consecutive_errors"),
                1, 20, 1, 0, "次", roundedObjectField("load_protection", "max_consecutive_errors"));
        addNumber(failures, "错误暂停时间", null, load.getLong("error_pause_ms"),
                1000, 600000, 1000, 0, "ms", roundedObjectField("load_protection", "error_pause_ms"));
        addNumber(failures, "最低电量阈值", "未充电且低于此值时触发保护",
                load.getInt("minimum_battery_percent_while_not_charging"), 1, 50, 1, 0, "%",
                roundedObjectField("load_protection", "minimum_battery_percent_while_not_charging"));
        addNumber(failures, "OCR 超时时间", null, json.getLong("ocr_timeout_ms"),
                300, 10000, 100, 0, "ms", rootRoundedField("ocr_timeout_ms"));
        addSection(root, "故障保护", "只调整错误暂停和技术阈值", failures);

        JSONObject performance = json.getJSONObject("performance");
        LinearLayout benchmark = ui.surface();
        addNumber(benchmark, "画面采样次数", null, performance.getInt("sample_count"),
                5, 200, 1, 0, "次", roundedObjectField("performance", "sample_count"));
        addNumber(benchmark, "OCR 采样次数", null, performance.getInt("ocr_sample_count"),
                1, 20, 1, 0, "次", roundedObjectField("performance", "ocr_sample_count"));
        addNumber(benchmark, "持续压力时间", null, performance.getLong("sustained_duration_ms"),
                2000, 120000, 500, 0, "ms", roundedObjectField("performance", "sustained_duration_ms"));
        addNumber(benchmark, "建议 P95 耗时", null, performance.getLong("recommended_p95_ms"),
                100, 10000, 50, 0, "ms", roundedObjectField("performance", "recommended_p95_ms"));
        addNumber(benchmark, "降级 P95 耗时", null, performance.getLong("degraded_p95_ms"),
                100, 30000, 50, 0, "ms", roundedObjectField("performance", "degraded_p95_ms"));
        addNumber(benchmark, "建议 OCR P95", null, performance.getLong("recommended_ocr_p95_ms"),
                100, 10000, 50, 0, "ms", roundedObjectField("performance", "recommended_ocr_p95_ms"));
        addNumber(benchmark, "降级 OCR P95", null, performance.getLong("degraded_ocr_p95_ms"),
                100, 30000, 50, 0, "ms", roundedObjectField("performance", "degraded_ocr_p95_ms"));
        addNumber(benchmark, "建议峰值内存", null, performance.getLong("recommended_peak_pss_mb"),
                64, 4096, 10, 0, "MB", roundedObjectField("performance", "recommended_peak_pss_mb"));
        addNumber(benchmark, "降级峰值内存", null, performance.getLong("degraded_peak_pss_mb"),
                64, 8192, 10, 0, "MB", roundedObjectField("performance", "degraded_peak_pss_mb"));
        addSection(root, "性能检查基准", "用于生成建议，不会禁止用户开启保护", benchmark);
    }

    private void buildSignals(LinearLayout root, JSONObject json) throws JSONException {
        addPageTitle(root, "风险偏置、词表与价格规则", "词表集中编辑，结构化价格规则单独校验");
        JSONObject signals = json.getJSONObject("signals");
        LinearLayout biases = ui.surface();
        addNumber(biases, "全球购风险偏置", null, signals.getDouble("global_purchase_bias"),
                -1, 1, 0.01, 2, "", objectField("signals", "global_purchase_bias"));
        addNumber(biases, "负向语境偏置", null, signals.getDouble("negative_context_bias"),
                -1, 1, 0.01, 2, "", objectField("signals", "negative_context_bias"));
        JSONArray rules = signals.getJSONArray("runtime_rules");
        for (int i = 0; i < Math.min(3, rules.length()); i++) {
            JSONObject rule = rules.getJSONObject(i);
            String id = rule.getString("id");
            String label = i == 0 ? "组合规则偏置" : i == 1 ? "交易压力偏置" : "站外交易偏置";
            addNumber(biases, label, null, rule.getDouble("risk_bias"),
                    -1, 1, 0.01, 2, "", (candidate, value) -> rule(candidate, id).put("risk_bias", value));
        }
        addSection(root, "风险偏置", "正数提高风险，负数降低风险", biases);

        LinearLayout terms = ui.surface();
        terms.addView(ui.entry("集中编辑基础词汇表",
                "所有词表放在同一个编辑框中，按分类行保存", "→",
                ignored -> editTerms()));
        addSection(root, "基础词汇表", "全部词条集中在同一个编辑框中", terms);

        LinearLayout price = ui.surface();
        String expression = signals.getJSONObject("fusion_rule_features").getString("price_regex");
        price.addView(ui.entry("价格识别表达式", expression, "→", ignored ->
                ui.inputValidated("编辑价格识别表达式",
                        "保存前会完整校验正则表达式；格式错误不会生效。",
                        expression, false, this::savePriceExpression)));
        addSection(root, "价格规则", "结构化规则不会与基础词表混用", price);
    }

    private void buildOverlay(LinearLayout root, JSONObject json) throws JSONException {
        addPageTitle(root, "状态覆盖层", "覆盖层只用于本机调试，不写入截图或 OCR 正文");
        JSONObject overlay = json.getJSONObject("status_overlay");
        LinearLayout surface = ui.surface();
        addBoolean(surface, "状态覆盖层", null, overlay.optBoolean("enabled", false),
                objectBoolean("status_overlay", "enabled"));
        addBoolean(surface, "目标 App 外显示", null,
                overlay.optBoolean("show_outside_targets", false),
                (candidate, value) -> {
                    JSONObject target = candidate.getJSONObject("status_overlay");
                    target.put("show_outside_targets", target.optBoolean("enabled", false) && value);
                });
        addNumber(surface, "覆盖层不透明度", null, overlay.getDouble("opacity"),
                0.20, 1, 0.01, 2, "", objectField("status_overlay", "opacity"));
        addSection(root, "显示范围", null, surface);
        TextView close = ui.capsule("立即关闭覆盖层", false, ignored ->
                saveBoolean((candidate, value) -> {
                    candidate.getJSONObject("status_overlay").put("enabled", false);
                    candidate.getJSONObject("status_overlay").put("show_outside_targets", false);
                }, false));
        root.addView(close, ui.margins(30, 0, 30, 8));
    }

    private void buildConfig(LinearLayout root) {
        addPageTitle(root, "配置管理", "导入时自动保留不可自定义的账户识别工程参数");
        LinearLayout operations = ui.surface();
        operations.addView(ui.entry("打开工程配置编辑器",
                "校验、导入、导出和单项恢复", "→",
                ignored -> ui.startSpatial(
                        new Intent(this, ConfigEditorActivity.class), false)));
        operations.addView(ui.divider(), fullDivider());
        operations.addView(ui.entry("恢复上一份有效配置",
                store.hasLastGood() ? "可以恢复" : "当前没有可恢复版本", null,
                ignored -> restoreLastGood()));
        operations.addView(ui.divider(), fullDivider());
        operations.addView(ui.entry("恢复风险信号与关键词", "仅恢复 signals 分区", null,
                ignored -> confirmResetSection("signals", "风险信号与关键词")));
        operations.addView(ui.divider(), fullDivider());
        operations.addView(ui.entry("恢复平台与媒体策略", "白名单账户也会恢复默认", null,
                ignored -> confirmResetSection("platforms", "平台与媒体策略")));
        addSection(root, "配置操作", "错误配置不会生效，并可恢复上一份有效配置", operations);
    }

    private void buildDiagnostics(LinearLayout root) {
        addPageTitle(root, "本机只读诊断", "只读显示技术状态，不提供开关或编辑入口");
        String report = LocalDiagnosticReport.build(this);
        Map<String, String> raw = parseDiagnosticReport(report);
        LinkedHashMap<String, String> rows = new LinkedHashMap<>();
        boolean accessibility = "true".equals(raw.get("accessibility_enabled"));
        boolean protection = "true".equals(raw.get("protection_enabled"));
        rows.put("无障碍权限", accessibility ? "已开启" : "未开启");
        rows.put("保护服务", !protection ? "已关闭"
                : accessibility ? "运行中" : "等待权限");
        rows.put("当前版本", compactVersion(raw.get("app_version")));
        rows.put("配置格式版本", fallback(raw.get("runtime_config_version")));
        rows.put("规则包版本", fallback(raw.get("rules_version")));
        rows.put("最近识别耗时", duration(raw.get("runtime_end_to_end_p95_ms")));
        rows.put("最近 OCR 耗时", duration(raw.get("ocr_p95_ms")));
        rows.put("本次会话错误", fallback(raw.get("processing_error_count")));
        rows.put("最近配置校验",
                raw.containsKey("runtime_config_version") && raw.containsKey("rules_version")
                        ? "通过" : "失败");
        addSection(root, "运行状态", null, ui.readOnlyKeyValueSurface(rows));
        TextView export = ui.capsule("导出本机诊断报告", false, ignored -> {
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("text/plain")
                    .putExtra(Intent.EXTRA_TITLE, BrandIdentity.DIAGNOSTIC_FILE_NAME);
            startActivityForResult(intent, REQUEST_DIAGNOSTIC_DESTINATION);
        });
        root.addView(export, ui.margins(30, 0, 30, 0));
    }

    private static Map<String, String> parseDiagnosticReport(String report) {
        LinkedHashMap<String, String> values = new LinkedHashMap<>();
        for (String line : report.split("\\R")) {
            int separator = line.indexOf('=');
            if (separator <= 0) continue;
            values.put(line.substring(0, separator), line.substring(separator + 1));
        }
        return values;
    }

    private static String fallback(String value) {
        return value == null || value.isBlank() ? "不可用" : value;
    }

    private static String compactVersion(String value) {
        if (value == null || value.isBlank()) return BuildConfig.VERSION_NAME;
        int code = value.indexOf(" (");
        return code > 0 ? value.substring(0, code) : value;
    }

    private static String duration(String value) {
        if (value == null || value.isBlank() || value.equals("0")) return "暂无数据";
        return value + " ms";
    }

    private void addPageTitle(LinearLayout root, String title, String detail) {
        TextView heading = ui.text(title, 20, ui.p.ink, true);
        heading.setGravity(Gravity.CENTER);
        root.addView(heading, ui.margins(8, 0, 8, detail == null ? 18 : 5));
        if (detail != null) {
            TextView copy = ui.text(detail, 12.5f, ui.p.muted, false);
            copy.setGravity(Gravity.CENTER);
            root.addView(copy, ui.margins(12, 0, 12, 18));
        }
    }

    private void addSection(LinearLayout root, String title, String detail, View body) {
        root.addView(ui.heading(title, detail), ui.margins(0, 0, 0, 10));
        root.addView(body, ui.margins(0, 0, 0, 22));
    }

    private void addBoolean(LinearLayout surface, String title, String detail,
            boolean current, BooleanMutation mutation) {
        final UiKit.SegmentControl[] control = new UiKit.SegmentControl[1];
        LinearLayout row = ui.controlRow(title, detail, OFF_ON, current ? 1 : 0,
                value -> {
                    if (!saveBoolean(mutation, value == 1) && control[0] != null) {
                        control[0].setSelected(current ? 1 : 0, true);
                    }
                });
        control[0] = (UiKit.SegmentControl) row.getChildAt(1);
        surface.addView(row);
    }

    private void addNumber(LinearLayout surface, String title, String detail,
            double current, double min, double max, double step, int decimals,
            String unit, NumberMutation mutation) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(ui.dp(detail == null ? 66 : 74));

        LinearLayout copy = ui.column();
        copy.setGravity(Gravity.CENTER);
        TextView label = ui.text(title, 14.5f, ui.p.ink, true);
        label.setGravity(Gravity.CENTER);
        copy.addView(label, ui.matchWrap());
        if (detail != null && !detail.isBlank()) {
            TextView explanation = ui.text(detail, 11.2f, ui.p.muted, false);
            explanation.setGravity(Gravity.CENTER);
            copy.addView(explanation, ui.margins(0, 3, 0, 0));
        }
        LinearLayout.LayoutParams copyParams = new LinearLayout.LayoutParams(0, -2, 1.1f);
        copyParams.rightMargin = ui.dp(9);
        row.addView(copy, copyParams);

        String display = format(current, decimals) + (unit.isBlank() ? "" : " " + unit);
        Runnable edit = () -> ui.inputValidated("编辑" + title,
                "允许范围：" + format(min, decimals) + " 至 " + format(max, decimals)
                        + (unit.isBlank() ? "" : " " + unit),
                format(current, decimals), true, raw -> {
                    try {
                        double parsed = Double.parseDouble(raw.trim());
                        if (parsed < min || parsed > max) {
                            ui.error("数值必须在允许范围内");
                            return false;
                        }
                        return saveNumber(mutation, parsed);
                    } catch (NumberFormatException error) {
                        ui.error("请输入有效数值");
                        return false;
                    }
                });
        UiKit.PreciseValueControl stepper = ui.preciseValue(display,
                () -> saveNumber(mutation, clamp(current - step, min, max)), edit,
                () -> saveNumber(mutation, clamp(current + step, min, max)));
        row.addView(stepper, new LinearLayout.LayoutParams(0, ui.dp(61), 2.25f));
        surface.addView(row);
    }

    private boolean saveNumber(NumberMutation mutation, double value) {
        String activeDetail = detailKey;
        try {
            JSONObject json = store.loadJson();
            mutation.apply(json, value);
            store.save(json);
            refreshDetailIfCurrent(activeDetail);
            return true;
        } catch (IOException | JSONException error) {
            ui.error("保存失败：" + safeMessage(error));
            return false;
        }
    }

    private boolean saveBoolean(BooleanMutation mutation, boolean value) {
        try {
            JSONObject json = store.loadJson();
            mutation.apply(json, value);
            store.save(json);
            return true;
        } catch (IOException | JSONException error) {
            ui.error("保存失败：" + safeMessage(error));
            return false;
        }
    }

    private void editTerms() {
        try {
            String initial = serializeTerms(store.loadJson());
            ui.inputValidated("集中编辑基础词汇表",
                    "每行一个分类，格式为“分类=词条1,词条2”。不可删除分类名；账户识别词不在这里开放。",
                    initial, false, this::saveTerms);
        } catch (IOException | JSONException error) {
            ui.error("读取词表失败：" + safeMessage(error));
        }
    }

    private String serializeTerms(JSONObject json) throws JSONException {
        JSONObject signals = json.getJSONObject("signals");
        JSONObject fusion = signals.getJSONObject("fusion_rule_features");
        StringBuilder text = new StringBuilder();
        Map<String, JSONArray> groups = new LinkedHashMap<>();
        groups.put("全球购", signals.getJSONArray("global_purchase_terms"));
        groups.put("负向语境", signals.getJSONArray("negative_context_terms"));
        groups.put("直播", signals.getJSONArray("live_terms"));
        groups.put("短视频", signals.getJSONArray("short_video_terms"));
        groups.put("健康", fusion.getJSONArray("health_terms"));
        groups.put("销售", fusion.getJSONArray("sales_terms"));
        groups.put("老年", fusion.getJSONArray("elderly_terms"));
        groups.put("规则负向", fusion.getJSONArray("negative_context_terms"));
        groups.put("购物车", fusion.getJSONArray("shopping_cart_terms"));
        groups.put("下单提示", fusion.getJSONArray("order_prompt_terms"));
        groups.put("采集覆盖层", fusion.getJSONArray("collector_overlay_terms"));
        groups.put("黑屏遮挡", fusion.getJSONArray("black_occlusion_terms"));
        groups.put("加载空白", fusion.getJSONArray("loading_or_blank_terms"));
        for (Map.Entry<String, JSONArray> entry : groups.entrySet()) {
            if (text.length() > 0) text.append('\n');
            text.append(entry.getKey()).append('=');
            JSONArray values = entry.getValue();
            for (int i = 0; i < values.length(); i++) {
                if (i > 0) text.append(',');
                text.append(values.getString(i));
            }
        }
        return text.toString();
    }

    private boolean saveTerms(String raw) {
        try {
            Map<String, String> lines = new LinkedHashMap<>();
            for (String line : raw.split("\\R")) {
                if (line.trim().isEmpty()) continue;
                int split = line.indexOf('=');
                if (split <= 0) throw new IllegalArgumentException("词表行缺少 =");
                lines.put(line.substring(0, split).trim(), line.substring(split + 1).trim());
            }
            String[] required = {"全球购", "负向语境", "直播", "短视频", "健康", "销售",
                    "老年", "规则负向", "购物车", "下单提示", "采集覆盖层", "黑屏遮挡", "加载空白"};
            for (String label : required) {
                if (!lines.containsKey(label) || lines.get(label).isBlank()) {
                    throw new IllegalArgumentException("缺少分类：" + label);
                }
            }
            JSONObject json = store.loadJson();
            JSONObject signals = json.getJSONObject("signals");
            JSONObject fusion = signals.getJSONObject("fusion_rule_features");
            signals.put("global_purchase_terms", termsArray(lines.get("全球购")));
            signals.put("negative_context_terms", termsArray(lines.get("负向语境")));
            signals.put("live_terms", termsArray(lines.get("直播")));
            signals.put("short_video_terms", termsArray(lines.get("短视频")));
            fusion.put("health_terms", termsArray(lines.get("健康")));
            fusion.put("sales_terms", termsArray(lines.get("销售")));
            fusion.put("elderly_terms", termsArray(lines.get("老年")));
            fusion.put("negative_context_terms", termsArray(lines.get("规则负向")));
            fusion.put("shopping_cart_terms", termsArray(lines.get("购物车")));
            fusion.put("order_prompt_terms", termsArray(lines.get("下单提示")));
            fusion.put("collector_overlay_terms", termsArray(lines.get("采集覆盖层")));
            fusion.put("black_occlusion_terms", termsArray(lines.get("黑屏遮挡")));
            fusion.put("loading_or_blank_terms", termsArray(lines.get("加载空白")));
            store.save(json);
            ui.message("基础词汇表已保存");
            String activeDetail = detailKey;
            stage.postDelayed(() -> refreshDetailIfCurrent(activeDetail),
                    ui.prefs.reduceMotion() ? 130 : 340);
            return true;
        } catch (IOException | JSONException | IllegalArgumentException error) {
            ui.error("词表未保存：" + safeMessage(error));
            return false;
        }
    }

    private boolean savePriceExpression(String raw) {
        String expression = raw.trim();
        if (expression.isEmpty()) {
            ui.error("价格识别表达式不能为空");
            return false;
        }
        try {
            Pattern.compile(expression);
            JSONObject json = store.loadJson();
            json.getJSONObject("signals").getJSONObject("fusion_rule_features")
                    .put("price_regex", expression);
            store.save(json);
            ui.message("价格识别表达式已保存");
            String activeDetail = detailKey;
            stage.postDelayed(() -> refreshDetailIfCurrent(activeDetail),
                    ui.prefs.reduceMotion() ? 130 : 340);
            return true;
        } catch (PatternSyntaxException error) {
            ui.error("正则表达式格式错误：" + error.getDescription());
            return false;
        } catch (IOException | JSONException error) {
            ui.error("保存失败：" + safeMessage(error));
            return false;
        }
    }

    private boolean saveIgnoredOverlayPackages(String raw) {
        try {
            LinkedHashSet<String> packages = new LinkedHashSet<>();
            Pattern packageName = Pattern.compile(
                    "^[A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)*$");
            for (String line : raw.split("\\R")) {
                String value = line.trim();
                if (value.isEmpty()) continue;
                if (!packageName.matcher(value).matches()) {
                    throw new IllegalArgumentException("包名格式错误：" + value);
                }
                packages.add(value);
            }
            if (packages.isEmpty()) throw new IllegalArgumentException("至少保留一个包名");
            JSONArray values = new JSONArray();
            for (String value : packages) values.put(value);
            JSONObject json = store.loadJson();
            json.getJSONObject("foreground_detection")
                    .put("ignored_overlay_packages", values);
            store.save(json);
            ui.message("忽略覆盖层包名已保存");
            String activeDetail = detailKey;
            stage.postDelayed(() -> refreshDetailIfCurrent(activeDetail),
                    ui.prefs.reduceMotion() ? 130 : 340);
            return true;
        } catch (IOException | JSONException | IllegalArgumentException error) {
            ui.error("包名列表未保存：" + safeMessage(error));
            return false;
        }
    }

    private static String joinLines(JSONArray values) throws JSONException {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < values.length(); i++) {
            if (result.length() > 0) result.append('\n');
            result.append(values.getString(i));
        }
        return result.toString();
    }

    private JSONArray termsArray(String value) {
        JSONArray result = new JSONArray();
        for (String term : value.split("[,，]")) {
            String normalized = term.trim();
            if (!normalized.isEmpty()) result.put(normalized);
        }
        return result;
    }

    private void restoreLastGood() {
        if (!store.hasLastGood()) {
            ui.error("当前没有上一份有效配置");
            return;
        }
        ui.confirm("恢复上一份有效配置？", "当前配置会成为新的可恢复版本。",
                "恢复", false, () -> {
                    String activeDetail = detailKey;
                    try {
                        if (store.restoreLastGood()) ui.message("已恢复上一份有效配置");
                        refreshDetailIfCurrent(activeDetail);
                    } catch (IOException error) {
                        ui.error("恢复失败：" + safeMessage(error));
                    }
                });
    }

    private void confirmResetSection(String section, String label) {
        ui.confirm("恢复" + label + "？", "只恢复此分区，其余设置保持不变。",
                "恢复", true, () -> {
                    String activeDetail = detailKey;
                    try {
                        store.resetSectionToDefault(section);
                        if ("platforms".equals(section)) {
                            WhitelistAccountsPage.clearUiState(this);
                        }
                        ui.message("已恢复" + label);
                        refreshDetailIfCurrent(activeDetail);
                    } catch (IOException error) {
                        ui.error("恢复失败：" + safeMessage(error));
                    }
                });
    }

    private NumberMutation objectField(String object, String field) {
        return (json, value) -> json.getJSONObject(object).put(field, value);
    }

    private NumberMutation roundedObjectField(String object, String field) {
        return (json, value) -> json.getJSONObject(object).put(field, Math.round(value));
    }

    private NumberMutation rootRoundedField(String field) {
        return (json, value) -> json.put(field, Math.round(value));
    }

    private BooleanMutation objectBoolean(String object, String field) {
        return (json, value) -> json.getJSONObject(object).put(field, value);
    }

    private static JSONObject targetScroll(JSONObject json) throws JSONException {
        return json.getJSONObject("content_change_events").getJSONObject("target_scroll");
    }

    private static JSONObject media(JSONObject json, String platformId, String mediaKey)
            throws JSONException {
        return platform(json, platformId).getJSONObject(mediaKey);
    }

    private static JSONObject platform(JSONObject json, String id) throws JSONException {
        JSONArray platforms = json.getJSONArray("platforms");
        for (int i = 0; i < platforms.length(); i++) {
            JSONObject platform = platforms.getJSONObject(i);
            if (id.equals(platform.optString("id"))) return platform;
        }
        throw new JSONException("找不到平台：" + id);
    }

    private static JSONObject rule(JSONObject json, String id) throws JSONException {
        JSONArray rules = json.getJSONObject("signals").getJSONArray("runtime_rules");
        for (int i = 0; i < rules.length(); i++) {
            JSONObject rule = rules.getJSONObject(i);
            if (id.equals(rule.optString("id"))) return rule;
        }
        throw new JSONException("找不到规则：" + id);
    }

    private void showRoot(boolean animate) {
        detailKey = null;
        replacePage(rootPage(), animate, true);
    }

    private void showDetail(String key, boolean animate) {
        if (key == null) {
            showRoot(animate);
            return;
        }
        detailKey = key;
        replacePage(detailPage(key), animate, false);
    }

    private void refreshDetailIfCurrent(String expectedKey) {
        if (expectedKey != null && expectedKey.equals(detailKey)) {
            showDetail(expectedKey, false);
        }
    }

    private void replacePage(View next, boolean animate, boolean returning) {
        stabilizePageTransition();
        if (!animate || stage.getChildCount() == 0) {
            stage.removeAllViews();
            stage.addView(next, new FrameLayout.LayoutParams(-1, -1));
            return;
        }
        int generation = ++pageTransitionGeneration;
        View old = stage.getChildAt(stage.getChildCount() - 1);
        float width = Math.max(1, stage.getWidth());
        float direction = returning ? 1 : -1;
        next.setTranslationX(-direction * width);
        stage.addView(next, new FrameLayout.LayoutParams(-1, -1));
        long duration = ui.prefs.reduceMotion() ? 120 : 420;
        next.animate().translationX(0).setDuration(duration)
                .setInterpolator(ui.motionInterpolator()).start();
        old.animate().translationX(direction * width).setDuration(duration)
                .setInterpolator(ui.motionInterpolator())
                .withEndAction(() -> {
                    if (generation == pageTransitionGeneration) stage.removeView(old);
                }).start();
    }

    private void stabilizePageTransition() {
        pageTransitionGeneration++;
        View active = pageDragging && pageOutgoing != null
                ? pageOutgoing
                : stage.getChildCount() == 0 ? null : stage.getChildAt(stage.getChildCount() - 1);
        for (int index = stage.getChildCount() - 1; index >= 0; index--) {
            View child = stage.getChildAt(index);
            child.animate().cancel();
            if (child != active) stage.removeView(child);
        }
        if (active != null) active.setTranslationX(0);
        pageOutgoing = null;
        pageIncoming = null;
        pageDragX = 0;
        pageDragging = false;
        pageGestureEligible = false;
        recyclePageVelocity();
    }

    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        if (detailKey == null && !pageDragging) return super.dispatchTouchEvent(event);
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            touchX = event.getX();
            touchY = event.getY();
            pageDragX = 0;
            pageGestureEligible = touchX >= ui.dp(20)
                    && touchX <= stage.getWidth() - ui.dp(20)
                    && !gestureExcluded(event.getRawX(), event.getRawY());
            recyclePageVelocity();
            pageVelocity = VelocityTracker.obtain();
            pageVelocity.addMovement(event);
            return super.dispatchTouchEvent(event);
        }
        if (pageVelocity != null) pageVelocity.addMovement(event);

        float dx = event.getX() - touchX;
        float dy = event.getY() - touchY;
        if (action == MotionEvent.ACTION_MOVE && pageGestureEligible && !pageDragging) {
            if (Math.max(Math.abs(dx), Math.abs(dy)) < touchSlop) {
                return super.dispatchTouchEvent(event);
            }
            if (dx <= 0 || Math.abs(dy) >= Math.abs(dx)) {
                pageGestureEligible = false;
                return super.dispatchTouchEvent(event);
            }
            beginPageDrag();
            MotionEvent cancel = MotionEvent.obtain(event);
            cancel.setAction(MotionEvent.ACTION_CANCEL);
            super.dispatchTouchEvent(cancel);
            cancel.recycle();
        }
        if (pageDragging && action == MotionEvent.ACTION_MOVE) {
            updatePageDrag(dx);
            return true;
        }
        if (pageDragging && (action == MotionEvent.ACTION_UP
                || action == MotionEvent.ACTION_CANCEL)) {
            float velocityX = 0;
            if (pageVelocity != null) {
                pageVelocity.computeCurrentVelocity(1000);
                velocityX = pageVelocity.getXVelocity();
            }
            float width = Math.max(1, stage.getWidth());
            boolean complete = action == MotionEvent.ACTION_UP
                    && (pageDragX >= width * 0.28f || velocityX >= 620f);
            settlePageDrag(complete);
            recyclePageVelocity();
            return true;
        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            pageGestureEligible = false;
            recyclePageVelocity();
        }
        return super.dispatchTouchEvent(event);
    }

    private void beginPageDrag() {
        pageDragging = true;
        pageOutgoing = stage.getChildAt(stage.getChildCount() - 1);
        pageIncoming = rootPage();
        float width = Math.max(1, stage.getWidth());
        pageIncoming.setTranslationX(-width);
        stage.addView(pageIncoming, 0, new FrameLayout.LayoutParams(-1, -1));
        updatePageDrag(0);
    }

    private void updatePageDrag(float dx) {
        float width = Math.max(1, stage.getWidth());
        pageDragX = Math.max(0, Math.min(width, dx));
        pageOutgoing.setTranslationX(pageDragX);
        pageIncoming.setTranslationX(pageDragX - width);
    }

    private void settlePageDrag(boolean complete) {
        float width = Math.max(1, stage.getWidth());
        float target = complete ? width : 0;
        float remaining = Math.abs(target - pageDragX) / width;
        long duration = ui.prefs.reduceMotion() ? 120
                : Math.max(130, Math.round(90 + remaining * 330));
        pageIncoming.animate().translationX(complete ? 0 : -width)
                .setDuration(duration).setInterpolator(ui.motionInterpolator()).start();
        pageOutgoing.animate().translationX(target).setDuration(duration)
                .setInterpolator(ui.motionInterpolator())
                .withEndAction(() -> finishPageDrag(complete)).start();
    }

    private void finishPageDrag(boolean completed) {
        if (completed) {
            stage.removeView(pageOutgoing);
            pageIncoming.setTranslationX(0);
            detailKey = null;
        } else {
            stage.removeView(pageIncoming);
            pageOutgoing.setTranslationX(0);
        }
        pageOutgoing = null;
        pageIncoming = null;
        pageDragX = 0;
        pageDragging = false;
        pageGestureEligible = false;
    }

    private boolean gestureExcluded(float rawX, float rawY) {
        return gestureExcluded(stage, Math.round(rawX), Math.round(rawY));
    }

    private boolean gestureExcluded(View candidate, int rawX, int rawY) {
        if (!candidate.isShown()) return false;
        android.graphics.Rect bounds = new android.graphics.Rect();
        if (!candidate.getGlobalVisibleRect(bounds) || !bounds.contains(rawX, rawY)) return false;
        if (candidate instanceof EditText || candidate instanceof UiKit.SegmentControl
                || (candidate != stage && candidate.isClickable())) return true;
        if (!(candidate instanceof ViewGroup)) return false;
        ViewGroup group = (ViewGroup) candidate;
        for (int index = group.getChildCount() - 1; index >= 0; index--) {
            if (gestureExcluded(group.getChildAt(index), rawX, rawY)) return true;
        }
        return false;
    }

    private void recyclePageVelocity() {
        if (pageVelocity != null) {
            pageVelocity.recycle();
            pageVelocity = null;
        }
    }

    private void handleBack() {
        if (detailKey != null) showRoot(true);
        else ui.finishSpatial(true);
    }

    @Override public boolean canFinishSpatialGesture() {
        return detailKey == null && !pageDragging;
    }

    @SuppressLint("GestureBackNavigation")
    @Override public void onBackPressed() {
        handleBack();
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_DIAGNOSTIC_DESTINATION
                || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri destination = data.getData();
        try (OutputStream output = getContentResolver().openOutputStream(destination, "w")) {
            if (output == null) throw new IOException("无法打开保存位置");
            output.write(LocalDiagnosticReport.build(this).getBytes(StandardCharsets.UTF_8));
            output.flush();
            ui.message("本机诊断报告已保存");
        } catch (IOException error) {
            ui.error("保存诊断报告失败：" + safeMessage(error));
        }
    }

    private LinearLayout.LayoutParams fullDivider() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, ui.dp(1));
        params.setMargins(-ui.dp(ui.surfacePaddingDp()), 0,
                -ui.dp(ui.surfacePaddingDp()), 0);
        return params;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static String format(double value, int decimals) {
        return decimals == 0 ? String.valueOf(Math.round(value))
                : String.format(Locale.ROOT, "%." + decimals + "f", value);
    }

    private static String safeMessage(Throwable error) {
        return error.getMessage() == null
                ? error.getClass().getSimpleName() : error.getMessage();
    }
}
