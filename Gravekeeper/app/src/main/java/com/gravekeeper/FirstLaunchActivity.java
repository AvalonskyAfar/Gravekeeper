package com.gravekeeper;

import android.app.Activity;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.util.SparseArray;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.VelocityTracker;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Eight approved presentation pages followed by the existing disclosure/permission handoff. */
@SuppressLint({"CustomSplashScreen", "UseCompatLoadingForDrawables"})
public final class FirstLaunchActivity extends Activity {
    private static final int LAST_PAGE = FirstLaunchPageCatalog.DISCLOSURE_PAGE_INDEX;

    private UiKit ui;
    private FrameLayout headingStage;
    private FrameLayout contentStage;
    private FrameLayout action;
    private FrameLayout actionContent;
    private TextView actionText;
    private UiKit.ArrowRightView actionArrow;
    private final TextView[] dots = new TextView[FirstLaunchPageCatalog.PAGE_COUNT];
    private final SparseArray<Drawable.ConstantState> illustrationStates = new SparseArray<>();
    private int page;
    private boolean confirmed;
    private boolean transitioning;
    private final int[] touchLocation = new int[2];
    private float gestureDownX;
    private float gestureDownY;
    private int gestureTarget = -1;
    private boolean gestureTracking;
    private boolean gestureHorizontal;
    private View gestureOldHeading;
    private View gestureNewHeading;
    private View gestureOldContent;
    private View gestureNewContent;
    private VelocityTracker gestureVelocity;
    private int gestureSlop;

    @Override protected void onCreate(Bundle state) {
        UiKit.applyFirstLaunchTheme(this);
        super.onCreate(state);
        ui = UiKit.forFirstLaunch(this);
        preloadIllustrations();
        page = state == null ? 0 : Math.max(0, Math.min(LAST_PAGE, state.getInt("page", 0)));
        confirmed = state != null && state.getBoolean("confirmed", false);
        gestureSlop = ViewConfiguration.get(this).getScaledTouchSlop();
        setContentView(build());
        BackNavigation.register(this, this::handleBack);
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putInt("page", page);
        state.putBoolean("confirmed", confirmed);
        super.onSaveInstanceState(state);
    }

    private View build() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(ui.p.bg);
        ui.applySystemInsets(root);

        LinearLayout shell = ui.pageColumn();
        shell.setGravity(Gravity.CENTER_VERTICAL);

        headingStage = new FrameLayout(this);
        headingStage.addView(headingView(page), new FrameLayout.LayoutParams(-1, -1));
        shell.addView(headingStage, new LinearLayout.LayoutParams(-1,
                ui.dp(ReadingLayoutSpec.FIRST_LAUNCH_HEADING_HEIGHT_DP)));

        FrameLayout surface = new FrameLayout(this);
        surface.setClipToOutline(false);
        surface.setClipChildren(false);
        surface.setClipToPadding(false);

        contentStage = new FrameLayout(this);
        FrameLayout.LayoutParams contentParams = new FrameLayout.LayoutParams(-1, -1);
        contentParams.bottomMargin = ui.dp(68);
        surface.addView(contentStage, contentParams);
        contentStage.addView(contentPanel(page), new FrameLayout.LayoutParams(-1, -1));

        LinearLayout fixedAction = ui.column();
        fixedAction.setBackground(ui.onboardingActionDrawable());
        fixedAction.addView(ui.divider(), new LinearLayout.LayoutParams(-1, ui.dp(1)));
        action = new FrameLayout(this);
        action.setClipChildren(false);
        actionContent = new FrameLayout(this);
        actionContent.setClipChildren(false);
        LinearLayout actionLabelGroup = new LinearLayout(this);
        actionLabelGroup.setOrientation(LinearLayout.HORIZONTAL);
        actionLabelGroup.setGravity(Gravity.CENTER);
        actionText = ui.text(actionLabel(), ReadingLayoutSpec.FIRST_LAUNCH_ACTION_SP,
                ui.p.blue, true);
        actionText.setGravity(Gravity.CENTER);
        actionLabelGroup.addView(actionText, new LinearLayout.LayoutParams(-2, -1));
        actionArrow = new UiKit.ArrowRightView(this, ui, ui.p.blue);
        LinearLayout.LayoutParams arrowParams =
                new LinearLayout.LayoutParams(ui.dp(24), ui.dp(24));
        arrowParams.leftMargin = ui.dp(7);
        actionLabelGroup.addView(actionArrow, arrowParams);
        actionArrow.setVisibility(actionHasArrow() ? View.VISIBLE : View.GONE);
        actionContent.addView(actionLabelGroup,
                new FrameLayout.LayoutParams(-2, -1, Gravity.CENTER));
        action.addView(actionContent, new FrameLayout.LayoutParams(-1, -1));
        action.setContentDescription(actionLabel());
        ui.attachPress(action, actionContent, ignored -> next());
        fixedAction.addView(action, new LinearLayout.LayoutParams(-1, ui.dp(67)));
        surface.addView(fixedAction,
                new FrameLayout.LayoutParams(-1, ui.dp(68), Gravity.BOTTOM));

        LinearLayout.LayoutParams surfaceParams = new LinearLayout.LayoutParams(-1, 0, 1);
        shell.addView(surface, surfaceParams);
        shell.addView(progress(), new LinearLayout.LayoutParams(-1, ui.dp(34)));
        root.addView(shell, new FrameLayout.LayoutParams(-1, -1));
        return root;
    }

    private View headingView(int index) {
        LinearLayout heading = ui.column();
        heading.setGravity(Gravity.CENTER);
        heading.setPadding(ui.dp(10), 0, ui.dp(10), 0);
        TextView title = ui.text(headingTitle(index), ReadingLayoutSpec.FIRST_LAUNCH_TITLE_SP,
                ui.p.ink, true);
        title.setGravity(Gravity.CENTER);
        heading.addView(title, ui.matchWrap());
        String detailText = headingDetail(index);
        if (detailText != null && !detailText.isBlank()) {
            TextView detail = ui.text(detailText,
                    ReadingLayoutSpec.FIRST_LAUNCH_SUBTITLE_SP, ui.p.muted, false);
            detail.setGravity(Gravity.CENTER);
            heading.addView(detail, ui.margins(0, 4, 0, 0));
        }
        return heading;
    }

    private View contentView(int index) {
        if (FirstLaunchPageCatalog.isDisclosure(index)) return privacyView();

        FirstLaunchPageCatalog.PageSpec pageSpec = FirstLaunchPageCatalog.introPage(index);

        LinearLayout content = ui.column();

        if (pageSpec.hasIllustration()) {
            Drawable.ConstantState state = illustrationStates.get(pageSpec.illustrationRes);
            Drawable drawable = state == null
                    ? getDrawable(pageSpec.illustrationRes)
                    : state.newDrawable(getResources()).mutate();
            View image = ui.onboardingMediaImage(drawable, pageSpec.title + "插图");
            LinearLayout.LayoutParams imageParams = new LinearLayout.LayoutParams(-1,
                    ui.dp(ReadingLayoutSpec.FIRST_LAUNCH_MEDIA_MIN_HEIGHT_DP), 1f);
            int edge = ui.dp(ReadingLayoutSpec.FIRST_LAUNCH_MEDIA_EDGE_INSET_DP);
            imageParams.setMargins(edge, edge, edge, 0);
            content.addView(image, imageParams);

            TextView copy = ui.explanatoryText(pageSpec.body);
            copy.setGravity(Gravity.CENTER_HORIZONTAL | Gravity.TOP);
            copy.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
            copy.setPadding(ui.dp(ReadingLayoutSpec.FIRST_LAUNCH_BODY_HORIZONTAL_DP),
                    ui.dp(ReadingLayoutSpec.FIRST_LAUNCH_BODY_VERTICAL_DP),
                    ui.dp(ReadingLayoutSpec.FIRST_LAUNCH_BODY_HORIZONTAL_DP),
                    ui.dp(ReadingLayoutSpec.FIRST_LAUNCH_BODY_VERTICAL_DP));
            content.addView(copy, ui.matchWrap());
            // fillViewport keeps the approved normal-size composition; the minimum media
            // height gives enlarged text a scrollable fallback instead of collapsing art.
            return ui.embeddedScroll(content);
        }

        content.setPadding(ui.dp(ReadingLayoutSpec.FIRST_LAUNCH_BODY_HORIZONTAL_DP),
                ui.dp(ReadingLayoutSpec.FIRST_LAUNCH_BODY_VERTICAL_DP + 8),
                ui.dp(ReadingLayoutSpec.FIRST_LAUNCH_BODY_HORIZONTAL_DP),
                ui.dp(ReadingLayoutSpec.FIRST_LAUNCH_BODY_VERTICAL_DP + 4));
        // The two no-image pages use the same approved body treatment as the six
        // illustrated pages. Their original copy is shown as one paragraph without
        // generated subheads, callouts or a separate editorial hierarchy.
        content.setGravity(Gravity.CENTER_VERTICAL);
        TextView copy = ui.explanatoryText(pageSpec.body);
        copy.setGravity(Gravity.CENTER);
        copy.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
        content.addView(copy, ui.matchWrap());
        return ui.embeddedScroll(content);
    }

    private TextView articleText(String value, float size, boolean medium, int color) {
        TextView view = ui.text(value, size, color, medium);
        view.setGravity(Gravity.CENTER_HORIZONTAL | Gravity.TOP);
        view.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
        view.setLineSpacing(ui.dp(2), 1.2f);
        view.setLetterSpacing(0.01f);
        return view;
    }

    private void preloadIllustrations() {
        for (int index = 0; index < FirstLaunchPageCatalog.INTRO_PAGE_COUNT; index++) {
            int resource = FirstLaunchPageCatalog.introPage(index).illustrationRes;
            if (resource == 0 || illustrationStates.get(resource) != null) continue;
            Drawable drawable = getDrawable(resource);
            if (drawable != null && drawable.getConstantState() != null) {
                illustrationStates.put(resource, drawable.getConstantState());
            }
        }
    }

    private View contentPanel(int index) {
        FrameLayout panel = new FrameLayout(this);
        panel.setBackground(ui.onboardingContentDrawable());
        panel.addView(contentView(index), new FrameLayout.LayoutParams(-1, -1));
        return panel;
    }

    private View privacyView() {
        LinearLayout content = ui.column();

        LinearLayout state = ui.column();
        state.setGravity(Gravity.CENTER);
        state.setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(12));
        TextView title = ui.text(confirmed ? "说明已确认" : "尚未确认说明",
                17f, ui.p.ink, true);
        title.setGravity(Gravity.CENTER);
        state.addView(title, ui.matchWrap());
        TextView detail = ui.text(confirmed ? "可以继续设置无障碍权限" : "确认后才能继续设置权限",
                14f, ui.p.muted, false);
        detail.setGravity(Gravity.CENTER);
        state.addView(detail, ui.margins(0, 4, 0, 0));
        content.addView(state, new LinearLayout.LayoutParams(-1, ui.dp(90)));
        content.addView(ui.divider(), new LinearLayout.LayoutParams(-1, ui.dp(1)));

        LinearLayout paragraphs = ui.column();
        paragraphs.setPadding(ui.dp(ReadingLayoutSpec.FIRST_LAUNCH_BODY_HORIZONTAL_DP),
                ui.dp(ReadingLayoutSpec.FIRST_LAUNCH_BODY_VERTICAL_DP),
                ui.dp(ReadingLayoutSpec.FIRST_LAUNCH_BODY_HORIZONTAL_DP),
                ui.dp(ReadingLayoutSpec.FIRST_LAUNCH_BODY_VERTICAL_DP));
        addPrivacySection(paragraphs, "读取范围",
                "应用会读取当前屏幕可见内容和页面变化，用于识别短视频与直播中的健康营销风险。");
        addPrivacySection(paragraphs, "处理方式",
                "读取只发生在开启保护并授予无障碍权限时。屏幕图像仅在内存中用于本地 OCR、模型和规则判断，应用可能按你的选择发出提醒或执行向上划走动作。");
        addPrivacySection(paragraphs, "隐私边界",
                "软件完全离线运行，不申请联网权限，不连接服务器，不收集或上传截图、OCR 文字、账户名称、视频内容、判断结果、性能数据或其他用户隐私数据；屏幕图像也不会写入文件。");
        addPrivacySection(paragraphs, "你的控制",
                "必要运行状态只保存在本机。你可以随时关闭保护、撤销无障碍权限、清除本机技术状态或卸载软件。");
        ScrollView scroll = ui.embeddedScroll(paragraphs);
        content.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        return content;
    }

    private void addPrivacySection(LinearLayout parent, String heading, String value) {
        TextView headingView = articleText(heading,
                ReadingLayoutSpec.FIRST_LAUNCH_ARTICLE_GROUP_SP, true, ui.p.blue);
        parent.addView(headingView, ui.margins(0, 0, 0, 6));
        TextView copy = articleText(value,
                ReadingLayoutSpec.FIRST_LAUNCH_ARTICLE_ITEM_BODY_SP, false, ui.p.ink);
        parent.addView(copy, ui.margins(0, 0, 0, 18));
    }

    private View progress() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        for (int i = 0; i <= LAST_PAGE; i++) {
            final int target = i;
            TextView dot = ui.text("●", 9, i == page ? ui.p.blue : ui.p.muted, false);
            dot.setGravity(Gravity.CENTER);
            dot.setContentDescription("第 " + (i + 1) + " 页");
            ui.attachPress(dot, dot, ignored -> goTo(target));
            dots[i] = dot;
            row.addView(dot, new LinearLayout.LayoutParams(ui.dp(28), ui.dp(28)));
        }
        return row;
    }

    private String headingTitle(int index) {
        if (FirstLaunchPageCatalog.isDisclosure(index)) return "隐私与使用说明";
        return FirstLaunchPageCatalog.introPage(index).title;
    }

    private String headingDetail(int index) {
        if (FirstLaunchPageCatalog.isDisclosure(index)) {
            return "首次使用前，请阅读并确认下面的内容";
        }
        return FirstLaunchPageCatalog.introPage(index).subtitle;
    }

    private String actionLabel() {
        if (page < LAST_PAGE) return "下一页";
        return confirmed ? "进入权限教程" : "我已阅读并继续";
    }

    private boolean actionHasArrow() {
        return page < LAST_PAGE || confirmed;
    }

    private void next() {
        if (transitioning) return;
        if (page < LAST_PAGE) {
            goTo(page + 1);
            return;
        }
        if (!confirmed) {
            confirmed = true;
            contentStage.removeAllViews();
            contentStage.addView(contentPanel(page), new FrameLayout.LayoutParams(-1, -1));
            switchActionLabel();
            return;
        }
        ui.prefs.setConsented(true);
        Intent tutorial = new Intent(this, MainActivity.class);
        tutorial.putExtra(MainActivity.EXTRA_OPEN_TUTORIAL, true);
        startActivity(tutorial);
        finish();
    }

    private void switchActionLabel() {
        actionText.setText(actionLabel());
        actionArrow.setVisibility(actionHasArrow() ? View.VISIBLE : View.GONE);
        action.setContentDescription(actionLabel());
        if (ui.prefs.reduceMotion()) return;
        action.setTranslationX(ui.dp(14));
        action.animate().translationX(0).setDuration(420)
                .setInterpolator(ui.motionInterpolator()).start();
    }

    private void goTo(int target) {
        if (transitioning || target == page || target < 0 || target > LAST_PAGE) return;
        int direction = target > page ? 1 : -1;
        View oldHeading = headingStage.getChildAt(headingStage.getChildCount() - 1);
        View oldContent = contentStage.getChildAt(contentStage.getChildCount() - 1);
        View newHeading = headingView(target);
        View newContent = contentPanel(target);
        float headingTravel = headingStage.getWidth() > 0 ? headingStage.getWidth() : ui.dp(390);
        float contentTravel = contentStage.getWidth() > 0 ? contentStage.getWidth() : ui.dp(390);
        newHeading.setTranslationX(direction * headingTravel);
        newContent.setTranslationX(direction * contentTravel);
        headingStage.addView(newHeading, new FrameLayout.LayoutParams(-1, -1));
        contentStage.addView(newContent, new FrameLayout.LayoutParams(-1, -1));
        page = target;
        confirmed = false;
        switchActionLabel();
        updateDots();

        long duration = ui.prefs.reduceMotion() ? 120 : 420;
        transitioning = true;
        newHeading.animate().translationX(0).setDuration(duration)
                .setInterpolator(ui.motionInterpolator()).start();
        oldHeading.animate().translationX(-direction * headingTravel).setDuration(duration)
                .setInterpolator(ui.motionInterpolator())
                .withEndAction(() -> headingStage.removeView(oldHeading)).start();
        newContent.animate().translationX(0).setDuration(duration)
                .setInterpolator(ui.motionInterpolator()).start();
        oldContent.animate().translationX(-direction * contentTravel).setDuration(duration)
                .setInterpolator(ui.motionInterpolator())
                .withEndAction(() -> {
                    contentStage.removeView(oldContent);
                    transitioning = false;
                }).start();
    }

    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        if (handlePageGesture(event)) return true;
        return super.dispatchTouchEvent(event);
    }

    private boolean handlePageGesture(MotionEvent event) {
        int actionMasked = event.getActionMasked();
        if (actionMasked == MotionEvent.ACTION_DOWN) {
            if (transitioning || isInside(action, event.getRawX(), event.getRawY())
                    || isInsideAnyDot(event.getRawX(), event.getRawY())) return false;
            gestureDownX = event.getX();
            gestureDownY = event.getY();
            gestureTarget = -1;
            gestureTracking = true;
            gestureHorizontal = false;
            recycleGestureVelocity();
            gestureVelocity = VelocityTracker.obtain();
            gestureVelocity.addMovement(event);
            return false;
        }
        if (!gestureTracking) return false;
        if (gestureVelocity != null) gestureVelocity.addMovement(event);
        float dx = event.getX() - gestureDownX;
        float dy = event.getY() - gestureDownY;
        if (actionMasked == MotionEvent.ACTION_MOVE) {
            if (!gestureHorizontal) {
                if (Math.abs(dx) < gestureSlop && Math.abs(dy) < gestureSlop) return false;
                if (Math.abs(dy) >= Math.abs(dx)) {
                    cancelGestureTracking();
                    return false;
                }
                int target = dx < 0 ? page + 1 : page - 1;
                if (target < 0 || target > LAST_PAGE) {
                    cancelGestureTracking();
                    return false;
                }
                beginGestureTransition(target);
            }
            updateGestureTransition(dx);
            return true;
        }
        if (actionMasked == MotionEvent.ACTION_UP
                || actionMasked == MotionEvent.ACTION_CANCEL) {
            if (!gestureHorizontal) {
                cancelGestureTracking();
                return false;
            }
            float width = Math.max(1, contentStage.getWidth());
            float velocityX = 0;
            if (gestureVelocity != null) {
                gestureVelocity.computeCurrentVelocity(1000);
                velocityX = gestureVelocity.getXVelocity();
            }
            boolean forward = gestureTarget > page;
            float directedDistance = forward ? -dx : dx;
            float directedVelocity = forward ? -velocityX : velocityX;
            boolean complete = actionMasked == MotionEvent.ACTION_UP
                    && (directedDistance / width >= 0.22f
                    || directedVelocity / width >= 0.45f);
            settleGestureTransition(complete);
            return true;
        }
        return gestureHorizontal;
    }

    private void beginGestureTransition(int target) {
        gestureHorizontal = true;
        gestureTarget = target;
        gestureOldHeading = headingStage.getChildAt(headingStage.getChildCount() - 1);
        gestureOldContent = contentStage.getChildAt(contentStage.getChildCount() - 1);
        gestureNewHeading = headingView(target);
        gestureNewContent = contentPanel(target);
        float headingTravel = Math.max(1, headingStage.getWidth());
        float contentTravel = Math.max(1, contentStage.getWidth());
        float sign = target > page ? 1f : -1f;
        gestureNewHeading.setTranslationX(sign * headingTravel);
        gestureNewContent.setTranslationX(sign * contentTravel);
        headingStage.addView(gestureNewHeading, new FrameLayout.LayoutParams(-1, -1));
        contentStage.addView(gestureNewContent, new FrameLayout.LayoutParams(-1, -1));
    }

    private void updateGestureTransition(float dx) {
        float direction = gestureTarget > page ? -1f : 1f;
        float headingTravel = Math.max(1, headingStage.getWidth());
        float contentTravel = Math.max(1, contentStage.getWidth());
        float directed = Math.max(0, Math.min(contentTravel, dx * direction));
        float contentOffset = directed * direction;
        float headingOffset = contentOffset * headingTravel / contentTravel;
        gestureOldHeading.setTranslationX(headingOffset);
        gestureOldContent.setTranslationX(contentOffset);
        gestureNewHeading.setTranslationX(headingOffset - direction * headingTravel);
        gestureNewContent.setTranslationX(contentOffset - direction * contentTravel);
    }

    private void settleGestureTransition(boolean complete) {
        transitioning = true;
        int oldPage = page;
        int target = gestureTarget;
        float direction = target > oldPage ? -1f : 1f;
        float headingTravel = Math.max(1, headingStage.getWidth());
        float contentTravel = Math.max(1, contentStage.getWidth());
        long duration = ui.prefs.reduceMotion() ? 120 : 420;
        if (complete) {
            page = target;
            confirmed = false;
            switchActionLabel();
            updateDots();
            gestureNewHeading.animate().translationX(0).setDuration(duration)
                    .setInterpolator(ui.motionInterpolator()).start();
            gestureNewContent.animate().translationX(0).setDuration(duration)
                    .setInterpolator(ui.motionInterpolator()).start();
            gestureOldHeading.animate().translationX(direction * headingTravel)
                    .setDuration(duration).setInterpolator(ui.motionInterpolator()).start();
            gestureOldContent.animate().translationX(direction * contentTravel)
                    .setDuration(duration).setInterpolator(ui.motionInterpolator())
                    .withEndAction(() -> finishGestureTransition(true)).start();
        } else {
            gestureOldHeading.animate().translationX(0).setDuration(duration)
                    .setInterpolator(ui.motionInterpolator()).start();
            gestureOldContent.animate().translationX(0).setDuration(duration)
                    .setInterpolator(ui.motionInterpolator()).start();
            gestureNewHeading.animate().translationX(-direction * headingTravel)
                    .setDuration(duration).setInterpolator(ui.motionInterpolator()).start();
            gestureNewContent.animate().translationX(-direction * contentTravel)
                    .setDuration(duration).setInterpolator(ui.motionInterpolator())
                    .withEndAction(() -> finishGestureTransition(false)).start();
        }
    }

    private void finishGestureTransition(boolean completed) {
        if (completed) {
            headingStage.removeView(gestureOldHeading);
            contentStage.removeView(gestureOldContent);
        } else {
            headingStage.removeView(gestureNewHeading);
            contentStage.removeView(gestureNewContent);
        }
        View activeHeading = headingStage.getChildAt(headingStage.getChildCount() - 1);
        View activeContent = contentStage.getChildAt(contentStage.getChildCount() - 1);
        if (activeHeading != null) activeHeading.setTranslationX(0);
        if (activeContent != null) activeContent.setTranslationX(0);
        gestureTracking = false;
        gestureHorizontal = false;
        gestureTarget = -1;
        transitioning = false;
        recycleGestureVelocity();
    }

    private void cancelGestureTracking() {
        gestureTracking = false;
        gestureHorizontal = false;
        gestureTarget = -1;
        recycleGestureVelocity();
    }

    private void recycleGestureVelocity() {
        if (gestureVelocity != null) {
            gestureVelocity.recycle();
            gestureVelocity = null;
        }
    }

    private boolean isInsideAnyDot(float rawX, float rawY) {
        for (TextView dot : dots) if (isInside(dot, rawX, rawY)) return true;
        return false;
    }

    private boolean isInside(View view, float rawX, float rawY) {
        if (view == null || !view.isShown()) return false;
        view.getLocationOnScreen(touchLocation);
        return rawX >= touchLocation[0] && rawX < touchLocation[0] + view.getWidth()
                && rawY >= touchLocation[1] && rawY < touchLocation[1] + view.getHeight();
    }

    private void updateDots() {
        for (int i = 0; i < dots.length; i++) {
            if (dots[i] != null) dots[i].setTextColor(i == page ? ui.p.blue : ui.p.muted);
        }
    }

    private boolean handleBack() {
        if (transitioning) return true;
        if (page == 0) {
            finish();
            return true;
        }
        goTo(page - 1);
        return true;
    }

    @SuppressLint("GestureBackNavigation")
    @Override public void onBackPressed() { handleBack(); }
}
