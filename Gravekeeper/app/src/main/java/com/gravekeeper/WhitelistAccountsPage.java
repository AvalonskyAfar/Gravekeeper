package com.gravekeeper;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.gravekeeper.config.ConfigStore;
import com.gravekeeper.inference.ContentSignals;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.Set;

/** Live-header account-name whitelist editor; matching remains frozen 0.5.4 semantics. */
final class WhitelistAccountsPage {
    private static final String UI_PREFS = "guard_whitelist_ui";
    private static final String DISABLED_PREFIX = "disabled_names_";
    private final Activity activity;
    private final ConfigStore store;
    private final UiKit ui;
    private LinearLayout listHost;

    WhitelistAccountsPage(Activity activity, ConfigStore store, UiKit ui) {
        this.activity = activity;
        this.store = store;
        this.ui = ui;
    }

    View build() {
        LinearLayout root = ui.pageColumn();
        root.addView(ui.pageTitle("白名单账户"), ui.margins(0, 0, 0, 5));
        TextView description = ui.text(
                "管理不会触发保护的直播账户名称。只读取直播页顶部显示的账户主名称，使用保守的前缀匹配；短视频不会使用这份白名单。",
                12.5f, ui.p.muted, false);
        description.setPadding(ui.dp(10), 0, ui.dp(10), 0);
        root.addView(description, ui.margins(0, 0, 0, 17));
        listHost = ui.column();
        root.addView(listHost, ui.margins(0, 0, 0, 18));
        listHost.post(this::refreshList);
        root.addView(ui.capsule("添加账户", false, ignored -> showAddDialog()),
                ui.margins(34, 0, 34, 0));
        return ui.scroll(root);
    }

    private void refreshList() {
        listHost.removeAllViews();
        try {
            JSONObject json = store.loadJson();
            JSONArray platforms = json.getJSONArray("platforms");
            LinearLayout surface = ui.surface();
            boolean any = false;
            int rowCount = 0;
            for (int i = 0; i < platforms.length(); i++) {
                JSONObject platform = platforms.getJSONObject(i);
                String platformId = platform.getString("id");
                JSONArray values = platform.getJSONArray("whitelist_ids");
                LinkedHashSet<String> disabled = disabledNames(platformId);
                LinkedHashSet<String> names = new LinkedHashSet<>();
                for (int j = 0; j < values.length(); j++) names.add(values.getString(j));
                names.addAll(disabled);
                if (names.isEmpty()) continue;
                any = true;
                for (String name : names) {
                    if (rowCount > 0) surface.addView(ui.divider(), fullDivider());
                    boolean enabled = valuesContains(values, name) && !disabled.contains(name);
                    surface.addView(new AccountItem(platformId, name, enabled),
                            new LinearLayout.LayoutParams(-1, ui.dp(68)));
                    rowCount++;
                }
            }
            if (any) listHost.addView(surface, new LinearLayout.LayoutParams(-1, -2));
        } catch (IOException | JSONException error) {
            ui.error("读取白名单失败：" + safeMessage(error));
        }
    }

    private void showAddDialog() {
        EditText editor = new EditText(activity);
        editor.setSingleLine(true);
        editor.setHint("输入直播账户主名称");
        editor.setTextColor(ui.p.ink);
        editor.setHintTextColor(ui.p.muted);
        editor.setTextSize(ui.scaledTextSp(15));
        editor.setPadding(ui.dp(13), ui.dp(11), ui.dp(13), ui.dp(11));
        editor.setBackground(ui.inputFieldDrawable());
        LinearLayout form = ui.column();
        TextView platformLabel = ui.text("直播平台", 13, ui.p.ink, true);
        form.addView(platformLabel, ui.margins(0, 0, 0, 7));
        UiKit.SegmentControl platform = ui.segment(new String[] {"抖音", "快手"}, 0, null);
        form.addView(platform, new LinearLayout.LayoutParams(-1, ui.dp(60)));
        TextView accountLabel = ui.text("账户名称", 13, ui.p.ink, true);
        form.addView(accountLabel, ui.margins(0, 13, 0, 7));
        form.addView(editor, ui.matchWrap());
        ui.form("添加白名单账户", "输入直播页面顶部显示的账户名称", form, ignored ->
                addAccount(platform.selected() == 0 ? "douyin" : "kuaishou",
                        editor.getText().toString()));
    }

    private boolean addAccount(String platformId, String raw) {
        String normalized = ContentSignals.normalizeAccountId(raw);
        if (normalized.length() < 2 || normalized.length() > 64) {
            ui.error("请输入 2 至 64 个字符的直播账户主名称");
            return false;
        }
        try {
            JSONObject json = store.loadJson();
            setActive(json, platformId, normalized, true);
            store.save(json);
            setDisabled(platformId, normalized, false);
            ui.message("已添加 " + normalized);
            refreshList();
            return true;
        } catch (IOException | JSONException error) {
            ui.error("添加账户失败：" + safeMessage(error));
            return false;
        }
    }

    private void toggleAccount(String platformId, String name, boolean enabled) {
        try {
            JSONObject json = store.loadJson();
            setActive(json, platformId, name, enabled);
            store.save(json);
            setDisabled(platformId, name, !enabled);
            ui.message(name + "已" + (enabled ? "启用" : "停用"));
            refreshList();
        } catch (IOException | JSONException error) {
            ui.error("更新白名单失败：" + safeMessage(error));
            refreshList();
        }
    }

    private void deleteAccount(String platformId, String name) {
        ui.confirm("删除白名单账户？", "将从" + ("douyin".equals(platformId) ? "抖音" : "快手") + "白名单中删除“" + name + "”。", "删除", true, () -> {
            try {
                JSONObject json = store.loadJson();
                setActive(json, platformId, name, false);
                store.save(json);
                setDisabled(platformId, name, false);
                ui.message("已删除" + name);
                refreshList();
            } catch (IOException | JSONException error) {
                ui.error("删除账户失败：" + safeMessage(error));
            }
        });
    }

    private static boolean valuesContains(JSONArray values, String name) throws JSONException {
        for (int i = 0; i < values.length(); i++) if (name.equals(values.getString(i))) return true;
        return false;
    }

    private static void setActive(JSONObject json, String platformId, String name, boolean enabled) throws JSONException {
        JSONArray platforms = json.getJSONArray("platforms");
        for (int i = 0; i < platforms.length(); i++) {
            JSONObject platform = platforms.getJSONObject(i);
            if (!platformId.equals(platform.optString("id"))) continue;
            LinkedHashSet<String> values = new LinkedHashSet<>();
            JSONArray current = platform.getJSONArray("whitelist_ids");
            for (int j = 0; j < current.length(); j++) values.add(current.getString(j));
            if (enabled) values.add(name); else values.remove(name);
            JSONArray next = new JSONArray();
            for (String value : values) next.put(value);
            platform.put("whitelist_ids", next);
            return;
        }
        throw new JSONException("找不到平台：" + platformId);
    }

    private LinkedHashSet<String> disabledNames(String platformId) {
        return new LinkedHashSet<>(activity.getSharedPreferences(
                UI_PREFS, Context.MODE_PRIVATE).getStringSet(
                DISABLED_PREFIX + platformId, Set.of()));
    }

    private void setDisabled(String platformId, String name, boolean disabled) {
        LinkedHashSet<String> values = disabledNames(platformId);
        if (disabled) values.add(name); else values.remove(name);
        activity.getSharedPreferences(UI_PREFS, Context.MODE_PRIVATE).edit()
                .putStringSet(DISABLED_PREFIX + platformId, values).apply();
    }

    public static void clearUiState(Context context) {
        context.getSharedPreferences(UI_PREFS, Context.MODE_PRIVATE).edit().clear().apply();
    }

    private LinearLayout.LayoutParams fullDivider() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, ui.dp(1));
        params.setMargins(-ui.dp(ui.surfacePaddingDp()), 0,
                -ui.dp(ui.surfacePaddingDp()), 0);
        return params;
    }

    private final class AccountItem extends FrameLayout {
        private final int revealWidth = ui.dp(78);
        private final int slop = ViewConfiguration.get(activity).getScaledTouchSlop();
        private final LinearLayout foreground;
        private float downX;
        private float downY;
        private float startOffset;
        private boolean dragging;

        AccountItem(String platformId, String name, boolean enabled) {
            super(WhitelistAccountsPage.this.activity);
            setClipChildren(true);
            TextView delete = ui.text("删除", 13.5f, Color.WHITE, true);
            delete.setGravity(Gravity.CENTER);
            delete.setBackground(ui.destructiveRevealDrawable());
            ui.attachPress(delete, delete, ignored -> deleteAccount(platformId, name));
            addView(delete, new FrameLayout.LayoutParams(revealWidth, -1, Gravity.START));
            foreground = new LinearLayout(activity);
            foreground.setOrientation(LinearLayout.HORIZONTAL);
            foreground.setGravity(Gravity.CENTER_VERTICAL);
            foreground.setPadding(ui.dp(14), ui.dp(8), ui.dp(7), ui.dp(8));
            // R2 rows share their parent S1. Do not add a second card edge or shadow per row.
            foreground.setBackground(ui.embeddedRowDrawable());
            TextView account = ui.text(name, 14.5f, ui.p.ink, true);
            account.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            foreground.addView(account, new LinearLayout.LayoutParams(0, -1, 1));
            UiKit.CompactToggle toggle = ui.compactToggle(enabled,
                    value -> toggleAccount(platformId, name, value));
            foreground.addView(toggle, new LinearLayout.LayoutParams(ui.dp(61), ui.dp(34)));
            addView(foreground, new FrameLayout.LayoutParams(-1, -1));
            foreground.setOnTouchListener((view, event) -> {
                if (isInside(toggle, event.getX(), event.getY())) {
                    // K5 owns gestures that begin on its bounds.  Account-row
                    // deletion is deliberately unavailable there so a toggle
                    // drag can never reveal the destructive action.
                    return false;
                }
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = event.getX();
                        downY = event.getY();
                        startOffset = foreground.getTranslationX();
                        dragging = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float dx = event.getX() - downX, dy = event.getY() - downY;
                        if (!dragging && Math.abs(dx) < slop && Math.abs(dy) < slop) return true;
                        if (!dragging && Math.abs(dy) > Math.abs(dx)) return false;
                        dragging = true;
                        foreground.getParent().requestDisallowInterceptTouchEvent(true);
                        foreground.setTranslationX(Math.max(0,
                                Math.min(revealWidth, startOffset + dx)));
                        return true;
                    case MotionEvent.ACTION_UP: case MotionEvent.ACTION_CANCEL:
                        foreground.getParent().requestDisallowInterceptTouchEvent(false);
                        float target = event.getActionMasked() == MotionEvent.ACTION_UP
                                && dragging && foreground.getTranslationX() >= revealWidth * 0.42f
                                ? revealWidth : 0;
                        foreground.animate().translationX(target)
                                .setDuration(ui.prefs.reduceMotion() ? 90 : 220)
                                .setInterpolator(ui.motionInterpolator()).start();
                        if (!dragging) performClick();
                        return true;
                    default: return false;
                }
            });
        }

        private boolean isInside(View child, float x, float y) {
            return x >= child.getLeft() && x < child.getRight()
                    && y >= child.getTop() && y < child.getBottom();
        }

        @Override public boolean performClick() { super.performClick(); return true; }
    }

    private static String safeMessage(Throwable error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }
}
