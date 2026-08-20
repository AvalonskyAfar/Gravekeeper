package com.gravekeeper;

import android.app.Activity;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.gravekeeper.config.ConfigStore;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/** Validated local configuration manager behind the formal developer page. */
public final class ConfigEditorActivity extends Activity {
    private static final int REQUEST_IMPORT = 4101;
    private static final int REQUEST_EXPORT = 4102;

    private ConfigStore store;
    private UiKit ui;
    private EditText editor;
    private EditText resetPath;

    @Override protected void onCreate(Bundle state) {
        UiKit.applyPreferredTheme(this);
        super.onCreate(state);
        store = new ConfigStore(this);
        ui = new UiKit(this);
        setContentView(ui.spatialRoot(build()));
        loadIntoEditor();
        BackNavigation.register(this, () -> ui.finishSpatial(true));
    }

    @SuppressLint("GestureBackNavigation")
    @Override public void onBackPressed() {
        ui.finishSpatial(true);
    }

    private View build() {
        LinearLayout root = ui.pageColumn();
        ui.applySystemInsets(root);
        TextView title = ui.text("工程配置", 20, ui.p.ink, true);
        title.setGravity(Gravity.CENTER);
        root.addView(title, ui.margins(8, 0, 8, 5));
        TextView detail = ui.text(
                "保存前执行完整校验；错误配置不会生效，账户识别工程参数始终保留当前冻结值",
                12.5f, ui.p.muted, false);
        detail.setGravity(Gravity.CENTER);
        root.addView(detail, ui.margins(12, 0, 12, 18));

        root.addView(ui.heading("当前工程配置", "仅供开发者精确编辑"),
                ui.margins(0, 0, 0, 10));
        LinearLayout editorSurface = ui.surface();
        editor = new EditText(this);
        editor.setTextSize(ui.scaledTextSp(12));
        editor.setTextColor(ui.p.ink);
        editor.setHintTextColor(ui.p.muted);
        editor.setTypeface(Typeface.MONOSPACE);
        editor.setGravity(Gravity.TOP | Gravity.START);
        editor.setHorizontallyScrolling(true);
        editor.setMinLines(24);
        editor.setPadding(ui.dp(12), ui.dp(12), ui.dp(12), ui.dp(12));
        editor.setBackground(ui.inputFieldDrawable());
        editorSurface.addView(editor, new LinearLayout.LayoutParams(-1, -2));
        TextView validate = ui.capsule("校验并保存当前配置", false,
                ignored -> save());
        editorSurface.addView(validate, ui.margins(22, 15, 22, 2));
        root.addView(editorSurface, ui.margins(0, 0, 0, 22));

        root.addView(ui.heading("配置操作", "导入先预览，导出仅写入你选择的本地位置"),
                ui.margins(0, 0, 0, 10));
        LinearLayout actions = ui.surface();
        addAction(actions, "从本地 JSON 导入并预览", "读取后先校验并列出变化",
                ignored -> chooseImport(), true);
        addAction(actions, "导出当前配置到本地", "不会联网或自动分享",
                ignored -> chooseExport(), true);
        addAction(actions, "恢复上一份有效配置",
                store.hasLastGood() ? "当前存在可恢复版本" : "当前没有可恢复版本",
                ignored -> restoreLastGood(), true);
        addAction(actions, "恢复风险信号与关键词", "仅恢复 signals 分区",
                ignored -> confirmRestoreSection("signals", "风险信号与关键词"), true);
        addAction(actions, "恢复平台与媒体策略", "账户白名单也会恢复默认",
                ignored -> confirmRestoreSection("platforms", "平台与媒体策略"), false);
        root.addView(actions, ui.margins(0, 0, 0, 22));

        root.addView(ui.heading("单项恢复", "输入配置路径后只恢复对应字段"),
                ui.margins(0, 0, 0, 10));
        LinearLayout single = ui.surface();
        resetPath = new EditText(this);
        resetPath.setSingleLine(true);
        resetPath.setHint("例如 signals.global_purchase_bias");
        resetPath.setTextSize(ui.scaledTextSp(14));
        resetPath.setTextColor(ui.p.ink);
        resetPath.setHintTextColor(ui.p.muted);
        resetPath.setPadding(ui.dp(12), ui.dp(11), ui.dp(12), ui.dp(11));
        resetPath.setBackground(ui.inputFieldDrawable());
        single.addView(resetPath, new LinearLayout.LayoutParams(-1, -2));
        TextView restoreOne = ui.capsule("恢复该单项默认值", false,
                ignored -> restoreOne());
        single.addView(restoreOne, ui.margins(24, 14, 24, 1));
        root.addView(single, ui.margins(0, 0, 0, 0));
        return ui.scroll(root);
    }

    private void addAction(LinearLayout surface, String title, String detail,
            View.OnClickListener listener, boolean divider) {
        surface.addView(ui.entry(title, detail, null, listener));
        if (divider) surface.addView(ui.divider(), fullDivider());
    }

    private void loadIntoEditor() {
        try {
            editor.setText(store.loadJson().toString(2));
        } catch (IOException | JSONException error) {
            editor.setText("");
            ui.error("读取配置失败：" + safeMessage(error));
        }
    }

    private void save() {
        try {
            JSONObject current = store.loadJson();
            JSONObject candidate = new JSONObject(editor.getText().toString());
            preserveFrozenAccountRecognition(current, candidate);
            JSONObject validated = store.validateAndMigrate(candidate);
            store.save(validated);
            WhitelistAccountsPage.clearUiState(this);
            editor.setText(validated.toString(2));
            ui.message("配置已校验并生效");
        } catch (JSONException error) {
            ui.error("JSON 格式错误：" + safeMessage(error));
        } catch (IOException error) {
            ui.error("配置校验失败：" + safeMessage(error));
        }
    }

    private void chooseImport() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .setType("application/json")
                .addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(intent, REQUEST_IMPORT);
    }

    private void chooseExport() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                .setType("application/json")
                .putExtra(Intent.EXTRA_TITLE, "gravekeeper-config.json")
                .addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(intent, REQUEST_EXPORT);
    }

    private void restoreLastGood() {
        if (!store.hasLastGood()) {
            ui.error("当前没有上一份有效配置");
            return;
        }
        ui.confirm("恢复上一份有效配置？", "当前配置会成为新的可恢复版本。",
                "恢复", false, () -> {
                    try {
                        if (store.restoreLastGood()) {
                            loadIntoEditor();
                            ui.message("已恢复上一份有效配置");
                        }
                    } catch (IOException error) {
                        ui.error("恢复失败：" + safeMessage(error));
                    }
                });
    }

    private void confirmRestoreSection(String section, String label) {
        ui.confirm("恢复" + label + "？", "只恢复此分区，其余设置保持不变。",
                "恢复", true, () -> {
                    try {
                        store.resetSectionToDefault(section);
                        if ("platforms".equals(section)) {
                            WhitelistAccountsPage.clearUiState(this);
                        }
                        loadIntoEditor();
                        ui.message("已恢复" + label);
                    } catch (IOException error) {
                        ui.error("恢复失败：" + safeMessage(error));
                    }
                });
    }

    private void restoreOne() {
        String path = resetPath.getText().toString().trim();
        if (path.isEmpty()) {
            ui.error("请先输入配置路径");
            return;
        }
        ui.confirm("恢复该单项？", "将恢复“" + path + "”的默认值。",
                "恢复", false, () -> {
                    try {
                        store.resetPathToDefault(path);
                        loadIntoEditor();
                        ui.message("已恢复该单项默认值");
                    } catch (IOException error) {
                        ui.error("恢复失败：" + safeMessage(error));
                    }
                });
    }

    @Override protected void onActivityResult(int requestCode, int resultCode,
            Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        if (requestCode == REQUEST_IMPORT) importConfig(data.getData());
        else if (requestCode == REQUEST_EXPORT) exportConfig(data.getData());
    }

    private void importConfig(Uri uri) {
        try (InputStream input = getContentResolver().openInputStream(uri)) {
            if (input == null) throw new IOException("无法读取所选文件");
            StringBuilder text = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    input, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) text.append(line).append('\n');
            }
            JSONObject current = store.loadJson();
            JSONObject candidateJson = new JSONObject(text.toString());
            preserveFrozenAccountRecognition(current, candidateJson);
            JSONObject candidate = store.validateAndMigrate(candidateJson);
            String summary = summarizeChanges(current, candidate);
            ui.confirm("确认导入配置", summary, "导入", false, () -> {
                try {
                    store.save(candidate);
                    WhitelistAccountsPage.clearUiState(this);
                    loadIntoEditor();
                    ui.message("配置已导入并生效");
                } catch (IOException error) {
                    ui.error("导入失败：" + safeMessage(error));
                }
            });
        } catch (IOException | JSONException error) {
            ui.error("导入校验失败，当前配置未改变：" + safeMessage(error));
        }
    }

    private void exportConfig(Uri uri) {
        try (OutputStream output = getContentResolver().openOutputStream(uri)) {
            if (output == null) throw new IOException("无法写入所选文件");
            output.write(store.loadJson().toString(2)
                    .getBytes(StandardCharsets.UTF_8));
            output.flush();
            ui.message("当前配置已导出");
        } catch (IOException | JSONException error) {
            ui.error("导出失败：" + safeMessage(error));
        }
    }

    private static String summarizeChanges(JSONObject before, JSONObject after) {
        StringBuilder summary = new StringBuilder("文件已通过完整校验。将改变：");
        appendChange(summary, "保护总开关", before.optBoolean("protection_enabled"),
                after.optBoolean("protection_enabled"));
        appendChange(summary, "通知快捷停止", before.optBoolean("notification_quick_stop"),
                after.optBoolean("notification_quick_stop"));
        appendChange(summary, "状态覆盖层", before.optJSONObject("status_overlay"),
                after.optJSONObject("status_overlay"));
        appendChange(summary, "截屏基础间隔", before.optLong("capture_interval_ms"),
                after.optLong("capture_interval_ms"));
        appendChange(summary, "OCR 基础间隔", before.optLong("ocr_interval_ms"),
                after.optLong("ocr_interval_ms"));
        appendChange(summary, "窗口策略", before.optJSONObject("multi_window"),
                after.optJSONObject("multi_window"));
        appendChange(summary, "平台配置", before.optJSONArray("platforms"),
                after.optJSONArray("platforms"));
        appendChange(summary, "风险信号", before.optJSONObject("signals"),
                after.optJSONObject("signals"));
        if (summary.toString().endsWith("将改变：")) summary.append("\n没有实质变化");
        summary.append("\n\n导入后仍可恢复上一份有效配置。账户识别工程参数不会被导入文件覆盖。");
        return summary.toString();
    }

    /** Account recognition is a frozen product safety boundary. */
    private static void preserveFrozenAccountRecognition(JSONObject current,
            JSONObject candidate) throws JSONException {
        JSONArray currentPlatforms = current.optJSONArray("platforms");
        JSONArray candidatePlatforms = candidate.optJSONArray("platforms");
        if (currentPlatforms == null || candidatePlatforms == null) return;
        for (int i = 0; i < candidatePlatforms.length(); i++) {
            JSONObject candidatePlatform = candidatePlatforms.getJSONObject(i);
            JSONObject currentPlatform = findPlatform(currentPlatforms,
                    candidatePlatform.optString("id", ""));
            if (currentPlatform == null) {
                candidatePlatform.remove("whitelist_match_mode");
                candidatePlatform.remove("account_detection");
                continue;
            }
            if (currentPlatform.has("whitelist_match_mode")) {
                candidatePlatform.put("whitelist_match_mode",
                        currentPlatform.get("whitelist_match_mode"));
            }
            if (currentPlatform.has("account_detection")) {
                candidatePlatform.put("account_detection", new JSONObject(
                        currentPlatform.getJSONObject("account_detection").toString()));
            }
        }
    }

    private static JSONObject findPlatform(JSONArray platforms, String id)
            throws JSONException {
        for (int i = 0; i < platforms.length(); i++) {
            JSONObject platform = platforms.getJSONObject(i);
            if (id.equals(platform.optString("id", ""))) return platform;
        }
        return null;
    }

    private static void appendChange(StringBuilder text, String label,
            Object before, Object after) {
        if (!String.valueOf(before).equals(String.valueOf(after))) {
            text.append("\n• ").append(label);
        }
    }

    private LinearLayout.LayoutParams fullDivider() {
        LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(-1, ui.dp(1));
        params.setMargins(-ui.dp(ui.surfacePaddingDp()), 0,
                -ui.dp(ui.surfacePaddingDp()), 0);
        return params;
    }

    private static String safeMessage(Throwable error) {
        return error.getMessage() == null
                ? error.getClass().getSimpleName() : error.getMessage();
    }
}
