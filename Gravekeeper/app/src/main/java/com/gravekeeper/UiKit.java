package com.gravekeeper;

import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Bitmap;
import android.graphics.ColorFilter;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.graphics.LinearGradient;
import android.graphics.Outline;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.text.InputType;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.Display;
import android.view.Surface;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewConfiguration;
import android.view.VelocityTracker;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.animation.PathInterpolator;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.Map;

/** Native implementation of the approved L1/S1/D1/K1/K4 dialog language. */
public final class UiKit {
    // Frozen light-theme K1/UI-001 screen colours. These are the final rendered beds
    // confirmed in the approved More-page screenshot, not the darker semantic source
    // colours previously (and incorrectly) painted directly onto Android Canvas.
    private static final int TRACK_OFF = Color.rgb(206, 131, 83);       // #CE8353
    // The yellow and green beds deliberately use the same restrained, screen-presented
    // saturation as the approved orange/blue reference; they are not the old semantic
    // source colours from the HTML draft.
    private static final int TRACK_LIGHT = Color.rgb(222, 190, 119);   // #DEBE77
    private static final int TRACK_STANDARD = Color.rgb(134, 172, 247);// #86ACF7
    private static final int TRACK_STRICT = Color.rgb(128, 184, 151);  // #80B897
    private static final int TRACK_THEME_LIGHT = Color.rgb(226, 237, 247); // #E2EDF7
    private static final int TRACK_OFF_EDGE = Color.rgb(190, 105, 56);
    private static final int TRACK_LIGHT_EDGE = Color.rgb(194, 158, 82);
    private static final int TRACK_STANDARD_EDGE = Color.rgb(105, 144, 224);
    private static final int TRACK_STRICT_EDGE = Color.rgb(91, 151, 116);
    private static final int TRACK_THEME_LIGHT_EDGE = Color.rgb(183, 204, 224);
    private static final String EXTRA_SPATIAL_SNAPSHOT =
            "com.gravekeeper.extra.SPATIAL_SNAPSHOT";
    private static final String EXTRA_SPATIAL_CHILD_FROM_LEFT =
            "com.gravekeeper.extra.SPATIAL_CHILD_FROM_LEFT";
    private static final String EXTRA_THEME_SNAPSHOT =
            "com.gravekeeper.extra.THEME_SNAPSHOT";
    private static final AtomicInteger NEXT_SPATIAL_TOKEN = new AtomicInteger(1);
    private static final ConcurrentHashMap<Integer, Bitmap> SPATIAL_SNAPSHOTS =
            new ConcurrentHashMap<>();
    public interface IntChange { void changed(int value); }
    public interface BoolChange { void changed(boolean value); }
    public interface TextChange { void changed(String value); }
    public interface ValidatedTextChange { boolean changed(String value); }
    public interface SpatialGestureGate { boolean canFinishSpatialGesture(); }

    public static final class Palette {
        public final int bg, surface, raised, ink, muted, border, blue, red, yellow, green, shadow;
        Palette(boolean dark, boolean contrast) {
            if (dark) {
                bg = Color.rgb(24, 29, 36);
                surface = Color.rgb(31, 37, 46);
                raised = Color.rgb(38, 45, 56);
                ink = Color.rgb(235, 239, 244);
                muted = contrast ? Color.rgb(201, 208, 217) : Color.rgb(161, 172, 187);
                border = contrast ? Color.rgb(88, 101, 118) : Color.rgb(54, 64, 77);
                blue = Color.rgb(91, 126, 164);
                red = Color.rgb(151, 89, 96);
                yellow = Color.rgb(157, 128, 75);
                green = Color.rgb(89, 133, 105);
                shadow = Color.argb(100, 0, 0, 0);
            } else {
                // Frozen preview host: both --background and --secondary resolve to white.
                // L1/S1 depth comes from the approved short shadow and inner shading, not
                // from tinting the material blue-grey.
                bg = Color.rgb(255, 255, 255);
                surface = Color.rgb(255, 255, 255);
                raised = Color.rgb(255, 255, 255);
                ink = Color.rgb(26, 28, 31);
                muted = contrast ? Color.rgb(76, 78, 80) : Color.rgb(142, 143, 144);
                border = contrast ? Color.rgb(209, 210, 210) : Color.rgb(237, 237, 237);
                // Frozen UI-002 semantic values from the approved HTML/CSS sample.
                blue = Color.rgb(49, 90, 155);
                red = Color.rgb(200, 88, 88);
                yellow = Color.rgb(224, 179, 58);
                green = Color.rgb(46, 145, 104);
                shadow = Color.argb(23, 26, 28, 31);
            }
        }
    }

    private final Activity activity;
    public final AppPreferences prefs;
    public final Palette p;
    public final boolean dark;
    private final float textScale;
    private LinearLayout activeValidationErrorHost;
    private boolean activeValidationFailed;
    private TextView activeFeedback;

    public static void applyPreferredTheme(Activity activity) {
        boolean dark = new AppPreferences(activity).resolveDark(activity);
        activity.setTheme(dark ? R.style.Theme_Gravekeeper_Dark
                : R.style.Theme_Gravekeeper_Light);
    }

    /** Translucent variant used when an independent child moves over its live parent. */
    public static void applySpatialOverlayTheme(Activity activity) {
        boolean dark = new AppPreferences(activity).resolveDark(activity);
        activity.setTheme(dark ? R.style.Theme_Gravekeeper_Spatial_Dark
                : R.style.Theme_Gravekeeper_Spatial_Light);
    }

    /** First launch is an approved light-only canvas; it must not mutate the global preference. */
    public static void applyFirstLaunchTheme(Activity activity) {
        activity.setTheme(R.style.Theme_Gravekeeper_FirstLaunch);
    }

    /** Captures the old palette immediately before Activity recreation. */
    public static void prepareThemeTransition(Activity activity) {
        Intent intent = activity.getIntent();
        int previous = intent.getIntExtra(EXTRA_THEME_SNAPSHOT, 0);
        Bitmap stale = previous == 0 ? null : SPATIAL_SNAPSHOTS.remove(previous);
        if (stale != null && !stale.isRecycled()) stale.recycle();
        View content = activity.findViewById(android.R.id.content);
        Bitmap bitmap = captureViewBitmap(content);
        if (bitmap == null) return;
        int token = NEXT_SPATIAL_TOKEN.getAndIncrement();
        SPATIAL_SNAPSHOTS.put(token, bitmap);
        intent.putExtra(EXTRA_THEME_SNAPSHOT, token);
    }

    /** Cross-fades the previous palette over the fully rendered new one. */
    public static void playPreparedThemeTransition(Activity activity, View ignoredRoot) {
        Intent intent = activity.getIntent();
        int token = intent.getIntExtra(EXTRA_THEME_SNAPSHOT, 0);
        if (token == 0) return;
        intent.removeExtra(EXTRA_THEME_SNAPSHOT);
        Bitmap bitmap = SPATIAL_SNAPSHOTS.remove(token);
        if (bitmap == null || bitmap.isRecycled()) return;
        View content = activity.findViewById(android.R.id.content);
        if (!(content instanceof ViewGroup)) {
            bitmap.recycle();
            return;
        }
        ViewGroup container = (ViewGroup) content;
        ImageView overlay = new ImageView(activity);
        overlay.setScaleType(ImageView.ScaleType.FIT_XY);
        overlay.setImageBitmap(bitmap);
        overlay.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        container.addView(overlay, new ViewGroup.LayoutParams(-1, -1));
        overlay.animate().alpha(0f).setDuration(300L)
                .setInterpolator(new PathInterpolator(0.2f, 0f, 0f, 1f))
                .withEndAction(() -> {
                    container.removeView(overlay);
                    overlay.setImageDrawable(null);
                    if (!bitmap.isRecycled()) bitmap.recycle();
                }).start();
    }

    private static Bitmap captureViewBitmap(View content) {
        if (content == null || content.getWidth() <= 0 || content.getHeight() <= 0) return null;
        long bytes = (long) content.getWidth() * content.getHeight() * 4L;
        if (bytes > 16L * 1024L * 1024L) return null;
        Bitmap bitmap = Bitmap.createBitmap(content.getWidth(), content.getHeight(),
                Bitmap.Config.ARGB_8888);
        content.draw(new Canvas(bitmap));
        return bitmap;
    }

    public UiKit(Activity activity) {
        this(activity, false);
    }

    /** Creates the light-only first-launch palette without changing any other Activity. */
    public static UiKit forFirstLaunch(Activity activity) {
        return new UiKit(activity, true);
    }

    private UiKit(Activity activity, boolean forceLight) {
        this.activity = activity;
        prefs = new AppPreferences(activity);
        dark = !forceLight && prefs.resolveDark(activity);
        p = new Palette(dark, prefs.highContrast());
        textScale = prefs.largeText() ? 1.13f : 1f;
        applyWindow();
    }

    private void applyWindow() {
        Window window = activity.getWindow();
        if (Build.VERSION.SDK_INT >= 21) {
            WindowManager.LayoutParams attrs = window.getAttributes();
            Display display = activity.getDisplay();
            Display.Mode bestMode = null;
            if (display != null) {
                Display.Mode current = display.getMode();
                for (Display.Mode candidate : display.getSupportedModes()) {
                    if (candidate.getPhysicalWidth() != current.getPhysicalWidth()
                            || candidate.getPhysicalHeight() != current.getPhysicalHeight()) continue;
                    if (bestMode == null
                            || candidate.getRefreshRate() > bestMode.getRefreshRate()) {
                        bestMode = candidate;
                    }
                }
            }
            if (bestMode != null) {
                attrs.preferredDisplayModeId = bestMode.getModeId();
                attrs.preferredRefreshRate = bestMode.getRefreshRate();
            }
            window.setAttributes(attrs);
        }
        window.setStatusBarColor(p.bg);
        window.setNavigationBarColor(p.bg);
        int flags = 0;
        if (!dark && Build.VERSION.SDK_INT >= 23) flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        if (!dark && Build.VERSION.SDK_INT >= 26) flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        window.getDecorView().setSystemUiVisibility(flags);
        if (Build.VERSION.SDK_INT >= 35) {
            window.setStatusBarColor(Color.TRANSPARENT);
            window.setNavigationBarColor(Color.TRANSPARENT);
        }
    }

    public int dp(float value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }

    /** Canvas text uses scaled pixels so it follows the system font setting. */
    public float sp(float value) {
        return value * activity.getResources().getDisplayMetrics().scaledDensity;
    }

    /** TextView/EditText size in sp after applying the app's optional large-text preference. */
    public float scaledTextSp(float value) {
        return value * textScale;
    }

    public PathInterpolator motionInterpolator() {
        return new PathInterpolator(0.22f, 0.78f, 0.18f, 1f);
    }

    public int surfacePaddingDp() { return 9; }

    public void applySystemInsets(View root) {
        if (Build.VERSION.SDK_INT < 35) return;
        final int left = root.getPaddingLeft();
        final int top = root.getPaddingTop();
        final int right = root.getPaddingRight();
        final int bottom = root.getPaddingBottom();
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
            view.setPadding(left + bars.left, top + bars.top,
                    right + bars.right, bottom + bars.bottom);
            return insets;
        });
        root.requestApplyInsets();
    }

    public TextView text(String value, float size, int color, boolean medium) {
        TextView view = new TextView(activity);
        view.setText(value);
        view.setTextSize(size * textScale);
        view.setTextColor(color);
        view.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        view.setIncludeFontPadding(false);
        view.setLineSpacing(0f, 1.16f);
        if (medium) view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return view;
    }

    public TextView pageTitle(String value) {
        TextView view = text(value, 20, p.ink, true);
        view.setGravity(Gravity.START);
        view.setPadding(dp(10), 0, dp(10), 0);
        return view;
    }

    public LinearLayout heading(String title, String detail) {
        LinearLayout group = column();
        group.setPadding(dp(10), 0, dp(10), 0);
        TextView titleView = text(title, 17, p.ink, true);
        group.addView(titleView, matchWrap());
        if (detail != null && !detail.isBlank()) {
            TextView detailView = text(stripTerminalPeriod(detail), 12.5f, p.muted, false);
            LinearLayout.LayoutParams params = matchWrap();
            params.topMargin = dp(4);
            group.addView(detailView, params);
        }
        return group;
    }

    private static String stripTerminalPeriod(String value) {
        String result = value.trim();
        while (result.endsWith("。")) result = result.substring(0, result.length() - 1);
        return result;
    }

    public LinearLayout column() {
        LinearLayout view = new LinearLayout(activity);
        view.setOrientation(LinearLayout.VERTICAL);
        return view;
    }

    public LinearLayout pageColumn() {
        LinearLayout root = column();
        root.setPadding(dp(12), dp(22), dp(12), dp(34));
        root.setBackgroundColor(p.bg);
        return root;
    }

    public ScrollView scroll(LinearLayout content) {
        return scroll(content, false);
    }

    /** Scroll host for content already placed inside an S1; keeps the parent material visible. */
    public ScrollView embeddedScroll(LinearLayout content) {
        return scroll(content, true);
    }

    private ScrollView scroll(LinearLayout content, boolean transparentBackground) {
        ScrollView scroll = new ScrollView(activity);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        // Keep one owner for vertical drag/fling physics. Android's ScrollView uses
        // OverScroller and EdgeEffect: Android 12+ supplies the mature stretch/return
        // treatment, while Android 11 keeps the platform-compatible edge feedback.
        // A second translation animator here would fight the page-return gesture.
        // There is no nested-scrolling parent in the app shell; skipping that dispatch
        // path keeps each touch/fling on the same small native route.
        scroll.setNestedScrollingEnabled(false);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setHorizontalScrollBarEnabled(false);
        // Short pages do not need an EdgeEffect at all. Long pages retain the platform
        // stretch/return treatment without allocating edge state for every screen.
        scroll.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        scroll.setSmoothScrollingEnabled(true);
        scroll.setBackgroundColor(transparentBackground ? Color.TRANSPARENT : p.bg);
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));
        return scroll;
    }

    public LinearLayout surface() {
        LinearLayout surface = column();
        surface.setPadding(dp(surfacePaddingDp()), dp(surfacePaddingDp()),
                dp(surfacePaddingDp()), dp(surfacePaddingDp()));
        surface.setBackground(surfaceDrawable());
        surface.setElevation(0);
        surface.setClipToOutline(false);
        surface.setClipChildren(false);
        surface.setClipToPadding(false);
        return surface;
    }

    public Drawable surfaceDrawable() {
        return new MaterialSurfaceDrawable(this, 8f, false, true);
    }

    /** Draws the frozen S1 material through the same native Drawable used by live surfaces. */
    public void drawSurfaceMaterial(Canvas canvas, RectF bounds, float cornerDp,
            boolean danger, boolean shadow) {
        MaterialSurfaceDrawable drawable =
                new MaterialSurfaceDrawable(this, cornerDp, danger, shadow);
        drawable.setBounds(Math.round(bounds.left), Math.round(bounds.top),
                Math.round(bounds.right), Math.round(bounds.bottom));
        drawable.draw(canvas);
    }

    /** Service/overlay counterpart of M1. It uses the same palette and drawable formula. */
    public static Drawable overlaySurfaceDrawable(Context context) {
        return new ContextMaterialDrawable(context);
    }

    /** Applies the shared M1/S1 material to non-LinearLayout hosts such as onboarding S1. */
    public void applySurfaceMaterial(View view) {
        view.setBackground(surfaceDrawable());
        view.setElevation(0);
    }

    /** Shared recessed field used by dialogs, K10 and engineering editors. */
    public Drawable inputFieldDrawable() {
        return new InsetFieldDrawable(this, 9f);
    }

    /** Recessed media/image well used by frozen tutorial and onboarding placeholders. */
    public Drawable mediaWellDrawable() {
        return new InsetFieldDrawable(this, 7f);
    }

    /** Image is a child of the recessed well, so the well remains visible around all edges. */
    public FrameLayout recessedMediaFrame(Drawable drawable, String description) {
        FrameLayout frame = new FrameLayout(activity);
        frame.setBackground(mediaWellDrawable());
        int inset = dp(ReadingLayoutSpec.MEDIA_WELL_INSET_DP);
        frame.setPadding(inset, inset, inset, inset);

        ClippedImageView image = new ClippedImageView(activity,
                dp(ReadingLayoutSpec.MEDIA_WELL_IMAGE_RADIUS_DP), false);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        image.setImageDrawable(drawable);
        image.setContentDescription(description);
        frame.addView(image, new FrameLayout.LayoutParams(-1, -1));
        return frame;
    }

    /** Media that starts at the first-launch S1 top and shares its two rounded corners. */
    public ImageView onboardingMediaImage(Drawable drawable, String description) {
        ClippedImageView image = new ClippedImageView(activity,
                dp(ReadingLayoutSpec.FIRST_LAUNCH_MEDIA_RADIUS_DP), true);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        image.setImageDrawable(drawable);
        image.setContentDescription(description);
        return image;
    }

    /** Moving D1-above first-launch section: top corners, no lower shadow or border. */
    public Drawable onboardingContentDrawable() {
        return new OnboardingSectionDrawable(this, true);
    }

    /** Fixed first-launch action section: bottom corners and the shared short outer shadow. */
    public Drawable onboardingActionDrawable() {
        return new OnboardingSectionDrawable(this, false);
    }

    /** Neutral row material inside a parent S1; it does not add a second outer shadow. */
    public Drawable embeddedRowDrawable() {
        return new SurfaceFillDrawable(this);
    }

    /** Destructive reveal material used behind the frozen right-swipe whitelist row. */
    public Drawable destructiveRevealDrawable() {
        return new DestructiveRevealDrawable(this);
    }

    public View divider() {
        View divider = new View(activity);
        // Frozen D1: foreground 14% composited on the current L1/S1 background.
        divider.setBackgroundColor(prefs.highContrast() ? p.border : blend(p.bg, p.ink, 0.14f));
        return divider;
    }

    public LinearLayout controlRow(String title, String detail, String[] labels, int selected,
            IntChange listener) {
        float copyWeight = labels.length == 2 ? 2f : 1f;
        float trackWeight = labels.length == 4 ? 4f : labels.length == 3 ? 3f : 1f;
        return controlRow(title, detail, labels, selected, copyWeight, trackWeight, listener);
    }

    /** Utility settings use the frozen More-page 1:1.35 adaptive column relation. */
    public LinearLayout utilityControlRow(String title, String detail, String[] labels,
            int selected, IntChange listener) {
        return controlRow(title, detail, labels, selected, 1f, 1.35f, listener);
    }

    private LinearLayout controlRow(String title, String detail, String[] labels, int selected,
            float copyWeight, float trackWeight, IntChange listener) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setClipChildren(false);
        row.setClipToPadding(false);
        row.setMinimumHeight(dp(detail == null || detail.isBlank() ? 64 : 72));
        LinearLayout copy = column();
        copy.setGravity(Gravity.CENTER);
        TextView main = text(title, 15.5f, p.ink, true);
        main.setGravity(Gravity.CENTER);
        copy.addView(main, matchWrap());
        if (detail != null && !detail.isBlank()) {
            TextView small = text(stripTerminalPeriod(detail), 11.5f, p.muted, false);
            small.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams smallParams = matchWrap();
            smallParams.topMargin = dp(3);
            copy.addView(small, smallParams);
        }
        LinearLayout.LayoutParams copyParams = new LinearLayout.LayoutParams(0, -2, copyWeight);
        copyParams.rightMargin = dp(10);
        row.addView(copy, copyParams);
        SegmentControl control = new SegmentControl(activity, this, labels, selected, listener);
        // The 52dp frozen track keeps an 8dp internal shadow gutter. This prevents Android
        // from clipping the soft material shadow at the child boundary.
        row.addView(control, new LinearLayout.LayoutParams(0, dp(60), trackWeight));
        return row;
    }

    public SegmentControl segment(String[] labels, int selected, IntChange listener) {
        return new SegmentControl(activity, this, labels, selected, listener);
    }

    /** K5: compact unlabeled account switch from whitelist-account-controls.html. */
    public CompactToggle compactToggle(boolean enabled, BoolChange listener) {
        return new CompactToggle(activity, this, enabled, listener);
    }

    /** K8: fixed-center precise value controller from precise-value-stepper.html. */
    public PreciseValueControl preciseValue(String display, Runnable decrease,
            Runnable edit, Runnable increase) {
        return new PreciseValueControl(activity, this, display, decrease, edit, increase);
    }

    /** K5: 3.8rem by 2.15rem red/blue switch with a 1.65rem neutral thumb. */
    @SuppressLint("ViewConstructor")
    public static final class CompactToggle extends View {
        private final UiKit ui;
        private final BoolChange listener;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private boolean enabled;
        private boolean pressed;
        private boolean moved;
        private boolean horizontalDrag;
        private boolean verticalAbort;
        private float downX;
        private float downY;
        private float startThumbPosition;
        private float thumbPosition;
        private float trackPosition;
        private ValueAnimator thumbAnimator;
        private final int touchSlop;

        CompactToggle(Context context, UiKit ui, boolean enabled, BoolChange listener) {
            super(context);
            this.ui = ui;
            this.enabled = enabled;
            this.listener = listener;
            thumbPosition = enabled ? 1f : 0f;
            trackPosition = thumbPosition;
            touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
            setClickable(true);
            setFocusable(true);
            updateDescription();
        }

        private void updateDescription() {
            setContentDescription(enabled ? "白名单已启用" : "白名单已停用");
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float gutterX = ui.dp(1.5f);
            float gutterTop = ui.dp(1f);
            float gutterBottom = ui.dp(3f);
            float padding = ui.dp(2.8f);
            int semantic = UiKit.blend(TRACK_OFF, TRACK_STANDARD, trackPosition);
            int track = ui.dark
                    ? UiKit.blend(ui.p.bg, ui.p.surface, 0.14f)
                    : semantic;
            int edge = ui.dark
                    ? UiKit.blend(ui.p.ink, ui.p.bg, 0.93f)
                    : UiKit.blend(TRACK_OFF_EDGE, TRACK_STANDARD_EDGE, trackPosition);

            rect.set(gutterX, gutterTop, getWidth() - gutterX, getHeight() - gutterBottom);
            RectF trackBounds = new RectF(rect);
            drawRecessedTrack(canvas, paint, trackBounds, ui, track, edge, pressed);

            float thumbSize = Math.min(ui.dp(26.4f), trackBounds.height() - padding * 2f);
            float travel = Math.max(0, trackBounds.width() - padding * 2f - thumbSize);
            float left = trackBounds.left + padding + thumbPosition * travel;
            float top = trackBounds.top + padding + (pressed ? ui.dp(1) : 0);
            rect.set(left, top, left + thumbSize, top + thumbSize);
            drawNeutralThumb(canvas, paint, rect, ui, pressed);
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = event.getX();
                    downY = event.getY();
                    startThumbPosition = thumbPosition;
                    moved = false;
                    horizontalDrag = false;
                    verticalAbort = false;
                    pressed = true;
                    if (thumbAnimator != null) thumbAnimator.cancel();
                    invalidate();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float dx = event.getX() - downX;
                    float dy = event.getY() - downY;
                    if (!horizontalDrag) {
                        if (Math.max(Math.abs(dx), Math.abs(dy)) < touchSlop) return true;
                        if (Math.abs(dy) >= Math.abs(dx)) {
                            verticalAbort = true;
                            pressed = false;
                            getParent().requestDisallowInterceptTouchEvent(false);
                            invalidate();
                            return true;
                        }
                        horizontalDrag = true;
                        getParent().requestDisallowInterceptTouchEvent(true);
                    }
                    moved = true;
                    float travel = compactToggleTravel();
                    thumbPosition = Math.max(0f, Math.min(1f,
                            startThumbPosition + dx / travel));
                    trackPosition = thumbPosition;
                    invalidate();
                    return true;
                case MotionEvent.ACTION_UP:
                    if (verticalAbort) {
                        pressed = false;
                        horizontalDrag = false;
                        getParent().requestDisallowInterceptTouchEvent(false);
                        animateThumb(enabled ? 1f : 0f);
                        return true;
                    }
                    boolean next = moved ? thumbPosition >= 0.5f : !enabled;
                    pressed = false;
                    getParent().requestDisallowInterceptTouchEvent(false);
                    boolean changed = next != enabled;
                    enabled = next;
                    updateDescription();
                    if (changed) {
                        ui.haptic(this);
                        if (listener != null) listener.changed(enabled);
                    }
                    animateThumb(enabled ? 1f : 0f);
                    performClick();
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    pressed = false;
                    horizontalDrag = false;
                    getParent().requestDisallowInterceptTouchEvent(false);
                    animateThumb(enabled ? 1f : 0f);
                    invalidate();
                    return true;
                default:
                    return true;
            }
        }

        @Override public boolean performClick() { return super.performClick(); }

        private float compactToggleTravel() {
            float trackWidth = Math.max(1f, getWidth() - ui.dp(3f));
            float padding = ui.dp(2.8f);
            float trackHeight = Math.max(1f, getHeight() - ui.dp(4f));
            float thumbSize = Math.min(ui.dp(26.4f), trackHeight - padding * 2f);
            return Math.max(1f, trackWidth - padding * 2f - thumbSize);
        }

        private void animateThumb(float target) {
            if (thumbAnimator != null) thumbAnimator.cancel();
            if (ui.prefs.reduceMotion()) {
                thumbPosition = target;
                trackPosition = target;
                invalidate();
                return;
            }
            thumbAnimator = ValueAnimator.ofFloat(thumbPosition, target);
            thumbAnimator.setDuration(MotionSpec.controlDuration(ui));
            thumbAnimator.setInterpolator(ui.motionInterpolator());
            thumbAnimator.addUpdateListener(value -> {
                thumbPosition = (float) value.getAnimatedValue();
                trackPosition = thumbPosition;
                invalidate();
            });
            thumbAnimator.start();
        }
    }

    /** K8 has three fixed interaction zones and a neutral value thumb that never moves. */
    @SuppressLint("ViewConstructor")
    public static final class PreciseValueControl extends View {
        private static final float SIDE_WEIGHT = 1f;
        private static final float VALUE_WEIGHT = 1.35f;
        private static final float TOTAL_WEIGHT = 3.35f;
        private final UiKit ui;
        private final String display;
        private final Runnable decrease;
        private final Runnable edit;
        private final Runnable increase;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private int pressedZone = -1;

        PreciseValueControl(Context context, UiKit ui, String display, Runnable decrease,
                Runnable edit, Runnable increase) {
            super(context);
            this.ui = ui;
            this.display = display;
            this.decrease = decrease;
            this.edit = edit;
            this.increase = increase;
            setClickable(true);
            setFocusable(true);
            setContentDescription("当前值 " + display + "，可减少、编辑或增加");
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            rect.set(ui.dp(1.5f), ui.dp(1.5f), getWidth() - ui.dp(1.5f),
                    getHeight() - ui.dp(6.5f));
            RectF track = new RectF(rect);
            int trackColor = ui.dark
                    ? UiKit.blend(ui.p.bg, ui.p.surface, 0.14f) : TRACK_STANDARD;
            int edge = ui.dark
                    ? UiKit.blend(ui.p.ink, ui.p.bg, 0.93f)
                    : TRACK_STANDARD_EDGE;
            drawRecessedTrack(canvas, paint, track, ui, trackColor, edge, pressedZone >= 0);

            float thumbWidth = track.width() * 0.42f;
            float inset = ui.dp(4f);
            rect.set(track.centerX() - thumbWidth / 2f, track.top + inset,
                    track.centerX() + thumbWidth / 2f, track.bottom - inset);
            drawNeutralThumb(canvas, paint, rect, ui, pressedZone == 1);

            paint.setShader(null);
            paint.clearShadowLayer();
            paint.setStyle(Paint.Style.FILL);
            paint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            paint.setTextAlign(Paint.Align.CENTER);
            float yOffset = pressedZone >= 0 ? ui.dp(1) : 0;
            paint.setTextSize(ui.sp(18f) * ui.textScale);
            Paint.FontMetrics fm = paint.getFontMetrics();
            float baseline = track.centerY() - (fm.ascent + fm.descent) / 2f + yOffset;
            paint.setColor(ui.dark ? ui.p.muted : Color.WHITE);
            float sideCenter = track.width() * (SIDE_WEIGHT / 2f) / TOTAL_WEIGHT;
            canvas.drawText("−", track.left + sideCenter, baseline, paint);
            canvas.drawText("+", track.right - sideCenter, baseline, paint);

            paint.setTextSize(ui.sp(12.5f) * ui.textScale);
            fm = paint.getFontMetrics();
            baseline = track.centerY() - (fm.ascent + fm.descent) / 2f + yOffset;
            paint.setColor(ui.p.ink);
            canvas.drawText(display, track.centerX(), baseline, paint);
        }

        private int zone(float x) {
            float width = Math.max(1f, getWidth());
            float first = width * SIDE_WEIGHT / TOTAL_WEIGHT;
            float second = width * (SIDE_WEIGHT + VALUE_WEIGHT) / TOTAL_WEIGHT;
            return x < first ? 0 : x < second ? 1 : 2;
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    pressedZone = zone(event.getX());
                    invalidate();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    int next = zone(event.getX());
                    if (next != pressedZone) {
                        pressedZone = next;
                        invalidate();
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    int action = pressedZone;
                    pressedZone = -1;
                    invalidate();
                    ui.haptic(this);
                    if (action == 0 && decrease != null) decrease.run();
                    else if (action == 1 && edit != null) edit.run();
                    else if (action == 2 && increase != null) increase.run();
                    performClick();
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    pressedZone = -1;
                    invalidate();
                    return true;
                default:
                    return true;
            }
        }

        @Override public boolean performClick() { return super.performClick(); }
    }

    /** UI-001: dedicated large capsule switch used only by the L1 main page. */
    public PowerSwitch powerSwitch(boolean enabled, BoolChange listener) {
        return new PowerSwitch(activity, this, enabled, listener);
    }

    public FrameLayout entry(String title, String detail, String arrow, View.OnClickListener listener) {
        int rowHeight = dp(detail == null || detail.isBlank() ? 62 : 76);
        FrameLayout row = new FrameLayout(activity);
        row.setMinimumHeight(rowHeight);
        row.setClickable(true);
        row.setFocusable(true);
        FrameLayout pressContent = new FrameLayout(activity);
        pressContent.setClipChildren(false);
        pressContent.setMinimumHeight(rowHeight);
        LinearLayout copy = column();
        copy.setGravity(Gravity.CENTER);
        // The arrow is positioned independently and must never shrink or offset the
        // centered title/description group.
        TextView main = text(title, 15.5f, p.ink, true);
        main.setGravity(Gravity.CENTER);
        copy.addView(main, matchWrap());
        if (detail != null && !detail.isBlank()) {
            TextView small = text(stripTerminalPeriod(detail), 11.8f, p.muted, false);
            small.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams smallParams = matchWrap();
            smallParams.topMargin = dp(4);
            copy.addView(small, smallParams);
        }
        pressContent.addView(copy, new FrameLayout.LayoutParams(
                -1, -2, Gravity.CENTER_VERTICAL));
        if (arrow != null) {
            boolean left = "←".equals(arrow);
            ChevronView arrowView = new ChevronView(activity, this, left, left ? p.ink : p.muted);
            FrameLayout.LayoutParams arrowParams = new FrameLayout.LayoutParams(dp(24), dp(24),
                    Gravity.CENTER_VERTICAL | (left ? Gravity.START : Gravity.END));
            if (left) arrowParams.leftMargin = dp(12);
            else arrowParams.rightMargin = dp(12);
            pressContent.addView(arrowView, arrowParams);
        }
        // A MATCH_PARENT child is not remeasured to a FrameLayout's minimum height when this
        // row itself is measured with WRAP_CONTENT. That left the press content attached to the
        // top of the 76dp D1 region on Android even though the copy requested CENTER gravity.
        // Keep the independently centred title/caption/arrow block at WRAP_CONTENT and centre
        // that block inside the final row height instead. pressContent owns the same minimum
        // height as the row, so the title/caption group is centred in its actual D1 region.
        FrameLayout.LayoutParams pressParams = new FrameLayout.LayoutParams(-1, rowHeight);
        row.addView(pressContent, pressParams);
        attachPress(row, pressContent, listener);
        row.setContentDescription(detail == null ? title : title + "，" + detail);
        return row;
    }

    /** Lucide-compatible 24dp chevron used by every frozen submenu entry. */
    @SuppressLint("ViewConstructor")
    public static final class ChevronView extends View {
        private final UiKit ui;
        private final boolean left;
        private final int color;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();

        ChevronView(Context context, UiKit ui, boolean left) {
            this(context, ui, left, ui.p.muted);
        }

        ChevronView(Context context, UiKit ui, boolean left, int color) {
            super(context);
            this.ui = ui;
            this.left = left;
            this.color = color;
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float cx = getWidth() / 2f;
            float cy = getHeight() / 2f;
            float half = ui.dp(3);
            float rise = ui.dp(6);
            path.reset();
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(ui.dp(2));
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setColor(color);
            if (left) {
                path.moveTo(cx + half, cy + rise);
                path.lineTo(cx - half, cy);
                path.lineTo(cx + half, cy - rise);
            } else {
                path.moveTo(cx - half, cy + rise);
                path.lineTo(cx + half, cy);
                path.lineTo(cx - half, cy - rise);
            }
            canvas.drawPath(path, paint);
        }
    }

    /** Lucide-compatible arrow-right used by the fixed first-launch action row. */
    @SuppressLint("ViewConstructor")
    public static final class ArrowRightView extends View {
        private final UiKit ui;
        private final int color;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();

        public ArrowRightView(Context context, UiKit ui, int color) {
            super(context);
            this.ui = ui;
            this.color = color;
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float cx = getWidth() / 2f;
            float cy = getHeight() / 2f;
            float half = ui.dp(7);
            float head = ui.dp(5);
            path.reset();
            path.moveTo(cx - half, cy);
            path.lineTo(cx + half, cy);
            path.moveTo(cx + half - head, cy - head);
            path.lineTo(cx + half, cy);
            path.lineTo(cx + half - head, cy + head);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(ui.dp(2));
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setColor(color);
            canvas.drawPath(path, paint);
        }
    }

    /** Bottom-level page link with the title centered independently from its icon. */
    public FrameLayout pageLink(String title, boolean right, View.OnClickListener listener) {
        int rowHeight = dp(64);
        FrameLayout host = new FrameLayout(activity);
        host.setMinimumHeight(rowHeight);
        host.setClickable(true);
        host.setFocusable(true);
        FrameLayout content = new FrameLayout(activity);
        content.setClipChildren(false);
        TextView label = text(title, 15, p.muted, true);
        label.setGravity(Gravity.CENTER);
        content.addView(label, new FrameLayout.LayoutParams(-1, -1));
        ChevronView arrow = new ChevronView(activity, this, !right, p.muted);
        content.addView(arrow, new FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER));
        FrameLayout.LayoutParams arrowParams = new FrameLayout.LayoutParams(dp(24), dp(24),
                Gravity.CENTER_VERTICAL | (right ? Gravity.END : Gravity.START));
        if (right) arrowParams.rightMargin = dp(12); else arrowParams.leftMargin = dp(12);
        content.removeView(arrow);
        content.addView(arrow, arrowParams);
        host.addView(content, new FrameLayout.LayoutParams(-1, rowHeight));
        attachPress(host, content, listener);
        host.setContentDescription(title);
        return host;
    }

    /** Compact centered link used only by the advanced/developer hierarchy entries. */
    public FrameLayout pageLinkAdjacent(String title, View.OnClickListener listener) {
        int rowHeight = dp(64);
        FrameLayout host = new FrameLayout(activity);
        host.setMinimumHeight(rowHeight);
        host.setClickable(true);
        host.setFocusable(true);
        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.HORIZONTAL);
        content.setGravity(Gravity.CENTER);
        TextView label = text(title, 15, p.muted, true);
        label.setGravity(Gravity.CENTER);
        content.addView(label, new LinearLayout.LayoutParams(-2, rowHeight));
        ChevronView arrow = new ChevronView(activity, this, false, p.muted);
        LinearLayout.LayoutParams arrowParams = new LinearLayout.LayoutParams(dp(24), dp(24));
        arrowParams.leftMargin = dp(5);
        content.addView(arrow, arrowParams);
        host.addView(content, new FrameLayout.LayoutParams(-2, rowHeight, Gravity.CENTER));
        attachPress(host, content, listener);
        host.setContentDescription(title);
        return host;
    }

    public TextView capsule(String title, boolean danger, View.OnClickListener listener) {
        TextView button = text(title, 14.5f, danger ? Color.WHITE : p.ink, true);
        button.setGravity(Gravity.CENTER);
        button.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
        button.setIncludeFontPadding(false);
        // Preserve the original capsule geometry. Text is centred by gravity and
        // font-padding control, not by increasing every button's minimum height.
        button.setPadding(dp(22), dp(13), dp(22), dp(13));
        button.setBackground(new MaterialSurfaceDrawable(this, 999f, danger, true));
        button.setElevation(0);
        attachPress(button, button, listener);
        return button;
    }

    public LinearLayout plainTextSurface(String text) {
        LinearLayout surface = surface();
        TextView copy = explanatoryText(text);
        copy.setPadding(dp(ReadingLayoutSpec.PLAIN_TEXT_EXTRA_HORIZONTAL_DP),
                dp(ReadingLayoutSpec.PLAIN_TEXT_EXTRA_VERTICAL_DP),
                dp(ReadingLayoutSpec.PLAIN_TEXT_EXTRA_HORIZONTAL_DP),
                dp(ReadingLayoutSpec.PLAIN_TEXT_EXTRA_VERTICAL_DP));
        surface.addView(copy, matchWrap());
        return surface;
    }

    /** Shared article typography used by onboarding, tutorials and explanatory surfaces. */
    public TextView explanatoryText(String value) {
        TextView copy = text(value, ReadingLayoutSpec.BODY_TEXT_SP, p.ink, false);
        copy.setGravity(Gravity.START | Gravity.TOP);
        copy.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        copy.setLineSpacing(dp(ReadingLayoutSpec.BODY_LINE_EXTRA_DP),
                ReadingLayoutSpec.BODY_LINE_MULTIPLIER);
        copy.setLetterSpacing(0.01f);
        return copy;
    }

    /**
     * Frozen R5: a stable two-column, read-only key/value list inside one S1.
     *
     * <p>The row itself deliberately has no click listener, press response, switch or
     * navigation affordance.  Both columns keep the same weights for every item so a long
     * label or value cannot move the alignment of neighbouring rows.</p>
     */
    public LinearLayout readOnlyKeyValueSurface(Map<String, String> values) {
        LinearLayout surface = surface();
        int index = 0;
        for (Map.Entry<String, String> entry : values.entrySet()) {
            LinearLayout row = new LinearLayout(activity);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setMinimumHeight(dp(52));
            row.setPadding(dp(12), dp(8), dp(12), dp(8));

            TextView key = text(entry.getKey(), 13.5f, p.ink, false);
            key.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams keyParams = new LinearLayout.LayoutParams(0, -2, 1.22f);
            keyParams.rightMargin = dp(10);
            row.addView(key, keyParams);

            String valueText = entry.getValue() == null ? "—" : entry.getValue();
            int valueColor = diagnosticValueColor(valueText);
            TextView value = text(valueText, 13.5f, valueColor, true);
            value.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
            value.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_END);
            row.addView(value, new LinearLayout.LayoutParams(0, -2, 1f));
            surface.addView(row, matchWrap());

            if (++index < values.size()) {
                LinearLayout.LayoutParams dividerParams =
                        new LinearLayout.LayoutParams(-1, dp(1));
                dividerParams.setMargins(-dp(surfacePaddingDp()), 0,
                        -dp(surfacePaddingDp()), 0);
                surface.addView(divider(), dividerParams);
            }
        }
        return surface;
    }

    private int diagnosticValueColor(String value) {
        if (value.equals("已开启") || value.equals("运行中") || value.equals("通过")) {
            return p.green;
        }
        if (value.equals("已关闭") || value.equals("失败") || value.equals("不可用")) {
            return p.red;
        }
        return p.ink;
    }

    @SuppressLint("ClickableViewAccessibility")
    public void attachPress(View host, View content, View.OnClickListener listener) {
        host.setOnTouchListener((view, event) -> {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                content.animate().cancel();
                content.animate().translationY(dp(1.5f)).setDuration(55).start();
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                // Restore before OnClick. Spatial launches must never capture or reveal
                // a parent page while its entry is still in the pressed position.
                content.animate().cancel();
                content.setTranslationY(0);
            }
            return false;
        });
        host.setOnClickListener(view -> {
            haptic(view);
            if (listener != null) listener.onClick(view);
        });
    }

    public void haptic(View view) {
        if (prefs.haptics()) view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
    }

    /**
     * Starts an independent Android page with the same complete spatial movement used by
     * the in-app PageHost. The child enters from the side that the approved gesture leaves
     * open; no alpha or scale transition is involved.
     */
    public void startSpatial(android.content.Intent intent, boolean childEntersFromLeft) {
        int snapshotToken = captureSpatialSnapshot();
        if (snapshotToken != 0) {
            intent.putExtra(EXTRA_SPATIAL_SNAPSHOT, snapshotToken);
        }
        intent.putExtra(EXTRA_SPATIAL_CHILD_FROM_LEFT, childEntersFromLeft);
        activity.startActivity(intent);
        if (snapshotToken != 0) {
            // SpatialActivityHost owns the complete transition. Running the window
            // animation as well would double-compose the movement and cause a hitch.
            activity.overridePendingTransition(0, 0);
            return;
        }
        boolean reduced = prefs.reduceMotion();
        activity.overridePendingTransition(
                childEntersFromLeft
                        ? reduced ? R.anim.gk_slide_in_left_reduced : R.anim.gk_slide_in_left
                        : reduced ? R.anim.gk_slide_in_right_reduced : R.anim.gk_slide_in_right,
                childEntersFromLeft
                        ? reduced ? R.anim.gk_slide_out_right_reduced : R.anim.gk_slide_out_right
                        : reduced ? R.anim.gk_slide_out_left_reduced : R.anim.gk_slide_out_left);
    }

    /** Completes a child page's spatial return to its parent. */
    public void finishSpatial(boolean parentEntersFromLeft) {
        View content = activity.findViewById(android.R.id.content);
        if (content instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) content;
            if (group.getChildCount() == 1
                    && group.getChildAt(0) instanceof SpatialActivityHost) {
                SpatialActivityHost host = (SpatialActivityHost) group.getChildAt(0);
                if (host.finishProgrammatically()) return;
            }
        }
        activity.finish();
        boolean reduced = prefs.reduceMotion();
        activity.overridePendingTransition(
                parentEntersFromLeft
                        ? reduced ? R.anim.gk_slide_in_left_reduced : R.anim.gk_slide_in_left
                        : reduced ? R.anim.gk_slide_in_right_reduced : R.anim.gk_slide_in_right,
                parentEntersFromLeft
                        ? reduced ? R.anim.gk_slide_out_right_reduced : R.anim.gk_slide_out_right
                        : reduced ? R.anim.gk_slide_out_left_reduced : R.anim.gk_slide_out_left);
    }

    /**
     * Wraps an independent Activity in the same finger-following spatial model used by
     * MainActivity.  A bounded in-process snapshot reveals the real parent appearance while
     * the child follows the finger; process death simply falls back to the themed L1 color.
     */
    public View spatialRoot(View page) {
        int token = activity.getIntent().getIntExtra(EXTRA_SPATIAL_SNAPSHOT, 0);
        Bitmap parent = token == 0 ? null : SPATIAL_SNAPSHOTS.remove(token);
        boolean childFromLeft = activity.getIntent().getBooleanExtra(
                EXTRA_SPATIAL_CHILD_FROM_LEFT, false);
        return new SpatialActivityHost(activity, this, page, parent, childFromLeft);
    }

    private int captureSpatialSnapshot() {
        View content = activity.findViewById(android.R.id.content);
        Bitmap bitmap = captureViewBitmap(content);
        if (bitmap == null) return 0;
        int token = NEXT_SPATIAL_TOKEN.getAndIncrement();
        SPATIAL_SNAPSHOTS.put(token, bitmap);
        return token;
    }

    /** Deterministic bitmap clipping; unlike an outline, this supports top-only corners. */
    @SuppressLint("AppCompatCustomView")
    private static final class ClippedImageView extends ImageView {
        private final float radiusPx;
        private final boolean topOnly;
        private final Path clipPath = new Path();

        ClippedImageView(Context context, float radiusPx, boolean topOnly) {
            super(context);
            this.radiusPx = radiusPx;
            this.topOnly = topOnly;
        }

        @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
            super.onSizeChanged(width, height, oldWidth, oldHeight);
            clipPath.reset();
            RectF bounds = new RectF(0f, 0f, width, height);
            if (topOnly) {
                float[] radii = {
                        radiusPx, radiusPx, radiusPx, radiusPx,
                        0f, 0f, 0f, 0f
                };
                clipPath.addRoundRect(bounds, radii, Path.Direction.CW);
            } else {
                clipPath.addRoundRect(bounds, radiusPx, radiusPx, Path.Direction.CW);
            }
        }

        @Override protected void onDraw(Canvas canvas) {
            int checkpoint = canvas.save();
            canvas.clipPath(clipPath);
            super.onDraw(canvas);
            canvas.restoreToCount(checkpoint);
        }
    }

    @SuppressLint("ViewConstructor")
    private static final class SpatialActivityHost extends FrameLayout {
        private final Activity activity;
        private final UiKit ui;
        private final View page;
        private final ImageView parentImage;
        private final Bitmap parentBitmap;
        private final int direction;
        private final int slop;
        private final int edge;
        private float downX;
        private float downY;
        private float offset;
        private boolean eligible;
        private boolean dragging;
        private boolean entering = true;
        private boolean enterStarted;
        private boolean finishing;
        private VelocityTracker velocity;

        SpatialActivityHost(Activity activity, UiKit ui, View page, Bitmap parentBitmap,
                boolean childEnteredFromLeft) {
            super(activity);
            this.activity = activity;
            this.ui = ui;
            this.page = page;
            this.parentBitmap = parentBitmap;
            direction = childEnteredFromLeft ? -1 : 1;
            slop = ViewConfiguration.get(activity).getScaledTouchSlop();
            edge = ui.dp(24);
            setBackgroundColor(ui.p.bg);
            setClipChildren(true);
            parentImage = new ImageView(activity);
            parentImage.setScaleType(ImageView.ScaleType.FIT_XY);
            if (parentBitmap != null) parentImage.setImageBitmap(parentBitmap);
            else parentImage.setBackgroundColor(ui.p.bg);
            parentImage.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            addView(parentImage, new FrameLayout.LayoutParams(-1, -1));
            // Keep the real child outside the first frame until its full-width starting
            // position is known. The parent snapshot remains visible, so no fade is used.
            page.setVisibility(INVISIBLE);
            addView(page, new FrameLayout.LayoutParams(-1, -1));
            if (parentBitmap == null) {
                // Direct launches have no parent surface. Keep the page immediately
                // usable while retaining the same interior swipe-return host.
                parentImage.setVisibility(GONE);
                page.setVisibility(VISIBLE);
                entering = false;
                enterStarted = true;
            }
        }

        @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
            super.onSizeChanged(width, height, oldWidth, oldHeight);
            if (!enterStarted && width > 0) {
                enterStarted = true;
                parentImage.setTranslationX(0);
                page.setTranslationX(direction * width);
                page.setVisibility(VISIBLE);
                long duration = ui.prefs.reduceMotion() ? 120 : 420;
                post(() -> {
                    if (finishing) return;
                    if (parentImage.getVisibility() == VISIBLE) {
                        parentImage.animate().translationX(-direction * width)
                                .setDuration(duration).setInterpolator(ui.motionInterpolator()).start();
                    }
                    page.animate().translationX(0).setDuration(duration)
                            .setInterpolator(ui.motionInterpolator())
                            .withEndAction(() -> {
                                entering = false;
                                offset = 0;
                            }).start();
                });
            } else if (!entering && !dragging && !finishing) {
                parentImage.setTranslationX(-direction * width);
            }
        }

        @Override public boolean onInterceptTouchEvent(MotionEvent event) {
            if (entering || finishing) return true;
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                downX = event.getX();
                downY = event.getY();
                offset = 0;
                dragging = false;
                eligible = downX >= edge && downX <= getWidth() - edge
                        && (!(activity instanceof SpatialGestureGate)
                        || ((SpatialGestureGate) activity).canFinishSpatialGesture())
                        && !gestureExcluded(this, Math.round(event.getRawX()),
                                Math.round(event.getRawY()));
                recycleVelocity();
                velocity = VelocityTracker.obtain();
                velocity.addMovement(event);
                return false;
            }
            if (velocity != null) velocity.addMovement(event);
            if (!eligible || action != MotionEvent.ACTION_MOVE) return false;
            float dx = event.getX() - downX;
            float dy = event.getY() - downY;
            if (Math.max(Math.abs(dx), Math.abs(dy)) < slop) return false;
            if (Math.abs(dy) >= Math.abs(dx) || Math.signum(dx) != direction) {
                eligible = false;
                return false;
            }
            dragging = true;
            update(dx);
            return true;
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            if (entering || finishing) return true;
            if (velocity != null) velocity.addMovement(event);
            int action = event.getActionMasked();
            float dx = event.getX() - downX;
            if (action == MotionEvent.ACTION_MOVE) {
                if (dragging) update(dx);
                return true;
            }
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                float speed = 0;
                if (velocity != null) {
                    velocity.computeCurrentVelocity(1000);
                    speed = velocity.getXVelocity();
                }
                boolean complete = action == MotionEvent.ACTION_UP && dragging
                        && (Math.abs(offset) >= getWidth() * 0.28f
                        || (Math.abs(speed) >= 850 && Math.signum(speed) == direction));
                settle(complete);
                recycleVelocity();
                return true;
            }
            return true;
        }

        boolean finishProgrammatically() {
            if (finishing) return true;
            if (getWidth() <= 0) return false;
            if (entering) {
                parentImage.animate().cancel();
                page.animate().cancel();
                entering = false;
                offset = page.getTranslationX();
            }
            settle(true);
            return true;
        }

        private void update(float value) {
            float width = Math.max(1, getWidth());
            offset = direction > 0
                    ? Math.max(0, Math.min(width, value))
                    : Math.min(0, Math.max(-width, value));
            page.setTranslationX(offset);
            parentImage.setTranslationX(offset - direction * width);
        }

        private void settle(boolean complete) {
            finishing = complete;
            float width = Math.max(1, getWidth());
            float target = complete ? direction * width : 0;
            float parentTarget = complete ? 0 : -direction * width;
            float remaining = Math.abs(target - offset) / width;
            long duration = ui.prefs.reduceMotion() ? 120
                    : Math.max(130, Math.round(90 + remaining * 330));
            if (parentImage.getVisibility() == VISIBLE) {
                parentImage.animate().translationX(parentTarget).setDuration(duration)
                        .setInterpolator(ui.motionInterpolator()).start();
            }
            page.animate().translationX(target).setDuration(duration)
                    .setInterpolator(ui.motionInterpolator())
                    .withEndAction(() -> {
                        if (complete) {
                            activity.finish();
                            activity.overridePendingTransition(0, 0);
                        } else {
                            dragging = false;
                            eligible = false;
                            offset = 0;
                        }
                    }).start();
        }

        private boolean gestureExcluded(View candidate, int rawX, int rawY) {
            if (!candidate.isShown()) return false;
            android.graphics.Rect bounds = new android.graphics.Rect();
            if (!candidate.getGlobalVisibleRect(bounds) || !bounds.contains(rawX, rawY)) {
                return false;
            }
            if (candidate instanceof EditText || candidate instanceof SegmentControl
                    || candidate instanceof CompactToggle
                    || candidate instanceof PreciseValueControl
                    || candidate instanceof PowerSwitch) return true;
            if (!(candidate instanceof ViewGroup)) return false;
            ViewGroup group = (ViewGroup) candidate;
            for (int index = group.getChildCount() - 1; index >= 0; index--) {
                if (gestureExcluded(group.getChildAt(index), rawX, rawY)) return true;
            }
            return false;
        }

        private void recycleVelocity() {
            if (velocity != null) {
                velocity.recycle();
                velocity = null;
            }
        }

        @Override protected void onDetachedFromWindow() {
            recycleVelocity();
            parentImage.setImageDrawable(null);
            if (parentBitmap != null && !parentBitmap.isRecycled()) parentBitmap.recycle();
            super.onDetachedFromWindow();
        }
    }

    public void error(String message) {
        if (activeValidationErrorHost != null) {
            activeValidationFailed = true;
            activeValidationErrorHost.removeAllViews();
            TextView content = text(message, 12.5f, Color.WHITE, true);
            content.setGravity(Gravity.CENTER);
            content.setPadding(dp(16), dp(10), dp(16), dp(10));
            content.setBackground(new MaterialSurfaceDrawable(this, 999f, true, true));
            content.setElevation(0);
            activeValidationErrorHost.addView(content, matchWrap());
            activeValidationErrorHost.setVisibility(View.VISIBLE);
            return;
        }
        showFeedback(message, true);
    }

    public void message(String message) {
        showFeedback(message, false);
    }

    private void showFeedback(String message, boolean danger) {
        View decor = activity.findViewById(android.R.id.content);
        if (!(decor instanceof FrameLayout)) return;
        FrameLayout host = (FrameLayout) decor;
        if (activeFeedback != null) {
            activeFeedback.animate().cancel();
            ViewGroup parent = (ViewGroup) activeFeedback.getParent();
            if (parent != null) parent.removeView(activeFeedback);
        }
        TextView content = text(message, 13, danger ? Color.WHITE : p.ink, true);
        content.setGravity(Gravity.CENTER);
        content.setPadding(dp(18), dp(12), dp(18), dp(12));
        content.setBackground(new MaterialSurfaceDrawable(this, 999f, danger, true));
        content.setElevation(0);
        // Cache the complete text + material once. The exit animation then moves a
        // prepared layer instead of rasterizing its shadow on the first retract frame.
        content.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(-2, -2,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        params.leftMargin = dp(18);
        params.rightMargin = dp(18);
        params.bottomMargin = dp(28);
        host.addView(content, params);
        activeFeedback = content;
        content.setTranslationY(dp(18));
        content.animate().translationY(0).setDuration(prefs.reduceMotion() ? 90 : 220)
                .setInterpolator(motionInterpolator()).start();
        content.postDelayed(() -> {
            if (activeFeedback != content) return;
            content.animate().translationY(dp(18))
                    .setDuration(prefs.reduceMotion() ? 90 : 220)
                    .setInterpolator(motionInterpolator())
                    .withEndAction(() -> {
                        if (content.getParent() == host) host.removeView(content);
                        content.setLayerType(View.LAYER_TYPE_NONE, null);
                        if (activeFeedback == content) activeFeedback = null;
                    }).start();
        }, 2600);
    }

    public Dialog confirm(String title, String detail, String positive, boolean danger, Runnable action) {
        return dialog(title, detail, null, "取消", positive, danger, ignored -> {
            action.run();
            return true;
        });
    }

    public Dialog input(String title, String detail, String initial, boolean numeric, TextChange action) {
        return inputValidated(title, detail, initial, numeric, value -> {
            action.changed(value);
            return true;
        });
    }

    public Dialog inputValidated(String title, String detail, String initial, boolean numeric,
            ValidatedTextChange action) {
        EditText editor = new EditText(activity);
        editor.setText(initial == null ? "" : initial);
        editor.setTextColor(p.ink);
        editor.setHintTextColor(p.muted);
        editor.setTextSize(15 * textScale);
        editor.setSingleLine(false);
        editor.setMinLines(numeric ? 1 : 3);
        editor.setMaxLines(numeric ? 1 : 10);
        editor.setInputType(numeric
                ? InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL | InputType.TYPE_NUMBER_FLAG_SIGNED
                : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        editor.setBackground(inputFieldDrawable());
        editor.setPadding(dp(13), dp(11), dp(13), dp(11));
        return dialog(title, detail, editor, "取消", "保存", false, ignored ->
                action.changed(editor.getText().toString()));
    }

    public Dialog form(String title, String detail, View form, ValidatedTextChange action) {
        return dialog(title, detail, form, "取消", "添加", false, action);
    }

    private Dialog dialog(String title, String detail, View custom, String negative, String positive,
            boolean danger, ValidatedTextChange action) {
        Dialog dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setCancelable(true);
        dialog.setCanceledOnTouchOutside(false);
        LinearLayout card = column();
        card.setPadding(dp(18), dp(19), dp(18), 0);
        card.setBackground(new MaterialSurfaceDrawable(this, 16f, false, true));
        // Keep the dialog material on one prepared layer for its complete lifetime.
        card.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        TextView titleView = text(title, 18, p.ink, true);
        titleView.setGravity(Gravity.CENTER);
        card.addView(titleView, matchWrap());
        if (detail != null && !detail.isBlank()) {
            TextView detailView = text(detail, 12.5f, p.muted, false);
            detailView.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams detailParams = matchWrap();
            detailParams.topMargin = dp(7);
            card.addView(detailView, detailParams);
        }
        if (custom != null) {
            LinearLayout.LayoutParams customParams = matchWrap();
            customParams.topMargin = dp(15);
            card.addView(custom, customParams);
        }
        LinearLayout validationError = column();
        validationError.setGravity(Gravity.CENTER);
        validationError.setVisibility(View.GONE);
        LinearLayout.LayoutParams errorParams = matchWrap();
        errorParams.topMargin = dp(10);
        card.addView(validationError, errorParams);
        View horizontal = divider();
        LinearLayout.LayoutParams dividerParams = new LinearLayout.LayoutParams(-1, dp(1));
        dividerParams.setMargins(-dp(18), dp(18), -dp(18), 0);
        card.addView(horizontal, dividerParams);
        LinearLayout actions = new LinearLayout(activity);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        TextView cancel = text(negative, 14, p.muted, true);
        cancel.setGravity(Gravity.CENTER);
        TextView accept = text(positive, 14, danger ? p.red : p.blue, true);
        accept.setGravity(Gravity.CENTER);
        actions.addView(cancel, new LinearLayout.LayoutParams(0, dp(54), 1));
        View vertical = divider();
        actions.addView(vertical, new LinearLayout.LayoutParams(dp(1), -1));
        actions.addView(accept, new LinearLayout.LayoutParams(0, dp(54), 1));
        // The frozen dialog ignores repeated actions while its 320ms close motion is
        // running.  Otherwise Android can dispatch a second click before the window
        // has dismissed and commit an operation twice.
        final boolean[] closing = {false};
        attachPress(cancel, cancel, view -> {
            if (closing[0]) return;
            closing[0] = true;
            cancel.setEnabled(false);
            accept.setEnabled(false);
            dismissDialog(dialog, card);
        });
        attachPress(accept, accept, view -> {
            if (closing[0]) return;
            activeValidationErrorHost = validationError;
            activeValidationFailed = false;
            validationError.removeAllViews();
            validationError.setVisibility(View.GONE);
            boolean accepted;
            try {
                accepted = action.changed(custom instanceof EditText
                        ? ((EditText) custom).getText().toString() : "");
                accepted = accepted && !activeValidationFailed;
            } finally {
                activeValidationErrorHost = null;
            }
            if (accepted) {
                closing[0] = true;
                cancel.setEnabled(false);
                accept.setEnabled(false);
                dismissDialog(dialog, card);
            }
        });
        card.addView(actions, new LinearLayout.LayoutParams(-1, -2));
        dialog.setOnKeyListener((target, keyCode, event) -> {
            if (keyCode != android.view.KeyEvent.KEYCODE_BACK
                    || event.getAction() != android.view.KeyEvent.ACTION_UP) return false;
            if (closing[0]) return true;
            closing[0] = true;
            cancel.setEnabled(false);
            accept.setEnabled(false);
            dismissDialog(dialog, card);
            return true;
        });
        dialog.setContentView(card);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawableResource(android.R.color.transparent);
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams lp = window.getAttributes();
            lp.width = Math.min(dp(352), activity.getResources().getDisplayMetrics().widthPixels - dp(32));
            lp.dimAmount = 0.27f;
            window.setAttributes(lp);
        }
        dialog.show();
        if (window != null) {
            WindowManager.LayoutParams lp = window.getAttributes();
            lp.width = Math.min(dp(352), activity.getResources().getDisplayMetrics().widthPixels - dp(32));
            window.setAttributes(lp);
        }
        if (!prefs.reduceMotion()) {
            card.setAlpha(0f);
            card.setTranslationY(dp(11.5f));
            card.setScaleX(0.978f);
            card.setScaleY(0.978f);
            card.animate().alpha(1f).translationY(0).scaleX(1f).scaleY(1f)
                    .setDuration(320).setInterpolator(motionInterpolator()).start();
        }
        return dialog;
    }

    private void dismissDialog(Dialog dialog, View card) {
        card.animate().cancel();
        if (prefs.reduceMotion()) {
            card.setLayerType(View.LAYER_TYPE_NONE, null);
            dialog.dismiss();
            return;
        }
        card.animate().alpha(0f).translationY(dp(11.5f)).scaleX(0.978f).scaleY(0.978f)
                .setDuration(320).setInterpolator(motionInterpolator())
                .withEndAction(() -> {
                    card.setLayerType(View.LAYER_TYPE_NONE, null);
                    dialog.dismiss();
                }).start();
    }

    public LinearLayout.LayoutParams matchWrap() { return new LinearLayout.LayoutParams(-1, -2); }

    public LinearLayout.LayoutParams margins(int left, int top, int right, int bottom) {
        LinearLayout.LayoutParams params = matchWrap();
        params.setMargins(dp(left), dp(top), dp(right), dp(bottom));
        return params;
    }

    public static int blend(int first, int second, float secondAmount) {
        float a = Math.max(0f, Math.min(1f, secondAmount));
        return Color.rgb(
                Math.round(Color.red(first) * (1 - a) + Color.red(second) * a),
                Math.round(Color.green(first) * (1 - a) + Color.green(second) * a),
                Math.round(Color.blue(first) * (1 - a) + Color.blue(second) * a));
    }

    private static int alphaColor(int color, int alpha) {
        return Color.argb(Math.max(0, Math.min(255, alpha)), Color.red(color),
                Color.green(color), Color.blue(color));
    }

    /**
     * Draws the frozen CSS outer shadow in the finite gutter owned by an Android View.
     *
     * Android clips a View's display list, while CSS shadows may extend outside the element.
     * A native mask blur therefore ends as a rectangular band when the frozen shadow is
     * wider than the View's finite gutter.  Use enough low-alpha shells to reproduce a
     * continuous falloff that reaches zero before that boundary.  The material body drawn by
     * the caller covers the shells' centres, leaving only the soft outer lift.
     */
    private static void drawBoundedOuterShadow(Canvas canvas, Paint paint, RectF bounds,
            float radius, UiKit ui, float blurDp, float offsetDp, int opacity) {
        int restoreColor = paint.getColor();
        int restoreAlpha = paint.getAlpha();
        ColorFilter restoreFilter = paint.getColorFilter();
        android.graphics.Rect clip = canvas.getClipBounds();
        float availableLeft = Math.max(0f, bounds.left - clip.left);
        float availableTop = Math.max(0f, bounds.top - clip.top);
        float availableRight = Math.max(0f, clip.right - bounds.right);
        float availableBottom = Math.max(0f, clip.bottom - bounds.bottom);

        float sideReach = Math.min(Math.min(availableLeft, availableRight), ui.dp(blurDp * 0.48f));
        float topReach = Math.min(availableTop, ui.dp(Math.max(0f, blurDp - offsetDp) * 0.22f));
        float bottomReach = Math.min(availableBottom, ui.dp(offsetDp + blurDp * 0.72f));
        if (sideReach <= 0.1f && topReach <= 0.1f && bottomReach <= 0.1f) {
            paint.setColor(restoreColor);
            paint.setAlpha(restoreAlpha);
            paint.setColorFilter(restoreFilter);
            return;
        }

        paint.setStyle(Paint.Style.FILL);
        paint.setShader(null);
        final int layers = 18;
        // Total accumulated opacity is intentionally slightly below the CSS source alpha.
        // The available native gutter is narrower than an unconstrained browser blur, so
        // preserving the full alpha would make the compact shadow look heavier.
        int layerAlpha = Math.max(1, Math.round(opacity * 0.76f / layers));
        for (int i = 0; i < layers; i++) {
            float factor = 1f - (float) i / layers;
            RectF layer = new RectF(bounds.left - sideReach * factor,
                    bounds.top - topReach * factor,
                    bounds.right + sideReach * factor,
                    bounds.bottom + bottomReach * factor);
            paint.setColor(alphaColor(ui.p.ink, layerAlpha));
            canvas.drawRoundRect(layer, radius + sideReach * factor * 0.38f,
                    radius + sideReach * factor * 0.38f, paint);
        }
        paint.setColor(restoreColor);
        paint.setAlpha(restoreAlpha);
        paint.setColorFilter(restoreFilter);
    }

    /** Draws the frozen single soft outer shadow; dark mode retains its approved treatment. */
    private static void drawSoftLift(Canvas canvas, Paint paint, RectF bounds, float radius,
            UiKit ui, boolean pressed, boolean compact) {
        int restoreColor = paint.getColor();
        int restoreAlpha = paint.getAlpha();
        ColorFilter restoreFilter = paint.getColorFilter();
        if (!ui.dark) {
            // Frozen CSS: K1 0 .38rem .8rem 12%; S1 0 .22rem .5rem 9%.
            float blurDp = compact ? 12.8f : 8f;
            float offsetDp = compact ? 6.08f : 3.52f;
            int opacity = compact ? 31 : 23;
            if (pressed) {
                blurDp = compact ? 5.3f : 4.6f;
                offsetDp = compact ? 2.5f : 1.8f;
                opacity = compact ? 28 : 20;
            }
            drawBoundedOuterShadow(canvas, paint, bounds, radius, ui,
                    blurDp, offsetDp, opacity);
            paint.setColor(restoreColor);
            paint.setAlpha(restoreAlpha);
            paint.setColorFilter(restoreFilter);
            return;
        }
        float scale = pressed ? 0.52f : 1f;
        float[] spread = compact
                ? new float[] {2.8f, 2.05f, 1.35f, 0.72f}
                : new float[] {4.8f, 3.55f, 2.3f, 1.15f};
        int[] opacity = compact
                ? new int[] {16, 20, 25, 30}
                : new int[] {10, 14, 19, 25};
        int base = ui.dark ? Color.BLACK : Color.rgb(30, 39, 50);
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(null);
        for (int i = spread.length - 1; i >= 0; i--) {
            float amount = ui.dp(spread[i]) * scale;
            RectF layer = new RectF(bounds);
            layer.inset(-amount * 0.42f, -amount * 0.18f);
            layer.offset(0, amount * 0.56f);
            paint.setColor(Color.argb(Math.round(opacity[i] * scale),
                    Color.red(base), Color.green(base), Color.blue(base)));
            canvas.drawRoundRect(layer, radius + amount * 0.32f,
                    radius + amount * 0.32f, paint);
        }
        // setColor() also changes Paint alpha. Restore the caller's material state so the
        // following gradient is opaque instead of being tinted by the colored track below.
        paint.setColor(restoreColor);
        paint.setAlpha(restoreAlpha);
        paint.setColorFilter(restoreFilter);
    }

    /**
     * Draws a CSS-style inset shadow as two short edge fades.
     *
     * The approved CSS specifies absolute offsets and blur radii.  The old native port
     * converted those shadows to percentages of the component height, so a 1.6dp shadow
     * could become a 30-50dp grey band on a tall S1.  Keep the affected depth tied to the
     * frozen CSS metrics instead: offset + two blur radii is the complete local falloff.
     */
    private static void drawInnerDepth(Canvas canvas, Paint paint, RectF bounds, float radius,
            UiKit ui, float topOffsetDp, float topBlurDp, int topAlpha,
            float bottomOffsetDp, float bottomBlurDp, int bottomAlpha) {
        Path clip = new Path();
        clip.addRoundRect(bounds, radius, radius, Path.Direction.CW);
        int save = canvas.save();
        canvas.clipPath(clip);
        paint.setStyle(Paint.Style.FILL);

        float topDepth = Math.min(bounds.height() / 2f,
                ui.dp(topOffsetDp + topBlurDp * 2f));
        if (topAlpha > 0 && topDepth > 0) {
            paint.setShader(new LinearGradient(0, bounds.top, 0, bounds.top + topDepth,
                    new int[] {alphaColor(ui.p.ink, topAlpha),
                            alphaColor(ui.p.ink, Math.round(topAlpha * 0.36f)),
                            Color.TRANSPARENT},
                    new float[] {0f,
                            Math.min(0.72f, ui.dp(topOffsetDp) / topDepth), 1f},
                    Shader.TileMode.CLAMP));
            canvas.drawRect(bounds.left, bounds.top, bounds.right, bounds.top + topDepth, paint);
        }

        float bottomDepth = Math.min(bounds.height() / 2f,
                ui.dp(bottomOffsetDp + bottomBlurDp * 2f));
        if (bottomAlpha > 0 && bottomDepth > 0) {
            paint.setShader(new LinearGradient(0, bounds.bottom - bottomDepth, 0, bounds.bottom,
                    new int[] {Color.TRANSPARENT,
                            alphaColor(ui.p.ink, Math.round(bottomAlpha * 0.36f)),
                            alphaColor(ui.p.ink, bottomAlpha)},
                    new float[] {0f,
                            Math.max(0.28f, 1f - ui.dp(bottomOffsetDp) / bottomDepth), 1f},
                    Shader.TileMode.CLAMP));
            canvas.drawRect(bounds.left, bounds.bottom - bottomDepth,
                    bounds.right, bounds.bottom, paint);
        }
        canvas.restoreToCount(save);
        paint.setShader(null);
    }

    /** Frozen S1/M1/K4: inset 0 0.1rem 0.1rem 4%, 0 -0.08rem 0.14rem 5%. */
    private static void drawSurfaceInnerDepth(Canvas canvas, Paint paint, RectF bounds,
            float radius, UiKit ui) {
        drawInnerDepth(canvas, paint, bounds, radius, ui,
                1.6f, 1.6f, 10, 1.28f, 2.24f, 13);
    }

    /** Frozen K3: inset 0 0.16rem 0.12rem 8%, 0 -0.12rem 0.18rem 10%. */
    private static void drawThumbInnerDepth(Canvas canvas, Paint paint, RectF bounds,
            float radius, UiKit ui) {
        drawInnerDepth(canvas, paint, bounds, radius, ui,
                2.56f, 1.92f, 20, 1.92f, 2.88f, 26);
    }

    /** Shared K1/UI-001 material: soft outer lift, recessed colored bed and rounded edge. */
    private static void drawRecessedTrack(Canvas canvas, Paint paint, RectF bounds, UiKit ui,
            int color, int edge, boolean pressed) {
        drawRecessedTrack(canvas, paint, bounds, ui, color, edge, pressed, false);
    }

    private static void drawRecessedTrack(Canvas canvas, Paint paint, RectF bounds, UiKit ui,
            int color, int edge, boolean pressed, boolean mainPower) {
        float radius = bounds.height() / 2f;
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(null);
        if (!ui.dark && mainPower) {
            // Frozen UI-001: 0 .55rem 1.1rem 16%; pressed .28rem .62rem 13%.
            // Use the same bounded native translation as S1/K1 so its edge cannot be cut.
            drawBoundedOuterShadow(canvas, paint, bounds, radius, ui,
                    pressed ? 9.92f : 17.6f, pressed ? 4.48f : 8.8f,
                    pressed ? 33 : 41);
        } else {
            drawSoftLift(canvas, paint, bounds, radius, ui, pressed, true);
        }
        // Preserve the frozen semantic colour as the actual bed colour. Depth is layered on
        // top, never created by mixing the whole track with S1 or by painting it twice.
        paint.setColor(color);
        canvas.drawRoundRect(bounds, radius, radius, paint);

        Path clip = new Path();
        clip.addRoundRect(bounds, radius, radius, Path.Direction.CW);
        int save = canvas.save();
        canvas.clipPath(clip);

        // Frozen K1: inset 0 0.2rem 0.32rem 16%.  Its native falloff is local to
        // 3.2dp + 2 * 5.12dp, never a percentage of the entire track height.
        paint.setStyle(Paint.Style.FILL);
        float topDepth = Math.min(bounds.height() / 2f, ui.dp(3.2f + 5.12f * 2f));
        paint.setShader(new LinearGradient(0, bounds.top, 0, bounds.top + topDepth,
                new int[] {
                        Color.argb(ui.dark ? 70 : 41, 0, 0, 0),
                        Color.argb(ui.dark ? 30 : 15, 0, 0, 0),
                        Color.TRANSPARENT
                }, new float[] {0f, Math.min(0.72f, ui.dp(3.2f) / topDepth), 1f},
                Shader.TileMode.CLAMP));
        canvas.drawRect(bounds.left, bounds.top, bounds.right, bounds.top + topDepth, paint);

        // Frozen K1: inset 0 -0.15rem 0.25rem background 34%.
        float bottomDepth = Math.min(bounds.height() / 2f, ui.dp(2.4f + 4f * 2f));
        paint.setShader(new LinearGradient(0, bounds.bottom - bottomDepth, 0, bounds.bottom,
                new int[] {
                        Color.TRANSPARENT,
                        Color.argb(ui.dark ? 18 : 14, 255, 255, 255),
                        Color.argb(ui.dark ? 38 : 87, 255, 255, 255)
                }, new float[] {0f, Math.max(0.28f, 1f - ui.dp(2.4f) / bottomDepth), 1f},
                Shader.TileMode.CLAMP));
        canvas.drawRect(bounds.left, bounds.bottom - bottomDepth,
                bounds.right, bounds.bottom, paint);
        canvas.restoreToCount(save);

        // The one-dp border is the frozen per-state edge colour (#984040/#ad8215/
        // #244879/#1f6f4e). It must not become a generic grey or a vertical colour ramp.
        RectF border = new RectF(bounds);
        border.inset(ui.dp(0.5f), ui.dp(0.5f));
        paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(ui.dp(1f));
        paint.setColor(edge);
        canvas.drawRoundRect(border, border.height() / 2f, border.height() / 2f, paint);
        paint.setShader(null);
        paint.setStyle(Paint.Style.FILL);
    }

    /** Shared neutral K3 material. Its edge is deliberately tonal rather than a hard outline. */
    private static void drawNeutralThumb(Canvas canvas, Paint paint, RectF bounds, UiKit ui,
            boolean pressed) {
        float radius = bounds.height() / 2f;
        int[] colors = ui.dark
                ? new int[] {
                        blend(ui.p.raised, ui.p.ink, 0.055f),
                        ui.p.raised,
                        blend(ui.p.raised, ui.p.bg, 0.19f)
                }
                : new int[] {ui.p.raised, ui.p.raised, ui.p.raised};
        paint.setStyle(Paint.Style.FILL);
        if (ui.dark) {
            drawSoftLift(canvas, paint, bounds, radius, ui, pressed, true);
        } else {
            // Frozen K3: 0 .28rem .32rem 23%; pressed 0 .12rem .17rem 19%.
            drawBoundedOuterShadow(canvas, paint, bounds, radius, ui,
                    pressed ? 2.72f : 5.12f, pressed ? 1.92f : 4.48f,
                    pressed ? 48 : 59);
        }
        paint.setShader(new LinearGradient(0, bounds.top, 0, bounds.bottom, colors,
                new float[] {0f, 0.56f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawRoundRect(bounds, radius, radius, paint);
        paint.setShader(null);
        if (!ui.dark) drawThumbInnerDepth(canvas, paint, bounds, radius, ui);

        RectF edge = new RectF(bounds);
        edge.inset(ui.dp(0.45f), ui.dp(0.45f));
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(ui.dp(0.9f));
        if (ui.dark) {
            paint.setShader(new LinearGradient(0, edge.top, 0, edge.bottom,
                    new int[] {Color.argb(105, 255, 255, 255),
                            alphaColor(ui.p.border, 142), Color.argb(88, 30, 39, 50)},
                    new float[] {0f, 0.60f, 1f}, Shader.TileMode.CLAMP));
        } else {
            paint.setShader(null);
            paint.setColor(blend(ui.p.bg, ui.p.ink, 0.24f));
        }
        canvas.drawRoundRect(edge, edge.height() / 2f, edge.height() / 2f, paint);
        paint.setShader(null);
        paint.setStyle(Paint.Style.FILL);
    }

    /** M1/S1/K4/dialog neutral material without Android's hard elevation silhouette. */
    private static final class MaterialSurfaceDrawable extends Drawable {
        private final UiKit ui;
        private final float cornerDp;
        private final boolean danger;
        private final boolean shadow;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private int drawableAlpha = 255;
        private ColorFilter colorFilter;

        MaterialSurfaceDrawable(UiKit ui, float cornerDp, boolean danger, boolean shadow) {
            this.ui = ui;
            this.cornerDp = cornerDp;
            this.danger = danger;
            this.shadow = shadow;
        }

        private void materialBounds(RectF out) {
            android.graphics.Rect bounds = getBounds();
            float horizontal = ui.dp(shadow ? 1.4f : 0.5f);
            float top = ui.dp(shadow ? 1f : 0.5f);
            float bottom = ui.dp(shadow ? 4.8f : 0.5f);
            out.set(bounds.left + horizontal, bounds.top + top,
                    bounds.right - horizontal, bounds.bottom - bottom);
        }

        private float radius(RectF bounds) {
            return cornerDp >= 900f ? bounds.height() / 2f : ui.dp(cornerDp);
        }

        @Override public void draw(Canvas canvas) {
            materialBounds(rect);
            if (rect.width() <= 0 || rect.height() <= 0) return;
            float radius = radius(rect);
            int fill = danger ? ui.p.red : ui.p.surface;
            int top = danger
                    ? blend(fill, Color.WHITE, ui.dark ? 0.055f : 0.10f)
                    : ui.dark ? blend(ui.p.surface, ui.p.ink, 0.028f)
                            : ui.p.surface;
            int middle = fill;
            int bottom = danger
                    ? blend(fill, Color.BLACK, ui.dark ? 0.10f : 0.055f)
                    : ui.dark ? blend(ui.p.surface, ui.p.bg, 0.15f)
                            : ui.p.surface;

            paint.setStyle(Paint.Style.FILL);
            paint.setShader(null);
            paint.setColor(fill);
            paint.setAlpha(drawableAlpha);
            paint.setColorFilter(colorFilter);
            if (shadow) drawSoftLift(canvas, paint, rect, radius, ui, false, false);
            canvas.drawRoundRect(rect, radius, radius, paint);

            if (ui.dark || danger) {
                paint.setShader(new LinearGradient(0, rect.top, 0, rect.bottom,
                        new int[] {top, middle, bottom}, new float[] {0f, 0.56f, 1f},
                        Shader.TileMode.CLAMP));
                canvas.drawRoundRect(rect, radius, radius, paint);
                paint.setShader(null);
            } else {
                // The approved light S1 is a white body. Keep its material depth in the
                // short inner shadow and outer lift instead of tinting the whole surface.
                canvas.drawRoundRect(rect, radius, radius, paint);
            }
            if (!ui.dark && !danger) drawSurfaceInnerDepth(canvas, paint, rect, radius, ui);

            RectF edge = new RectF(rect);
            edge.inset(ui.dp(0.45f), ui.dp(0.45f));
            int edgeBase = danger ? blend(ui.p.red, ui.p.ink, 0.20f)
                    : blend(ui.p.bg, ui.p.ink, ui.dark ? 0.082f : 0.08f);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(ui.dp(0.85f));
            if (ui.dark || danger) {
                paint.setShader(new LinearGradient(0, edge.top, 0, edge.bottom,
                        new int[] {Color.argb(ui.dark ? 66 : 172, 255, 255, 255),
                                alphaColor(edgeBase, ui.dark ? 142 : 126),
                                Color.argb(ui.dark ? 78 : 48, 30, 39, 50)},
                        new float[] {0f, 0.60f, 1f}, Shader.TileMode.CLAMP));
            } else {
                paint.setShader(null);
                paint.setColor(ui.p.border);
            }
            canvas.drawRoundRect(edge, Math.max(0, radius - ui.dp(0.45f)),
                    Math.max(0, radius - ui.dp(0.45f)), paint);
            paint.setShader(null);
            paint.setStyle(Paint.Style.FILL);
        }

        @Override public void setAlpha(int alpha) {
            drawableAlpha = Math.max(0, Math.min(255, alpha));
            invalidateSelf();
        }

        @Override public void setColorFilter(ColorFilter colorFilter) {
            this.colorFilter = colorFilter;
            invalidateSelf();
        }

        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }

        @Override public void getOutline(Outline outline) {
            RectF bounds = new RectF();
            materialBounds(bounds);
            outline.setRoundRect(Math.round(bounds.left), Math.round(bounds.top),
                    Math.round(bounds.right), Math.round(bounds.bottom), radius(bounds));
        }
    }

    private static final class ContextMaterialDrawable extends Drawable {
        private final float density;
        private final boolean dark;
        private final int bg;
        private final int surface;
        private final int border;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private int drawableAlpha = 255;
        private ColorFilter colorFilter;

        ContextMaterialDrawable(Context context) {
            density = context.getResources().getDisplayMetrics().density;
            AppPreferences preferences = new AppPreferences(context);
            // Overlays run from the accessibility service, whose configuration does not
            // necessarily reflect the app's explicit Light/Dark choice. Resolve the same
            // preference used by Activities instead of reading only system uiMode.
            dark = preferences.resolveDark(context);
            Palette palette = new Palette(dark, preferences.highContrast());
            bg = palette.bg;
            surface = palette.surface;
            border = palette.border;
        }

        private float px(float dp) { return dp * density; }

        @Override public void draw(Canvas canvas) {
            android.graphics.Rect bounds = getBounds();
            rect.set(bounds.left + px(1.4f), bounds.top + px(1f),
                    bounds.right - px(1.4f), bounds.bottom - px(4.8f));
            if (rect.width() <= 0 || rect.height() <= 0) return;
            float radius = px(10f);
            int top = dark ? blend(surface, Color.WHITE, 0.028f)
                    : surface;
            int bottom = dark ? blend(surface, bg, 0.15f)
                    : surface;
            int shadowBase = dark ? Color.BLACK : Color.rgb(30, 39, 50);
            paint.setStyle(Paint.Style.FILL);
            paint.setShader(null);
            paint.setColorFilter(colorFilter);
            if (dark) {
                for (int index = 3; index >= 0; index--) {
                    float amount = px(new float[] {4.8f, 3.55f, 2.3f, 1.15f}[index]);
                    RectF shadow = new RectF(rect);
                    shadow.inset(-amount * 0.42f, -amount * 0.18f);
                    shadow.offset(0, amount * 0.56f);
                    int opacity = new int[] {10, 14, 19, 25}[index];
                    paint.setColor(Color.argb(opacity * drawableAlpha / 255,
                            Color.red(shadowBase), Color.green(shadowBase),
                            Color.blue(shadowBase)));
                    canvas.drawRoundRect(shadow, radius + amount * 0.32f,
                            radius + amount * 0.32f, paint);
                }
            } else {
                paint.setColor(surface);
                paint.setAlpha(drawableAlpha);
                paint.setShadowLayer(px(7f), 0, px(3.1f),
                        Color.argb(23 * drawableAlpha / 255,
                                Color.red(shadowBase), Color.green(shadowBase),
                                Color.blue(shadowBase)));
                canvas.drawRoundRect(rect, radius, radius, paint);
                paint.clearShadowLayer();
            }
            paint.setAlpha(drawableAlpha);
            paint.setShader(new LinearGradient(0, rect.top, 0, rect.bottom,
                    new int[] {top, surface, bottom}, new float[] {0f, 0.56f, 1f},
                    Shader.TileMode.CLAMP));
            canvas.drawRoundRect(rect, radius, radius, paint);
            paint.setShader(null);

            if (!dark) {
                Path clip = new Path();
                clip.addRoundRect(rect, radius, radius, Path.Direction.CW);
                int save = canvas.save();
                canvas.clipPath(clip);
                float topDepth = Math.min(rect.height() / 2f, px(4.8f));
                paint.setShader(new LinearGradient(0, rect.top, 0, rect.top + topDepth,
                        new int[] {Color.argb(10, 26, 28, 31),
                                Color.argb(4, 26, 28, 31), Color.TRANSPARENT},
                        new float[] {0f, px(1.6f) / topDepth, 1f}, Shader.TileMode.CLAMP));
                canvas.drawRect(rect.left, rect.top, rect.right, rect.top + topDepth, paint);
                float bottomDepth = Math.min(rect.height() / 2f, px(5.76f));
                paint.setShader(new LinearGradient(0, rect.bottom - bottomDepth, 0, rect.bottom,
                        new int[] {Color.TRANSPARENT, Color.argb(5, 26, 28, 31),
                                Color.argb(13, 26, 28, 31)},
                        new float[] {0f, 1f - px(1.28f) / bottomDepth, 1f},
                        Shader.TileMode.CLAMP));
                canvas.drawRect(rect.left, rect.bottom - bottomDepth,
                        rect.right, rect.bottom, paint);
                canvas.restoreToCount(save);
                paint.setShader(null);
            }

            RectF edge = new RectF(rect);
            edge.inset(px(0.45f), px(0.45f));
            int edgeBase = blend(bg, dark ? Color.WHITE : Color.rgb(30, 39, 50),
                    dark ? 0.082f : 0.08f);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(px(0.85f));
            if (dark) {
                paint.setShader(new LinearGradient(0, edge.top, 0, edge.bottom,
                        new int[] {Color.argb(66, 255, 255, 255),
                                alphaColor(edgeBase, 142), Color.argb(78, 30, 39, 50)},
                        new float[] {0f, 0.60f, 1f}, Shader.TileMode.CLAMP));
            } else {
                paint.setShader(null);
                paint.setColor(border);
            }
            canvas.drawRoundRect(edge, radius - px(0.45f), radius - px(0.45f), paint);
            paint.setShader(null);

            if (dark) {
                RectF inner = new RectF(rect);
                inner.inset(px(1.25f), px(1.25f));
                paint.setStrokeWidth(px(0.65f));
                paint.setShader(new LinearGradient(0, inner.top, 0, inner.bottom,
                        new int[] {Color.argb(40, 255, 255, 255), Color.TRANSPARENT,
                                Color.argb(36, 30, 39, 50)},
                        new float[] {0f, 0.43f, 1f}, Shader.TileMode.CLAMP));
                canvas.drawRoundRect(inner, radius - px(1.25f), radius - px(1.25f), paint);
                paint.setShader(null);
            }
        }

        @Override public void setAlpha(int alpha) {
            drawableAlpha = Math.max(0, Math.min(255, alpha));
            invalidateSelf();
        }

        @Override public void setColorFilter(ColorFilter colorFilter) {
            this.colorFilter = colorFilter;
            invalidateSelf();
        }

        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    /** K10/dialog input well: a true recessed field with soft internal edge transitions. */
    private static final class InsetFieldDrawable extends Drawable {
        private final UiKit ui;
        private final float cornerDp;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private int drawableAlpha = 255;
        private ColorFilter colorFilter;

        InsetFieldDrawable(UiKit ui, float cornerDp) {
            this.ui = ui;
            this.cornerDp = cornerDp;
        }

        @Override public void draw(Canvas canvas) {
            android.graphics.Rect bounds = getBounds();
            rect.set(bounds.left + ui.dp(0.5f), bounds.top + ui.dp(0.5f),
                    bounds.right - ui.dp(0.5f), bounds.bottom - ui.dp(0.5f));
            float radius = ui.dp(cornerDp);
            int fill = UiKit.blend(ui.p.bg, ui.p.surface, ui.dark ? 0.22f : 0.35f);
            int top = UiKit.blend(fill, ui.p.ink, ui.dark ? 0.07f : 0.045f);
            int bottom = UiKit.blend(fill, ui.p.raised, ui.dark ? 0.025f : 0.16f);
            paint.setStyle(Paint.Style.FILL);
            paint.setAlpha(drawableAlpha);
            paint.setColorFilter(colorFilter);
            paint.setShader(new LinearGradient(0, rect.top, 0, rect.bottom,
                    new int[] {top, fill, bottom}, new float[] {0f, 0.48f, 1f},
                    Shader.TileMode.CLAMP));
            canvas.drawRoundRect(rect, radius, radius, paint);
            paint.setShader(null);

            RectF edge = new RectF(rect);
            edge.inset(ui.dp(0.45f), ui.dp(0.45f));
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(ui.dp(0.9f));
            paint.setShader(new LinearGradient(0, edge.top, 0, edge.bottom,
                    new int[] {
                            Color.argb(ui.dark ? 104 : 74, 0, 0, 0),
                            alphaColor(ui.p.border, ui.dark ? 132 : 156),
                            Color.argb(ui.dark ? 30 : 96, 255, 255, 255)
                    }, new float[] {0f, 0.58f, 1f}, Shader.TileMode.CLAMP));
            canvas.drawRoundRect(edge, Math.max(0, radius - ui.dp(0.45f)),
                    Math.max(0, radius - ui.dp(0.45f)), paint);
            paint.setShader(null);
        }

        @Override public void setAlpha(int alpha) {
            drawableAlpha = Math.max(0, Math.min(255, alpha));
            invalidateSelf();
        }

        @Override public void setColorFilter(ColorFilter colorFilter) {
            this.colorFilter = colorFilter;
            invalidateSelf();
        }

        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    /** S1-owned row fill: the parent's edge and shadow stay authoritative. */
    private static final class SurfaceFillDrawable extends Drawable {
        private final UiKit ui;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int drawableAlpha = 255;
        private ColorFilter colorFilter;

        SurfaceFillDrawable(UiKit ui) {
            this.ui = ui;
        }

        @Override public void draw(Canvas canvas) {
            android.graphics.Rect bounds = getBounds();
            paint.setStyle(Paint.Style.FILL);
            paint.setAlpha(drawableAlpha);
            paint.setColorFilter(colorFilter);
            if (ui.dark) {
                int top = blend(ui.p.surface, ui.p.ink, 0.028f);
                int bottom = blend(ui.p.surface, ui.p.bg, 0.15f);
                paint.setShader(new LinearGradient(0, bounds.top, 0, bounds.bottom,
                        new int[] {top, ui.p.surface, bottom}, new float[] {0f, 0.56f, 1f},
                        Shader.TileMode.CLAMP));
            } else {
                paint.setShader(null);
                paint.setColor(ui.p.surface);
            }
            canvas.drawRect(bounds, paint);
            paint.setShader(null);
        }

        @Override public void setAlpha(int alpha) {
            drawableAlpha = Math.max(0, Math.min(255, alpha));
            invalidateSelf();
        }

        @Override public void setColorFilter(ColorFilter colorFilter) {
            this.colorFilter = colorFilter;
            invalidateSelf();
        }

        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    /** A recessed destructive bed that stays visually subordinate to the parent S1. */
    private static final class DestructiveRevealDrawable extends Drawable {
        private final UiKit ui;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private int drawableAlpha = 255;
        private ColorFilter colorFilter;

        DestructiveRevealDrawable(UiKit ui) { this.ui = ui; }

        @Override public void draw(Canvas canvas) {
            android.graphics.Rect bounds = getBounds();
            rect.set(bounds);
            int base = ui.dark ? blend(ui.p.red, ui.p.bg, 0.26f) : ui.p.red;
            int top = blend(base, ui.p.ink, ui.dark ? 0.13f : 0.09f);
            int bottom = blend(base, Color.WHITE, ui.dark ? 0.035f : 0.12f);
            paint.setAlpha(drawableAlpha);
            paint.setColorFilter(colorFilter);
            paint.setStyle(Paint.Style.FILL);
            paint.setShader(new LinearGradient(0, rect.top, 0, rect.bottom,
                    new int[] {top, base, bottom}, new float[] {0f, 0.54f, 1f},
                    Shader.TileMode.CLAMP));
            canvas.drawRect(rect, paint);
            paint.setShader(null);
            paint.setColor(Color.argb(ui.dark ? 52 : 42, 0, 0, 0));
            canvas.drawRect(rect.left, rect.top, rect.right, rect.top + ui.dp(1.2f), paint);
            paint.setColor(Color.argb(ui.dark ? 26 : 52, 255, 255, 255));
            canvas.drawRect(rect.left, rect.bottom - ui.dp(1.1f), rect.right, rect.bottom, paint);
        }

        @Override public void setAlpha(int alpha) {
            drawableAlpha = Math.max(0, Math.min(255, alpha));
            invalidateSelf();
        }

        @Override public void setColorFilter(ColorFilter colorFilter) {
            this.colorFilter = colorFilter;
            invalidateSelf();
        }

        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    /** Exact native counterpart of first-launch-flow-animation's split S1 sections. */
    private static final class OnboardingSectionDrawable extends Drawable {
        private final UiKit ui;
        private final boolean topSection;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final RectF rect = new RectF();
        private int drawableAlpha = 255;
        private ColorFilter colorFilter;

        OnboardingSectionDrawable(UiKit ui, boolean topSection) {
            this.ui = ui;
            this.topSection = topSection;
        }

        private void shape(RectF bounds, float radius) {
            float[] radii = topSection
                    ? new float[] {radius, radius, radius, radius, 0, 0, 0, 0}
                    : new float[] {0, 0, 0, 0, radius, radius, radius, radius};
            path.reset();
            path.addRoundRect(bounds, radii, Path.Direction.CW);
        }

        @Override public void draw(Canvas canvas) {
            android.graphics.Rect bounds = getBounds();
            float side = ui.dp(0.7f);
            float top = topSection ? ui.dp(0.7f) : 0;
            float bottom = topSection ? 0 : ui.dp(4.8f);
            rect.set(bounds.left + side, bounds.top + top,
                    bounds.right - side, bounds.bottom - bottom);
            float radius = ui.dp(8f);
            shape(rect, radius);

            int fillTop = ui.dark && topSection
                    ? blend(ui.p.bg, ui.p.surface, 0.22f) : ui.p.surface;
            int fillMiddle = ui.dark
                    ? blend(ui.p.bg, ui.p.surface, 0.14f) : ui.p.surface;
            int fillBottom = ui.dark
                    ? blend(ui.p.bg, ui.p.surface, 0.30f) : ui.p.surface;
            paint.setStyle(Paint.Style.FILL);
            paint.setAlpha(drawableAlpha);
            paint.setColorFilter(colorFilter);
            paint.setShader(null);
            if (!topSection) {
                paint.setColor(fillMiddle);
                drawSoftLift(canvas, paint, rect, radius, ui, false, false);
                canvas.drawPath(path, paint);
            }
            paint.setShader(new LinearGradient(0, rect.top, 0, rect.bottom,
                    new int[] {fillTop, fillMiddle, fillBottom},
                    new float[] {0f, 0.56f, 1f}, Shader.TileMode.CLAMP));
            canvas.drawPath(path, paint);
            paint.setShader(null);

            if (!ui.dark) {
                int save = canvas.save();
                canvas.clipPath(path);
                if (topSection) {
                    float depth = Math.min(rect.height(), ui.dp(4.8f));
                    paint.setShader(new LinearGradient(0, rect.top, 0, rect.top + depth,
                            new int[] {alphaColor(ui.p.ink, 10), alphaColor(ui.p.ink, 4),
                                    Color.TRANSPARENT},
                            new float[] {0f, ui.dp(1.6f) / depth, 1f}, Shader.TileMode.CLAMP));
                    canvas.drawRect(rect.left, rect.top, rect.right, rect.top + depth, paint);
                } else {
                    float depth = Math.min(rect.height(), ui.dp(5.76f));
                    paint.setShader(new LinearGradient(0, rect.bottom - depth, 0, rect.bottom,
                            new int[] {Color.TRANSPARENT, alphaColor(ui.p.ink, 5),
                                    alphaColor(ui.p.ink, 13)},
                            new float[] {0f, 1f - ui.dp(1.28f) / depth, 1f},
                            Shader.TileMode.CLAMP));
                    canvas.drawRect(rect.left, rect.bottom - depth,
                            rect.right, rect.bottom, paint);
                }
                canvas.restoreToCount(save);
                paint.setShader(null);
            }

            RectF edge = new RectF(rect);
            edge.inset(ui.dp(0.45f), ui.dp(0.45f));
            shape(edge, Math.max(0, radius - ui.dp(0.45f)));
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(ui.dp(0.85f));
            if (ui.dark) {
                paint.setShader(new LinearGradient(0, edge.top, 0, edge.bottom,
                        new int[] {Color.argb(56, 255, 255, 255),
                                alphaColor(ui.p.border, 120), Color.argb(64, 30, 39, 50)},
                        new float[] {0f, 0.60f, 1f}, Shader.TileMode.CLAMP));
            } else {
                paint.setShader(null);
                paint.setColor(ui.p.border);
            }
            canvas.drawPath(path, paint);
            paint.setShader(null);

            // The shared D1 owns the joining edge between sections.
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(fillMiddle);
            if (topSection) {
                canvas.drawRect(rect.left, rect.bottom - ui.dp(1.5f),
                        rect.right, rect.bottom + ui.dp(1), paint);
            } else {
                canvas.drawRect(rect.left, rect.top - ui.dp(1),
                        rect.right, rect.top + ui.dp(1.5f), paint);
            }
        }

        @Override public void setAlpha(int alpha) {
            drawableAlpha = Math.max(0, Math.min(255, alpha));
            invalidateSelf();
        }

        @Override public void setColorFilter(ColorFilter colorFilter) {
            this.colorFilter = colorFilter;
            invalidateSelf();
        }

        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    @SuppressLint("ViewConstructor")
    public static final class SegmentControl extends View {
        private final UiKit ui;
        private final String[] labels;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private final IntChange listener;
        private int selected;
        private int candidate;
        private float thumbPosition;
        private float trackPosition;
        private boolean dragging;
        private boolean pressed;
        private boolean verticalAbort;
        private float downX;
        private float downY;
        private final int touchSlop;
        private ValueAnimator stateAnimator;

        SegmentControl(Context context, UiKit ui, String[] labels, int selected, IntChange listener) {
            super(context);
            this.ui = ui;
            this.labels = labels.clone();
            this.selected = Math.max(0, Math.min(labels.length - 1, selected));
            candidate = this.selected;
            thumbPosition = this.selected;
            trackPosition = this.selected;
            this.listener = listener;
            touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
            setClickable(true);
            setFocusable(true);
            setContentDescription(labels[this.selected]);
        }

        public int selected() { return selected; }

        public void setSelected(int value, boolean animate) {
            int next = Math.max(0, Math.min(labels.length - 1, value));
            selected = next;
            candidate = next;
            setContentDescription(labels[next]);
            animateThumb(next, animate);
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float shadowLeft = ui.dp(1.5f);
            float shadowTop = ui.dp(1.5f);
            float shadowRight = ui.dp(1.5f);
            float shadowBottom = ui.dp(6.5f);
            float inset = ui.dp(4f);
            rect.set(shadowLeft, shadowTop, getWidth() - shadowRight,
                    getHeight() - shadowBottom);
            RectF trackBounds = new RectF(rect);
            int color = trackColor(trackPosition);
            int edge = trackEdge(trackPosition);
            drawRecessedTrack(canvas, paint, trackBounds, ui, color, edge, pressed);

            float contentLeft = trackBounds.left + inset;
            float contentRight = trackBounds.right - inset;
            float segment = (contentRight - contentLeft) / labels.length;
            float left = contentLeft + thumbPosition * segment;
            rect.set(left, trackBounds.top + inset, left + segment,
                    trackBounds.bottom - inset);
            // Frozen K1 uses overflow:hidden.  Keep K3's lift inside the rounded track so
            // the selected end segment cannot grow a square or clipped shadow corner.
            Path trackClip = new Path();
            trackClip.addRoundRect(trackBounds, trackBounds.height() / 2f,
                    trackBounds.height() / 2f, Path.Direction.CW);
            int thumbClipSave = canvas.save();
            canvas.clipPath(trackClip);
            drawNeutralThumb(canvas, paint, rect, ui, pressed);
            canvas.restoreToCount(thumbClipSave);

            paint.setTypeface(Typeface.create("sans", Typeface.BOLD));
            float labelSize = ui.sp(labels.length == 4 ? 11.2f : 12.2f) * ui.textScale;
            // The thumb border is stroked above; labels must always be filled.
            paint.setStyle(Paint.Style.FILL);
            paint.setShader(null);
            paint.clearShadowLayer();
            paint.setTextSize(labelSize);
            paint.setTextAlign(Paint.Align.CENTER);
            Paint.FontMetrics fm = paint.getFontMetrics();
            float baseline = trackBounds.centerY() - (fm.ascent + fm.descent) / 2f
                    + (pressed ? ui.dp(1) : 0);
            for (int i = 0; i < labels.length; i++) {
                paint.setColor(i == candidate ? ui.p.ink : optionText(i, color));
                canvas.drawText(labels[i], contentLeft + segment * (i + 0.5f), baseline, paint);
            }
        }

        private int semantic(int index) {
            if (isThemeSelector()) {
                return index == 0 ? TRACK_OFF
                        : index == 1 ? TRACK_THEME_LIGHT : TRACK_STANDARD;
            }
            if (labels.length == 2) return index == 0 ? TRACK_OFF : TRACK_STANDARD;
            if (labels.length == 3) return index == 0 ? TRACK_OFF
                    : index == 1 ? TRACK_LIGHT : TRACK_STANDARD;
            return index == 0 ? TRACK_OFF
                    : index == 1 ? TRACK_LIGHT
                    : index == 2 ? TRACK_STANDARD : TRACK_STRICT;
        }

        private int semanticEdge(int index) {
            if (isThemeSelector()) {
                return index == 0 ? TRACK_OFF_EDGE
                        : index == 1 ? TRACK_THEME_LIGHT_EDGE : TRACK_STANDARD_EDGE;
            }
            if (labels.length == 2) return index == 0 ? TRACK_OFF_EDGE : TRACK_STANDARD_EDGE;
            if (labels.length == 3) return index == 0 ? TRACK_OFF_EDGE
                    : index == 1 ? TRACK_LIGHT_EDGE : TRACK_STANDARD_EDGE;
            return index == 0 ? TRACK_OFF_EDGE
                    : index == 1 ? TRACK_LIGHT_EDGE
                    : index == 2 ? TRACK_STANDARD_EDGE : TRACK_STRICT_EDGE;
        }

        private int interpolateSemantic(float position, boolean edge) {
            int low = Math.max(0, Math.min(labels.length - 1, (int) Math.floor(position)));
            int high = Math.max(0, Math.min(labels.length - 1, low + 1));
            float amount = Math.max(0f, Math.min(1f, position - low));
            int first = edge ? semanticEdge(low) : semantic(low);
            int second = edge ? semanticEdge(high) : semantic(high);
            return UiKit.blend(first, second, amount);
        }

        private int trackColor(float position) {
            if (!ui.dark) {
                // Final screen colours are frozen from the approved visual sample. Do not
                // replace them with the darker source semantics from an earlier CSS draft.
                return interpolateSemantic(position, false);
            }
            return UiKit.blend(ui.p.bg, ui.p.surface, 0.14f);
        }

        private int trackEdge(float position) {
            if (!ui.dark) {
                return interpolateSemantic(position, true);
            }
            return UiKit.blend(ui.p.ink, ui.p.bg, 0.93f);
        }

        private int optionText(int index, int track) {
            if (!ui.dark) {
                if (isThemeSelector() && candidate == 1) return Color.rgb(52, 72, 92);
                int active = Math.max(0, Math.min(labels.length - 1, candidate));
                if (active == 1 && labels.length >= 3) return Color.rgb(44, 33, 4);
                if (active == 3 && labels.length == 4) return Color.rgb(244, 255, 249);
                return Color.WHITE;
            }
            int semantic = semantic(index);
            return UiKit.blend(semantic, ui.p.ink, labels.length == 2 ? 0.62f : 0.70f);
        }

        private boolean isThemeSelector() {
            return labels.length == 3
                    && "跟随系统".equals(labels[0])
                    && "浅色".equals(labels[1])
                    && "深色".equals(labels[2]);
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    if (stateAnimator != null) stateAnimator.cancel();
                    downX = event.getX();
                    downY = event.getY();
                    dragging = false;
                    verticalAbort = false;
                    pressed = true;
                    invalidate();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float dx = event.getX() - downX;
                    float dy = event.getY() - downY;
                    if (!dragging) {
                        if (Math.max(Math.abs(dx), Math.abs(dy)) < touchSlop) return true;
                        if (Math.abs(dy) >= Math.abs(dx)) {
                            verticalAbort = true;
                            pressed = false;
                            candidate = selected;
                            getParent().requestDisallowInterceptTouchEvent(false);
                            invalidate();
                            return true;
                        }
                        dragging = true;
                        getParent().requestDisallowInterceptTouchEvent(true);
                    }
                    updateFromX(event.getX(), true);
                    return true;
                case MotionEvent.ACTION_UP:
                    if (verticalAbort) {
                        pressed = false;
                        dragging = false;
                        candidate = selected;
                        animateThumb(selected, true);
                        getParent().requestDisallowInterceptTouchEvent(false);
                        return true;
                    }
                    updateFromX(event.getX(), false);
                    pressed = false;
                    dragging = false;
                    getParent().requestDisallowInterceptTouchEvent(false);
                    commit(candidate);
                    invalidate();
                    performClick();
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    pressed = false;
                    dragging = false;
                    candidate = selected;
                    animateThumb(selected, true);
                    getParent().requestDisallowInterceptTouchEvent(false);
                    return true;
                default:
                    return super.onTouchEvent(event);
            }
        }

        @Override public boolean performClick() { return super.performClick(); }

        private void updateFromX(float x, boolean follow) {
            float left = ui.dp(5.5f);
            float right = getWidth() - ui.dp(5.5f);
            float segment = Math.max(1f, (right - left) / labels.length);
            candidate = Math.max(0, Math.min(labels.length - 1,
                    (int) ((x - left) / segment)));
            if (follow) {
                thumbPosition = Math.max(0f, Math.min(labels.length - 1,
                        (x - left) / segment - 0.5f));
                trackPosition = thumbPosition;
                invalidate();
            }
        }

        private void commit(int value) {
            int old = selected;
            selected = value;
            setContentDescription(labels[value]);
            animateThumb(value, true);
            if (old != value) {
                if (listener != null) listener.changed(value);
                // The haptics preference itself is a segment. Read it only after its
                // callback commits the destination state: enabling confirms with a tick,
                // disabling stays quiet.
                ui.haptic(this);
            }
        }

        private void animateThumb(float target, boolean animate) {
            if (stateAnimator != null) stateAnimator.cancel();
            if (!animate || ui.prefs.reduceMotion()) {
                thumbPosition = target;
                trackPosition = target;
                invalidate();
                return;
            }
            final float startThumb = thumbPosition;
            final float startTrack = trackPosition;
            stateAnimator = ValueAnimator.ofFloat(0f, 1f);
            stateAnimator.setDuration(MotionSpec.controlDuration(ui));
            stateAnimator.setInterpolator(ui.motionInterpolator());
            stateAnimator.addUpdateListener(value -> {
                float progress = (float) value.getAnimatedValue();
                thumbPosition = startThumb + (target - startThumb) * progress;
                trackPosition = startTrack + (target - startTrack) * progress;
                invalidate();
            });
            stateAnimator.start();
        }
    }

    /** UI-001: dedicated large capsule switch used only by the L1 main page. */
    @SuppressLint("ViewConstructor")
    public static final class PowerSwitch extends View {
        private final UiKit ui;
        private final BoolChange listener;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private boolean enabled;
        private boolean pressed;
        private boolean moved;
        private boolean horizontalDrag;
        private boolean verticalAbort;
        private float downX;
        private float downY;
        private float startThumbPosition;
        private float thumbPosition;
        private float trackPosition;
        private ValueAnimator stateAnimator;
        private final int touchSlop;

        PowerSwitch(Context context, UiKit ui, boolean enabled, BoolChange listener) {
            super(context);
            this.ui = ui;
            this.enabled = enabled;
            thumbPosition = enabled ? 1f : 0f;
            trackPosition = thumbPosition;
            this.listener = listener;
            touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
            setClickable(true);
            setFocusable(true);
            updateDescription();
        }

        public boolean isEnabledState() { return enabled; }

        public void setEnabledState(boolean value, boolean animate) {
            enabled = value;
            updateDescription();
            animateState(value ? 1f : 0f, animate);
        }

        private void updateDescription() {
            setContentDescription(enabled ? "保护已开启" : "保护已关闭");
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float shadowLeft = ui.dp(2.5f);
            float shadowTop = ui.dp(2f);
            float shadowRight = ui.dp(2.5f);
            float shadowBottom = ui.dp(10f);
            float inset = ui.dp(5.6f);
            rect.set(shadowLeft, shadowTop, getWidth() - shadowRight,
                    getHeight() - shadowBottom);
            RectF trackBounds = new RectF(rect);

            int off = TRACK_OFF;
            int on = TRACK_STANDARD;
            int semantic = UiKit.blend(off, on, trackPosition);
            int track = ui.dark ? UiKit.blend(ui.p.bg, ui.p.surface, 0.14f)
                    : semantic;
            int edge = ui.dark ? UiKit.blend(ui.p.ink, ui.p.bg, 0.93f)
                    : UiKit.blend(TRACK_OFF_EDGE, TRACK_STANDARD_EDGE, trackPosition);
            drawRecessedTrack(canvas, paint, trackBounds, ui, track, edge, pressed, true);

            float thumbWidth = Math.max(ui.dp(112), trackBounds.width() * 0.56f - inset);
            float travel = Math.max(0, trackBounds.width() - inset * 2 - thumbWidth);
            float left = trackBounds.left + inset + thumbPosition * travel;
            rect.set(left, trackBounds.top + inset, left + thumbWidth,
                    trackBounds.bottom - inset);
            Path trackClip = new Path();
            trackClip.addRoundRect(trackBounds, trackBounds.height() / 2f,
                    trackBounds.height() / 2f, Path.Direction.CW);
            int thumbClipSave = canvas.save();
            canvas.clipPath(trackClip);
            drawNeutralThumb(canvas, paint, rect, ui, pressed);
            canvas.restoreToCount(thumbClipSave);

            float centerX = left + thumbWidth / 2f;
            float centerY = trackBounds.centerY() + (pressed ? ui.dp(1) : 0);
            paint.setColor(ui.p.ink);
            drawPowerGlyph(canvas, centerX - ui.dp(27), centerY, ui.dp(8.5f));
            paint.setStyle(Paint.Style.FILL);
            paint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            paint.setTextAlign(Paint.Align.LEFT);
            paint.setTextSize(ui.sp(14f) * ui.textScale);
            paint.setColor(ui.p.ink);
            boolean visualEnabled = thumbPosition >= 0.5f;
            canvas.drawText(visualEnabled ? "已开启" : "已关闭", centerX - ui.dp(12),
                    centerY - (paint.ascent() + paint.descent()) / 2f, paint);

            paint.setTextAlign(Paint.Align.CENTER);
            paint.setTextSize(ui.sp(14f) * ui.textScale);
            paint.setColor(ui.dark
                    ? UiKit.blend(track, ui.p.ink, 0.82f)
                    : UiKit.blend(track, ui.p.ink, 0.76f));
            canvas.drawText(visualEnabled ? "关闭保护" : "开启保护",
                    trackBounds.left + trackBounds.width() * (visualEnabled ? 0.22f : 0.78f),
                    centerY - (paint.ascent() + paint.descent()) / 2f, paint);
        }

        private void drawPowerGlyph(Canvas canvas, float cx, float cy, float r) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(ui.dp(1.8f));
            paint.setStrokeCap(Paint.Cap.ROUND);
            rect.set(cx - r, cy - r, cx + r, cy + r);
            canvas.drawArc(rect, -48, 276, false, paint);
            canvas.drawLine(cx, cy - r - ui.dp(1), cx, cy + ui.dp(1), paint);
            paint.setStrokeCap(Paint.Cap.BUTT);
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = event.getX();
                    downY = event.getY();
                    startThumbPosition = thumbPosition;
                    moved = false;
                    horizontalDrag = false;
                    verticalAbort = false;
                    pressed = true;
                    if (stateAnimator != null) stateAnimator.cancel();
                    invalidate();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float dx = event.getX() - downX;
                    float dy = event.getY() - downY;
                    if (!horizontalDrag) {
                        if (Math.max(Math.abs(dx), Math.abs(dy)) < touchSlop) return true;
                        if (Math.abs(dy) >= Math.abs(dx)) {
                            verticalAbort = true;
                            pressed = false;
                            getParent().requestDisallowInterceptTouchEvent(false);
                            invalidate();
                            return true;
                        }
                        horizontalDrag = true;
                        getParent().requestDisallowInterceptTouchEvent(true);
                    }
                    moved = true;
                    float travel = powerSwitchTravel();
                    thumbPosition = Math.max(0f, Math.min(1f,
                            startThumbPosition + dx / travel));
                    trackPosition = thumbPosition;
                    invalidate();
                    return true;
                case MotionEvent.ACTION_UP:
                    if (verticalAbort) {
                        pressed = false;
                        horizontalDrag = false;
                        getParent().requestDisallowInterceptTouchEvent(false);
                        animateState(enabled ? 1f : 0f, true);
                        return true;
                    }
                    boolean next = moved ? thumbPosition >= 0.5f : !enabled;
                    pressed = false;
                    getParent().requestDisallowInterceptTouchEvent(false);
                    boolean changed = next != enabled;
                    if (changed) {
                        enabled = next;
                        updateDescription();
                        ui.haptic(this);
                        if (listener != null) listener.changed(enabled);
                    }
                    animateState(enabled ? 1f : 0f, true);
                    performClick();
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    pressed = false;
                    horizontalDrag = false;
                    getParent().requestDisallowInterceptTouchEvent(false);
                    animateState(enabled ? 1f : 0f, true);
                    return true;
                default:
                    return true;
            }
        }

        @Override public boolean performClick() { return super.performClick(); }

        private float powerSwitchTravel() {
            float trackWidth = Math.max(1f, getWidth() - ui.dp(5f));
            float inset = ui.dp(5.6f);
            float thumbWidth = Math.max(ui.dp(112), trackWidth * 0.56f - inset);
            return Math.max(1f, trackWidth - inset * 2f - thumbWidth);
        }

        private void animateState(float target, boolean animate) {
            if (stateAnimator != null) stateAnimator.cancel();
            if (!animate || ui.prefs.reduceMotion()) {
                thumbPosition = target;
                trackPosition = target;
                invalidate();
                return;
            }
            final float startThumb = thumbPosition;
            final float startTrack = trackPosition;
            stateAnimator = ValueAnimator.ofFloat(0f, 1f);
            stateAnimator.setDuration(MotionSpec.controlDuration(ui));
            stateAnimator.setInterpolator(ui.motionInterpolator());
            stateAnimator.addUpdateListener(value -> {
                float progress = (float) value.getAnimatedValue();
                thumbPosition = startThumb + (target - startThumb) * progress;
                trackPosition = startTrack + (target - startTrack) * progress;
                invalidate();
            });
            stateAnimator.start();
        }
    }
}
