package com.gravekeeper;

import android.accessibilityservice.AccessibilityService;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

/**
 * Draggable status panel shown through TYPE_ACCESSIBILITY_OVERLAY.
 * Used during development to surface live capture and whitelist status.
 */
final class StatusOverlayController {
    private final AccessibilityService service;
    private final WindowManager windowManager;
    private TextView view;
    private WindowManager.LayoutParams params;
    private float opacity = 0.88f;
    private boolean desiredVisible;
    private boolean captureHidden;
    private boolean screenActive = true;

    /** Saved position so drag survives text/content updates. */
    private int savedX;
    private int savedY = -1; // -1 = use default

    StatusOverlayController(AccessibilityService service) {
        this.service = service;
        windowManager = (WindowManager) service.getSystemService(
                AccessibilityService.WINDOW_SERVICE);
    }

    void configure(boolean enabled, double opacity) {
        this.opacity = (float) opacity;
        if (!enabled) remove();
        else ensureView();
    }

    void update(String text, boolean visible) {
        desiredVisible = visible;
        if (!visible) {
            if (view != null) view.setVisibility(View.GONE);
            return;
        }
        if (!ensureView()) return;
        view.setAlpha(opacity);
        view.setText(text == null ? "" : text);
        view.setVisibility(captureHidden || !screenActive ? View.INVISIBLE : View.VISIBLE);
    }

    void hideForCapture() {
        captureHidden = true;
        if (view != null) view.setVisibility(View.INVISIBLE);
    }

    void restoreAfterCapture() {
        captureHidden = false;
        if (view != null) view.setVisibility(
                desiredVisible && screenActive ? View.VISIBLE : View.GONE);
    }

    void setScreenActive(boolean active) {
        screenActive = active;
        if (view == null) return;
        view.setVisibility(active && desiredVisible && !captureHidden
                ? View.VISIBLE : View.GONE);
    }

    void remove() {
        if (view == null) return;
        try { windowManager.removeView(view); }
        catch (RuntimeException ignored) { }
        // Remember last position for next time the overlay reappears.
        if (params != null) {
            savedX = params.x;
            savedY = params.y;
        }
        view = null;
        params = null;
        desiredVisible = false;
        captureHidden = false;
        screenActive = true;
    }

    private boolean ensureView() {
        if (view != null) {
            applyStyle(view);
            return true;
        }
        TextView panel = new TextView(service);
        panel.setPadding(dp(12), dp(8), dp(12), dp(8));
        applyStyle(panel);
        panel.setVisibility(View.GONE);

        params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                // Touch is deliberately accepted so the developer can drag.
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = savedX;
        params.y = savedY >= 0 ? savedY : dp(8);

        try {
            windowManager.addView(panel, params);
            panel.setAlpha(opacity);
            view = panel;
            attachDragListener(panel);
            return true;
        } catch (RuntimeException unavailable) {
            view = null;
            params = null;
            return false;
        }
    }

    /** Horizontal drag that repositions the overlay via WindowManager. */
    private void attachDragListener(TextView panel) {
        panel.setOnTouchListener((v, event) -> {
            if (params == null) return false;
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    params.x = (int) event.getRawX();
                    params.y = (int) event.getRawY() - v.getHeight();
                    try { windowManager.updateViewLayout(view, params); }
                    catch (RuntimeException ignored) { }
                    return true;
                case MotionEvent.ACTION_MOVE:
                    params.x = (int) event.getRawX();
                    params.y = (int) event.getRawY() - v.getHeight();
                    try { windowManager.updateViewLayout(view, params); }
                    catch (RuntimeException ignored) { }
                    return true;
                default:
                    return false;
            }
        });
    }

    private void applyStyle(TextView panel) {
        AppPreferences preferences = new AppPreferences(service);
        boolean dark = preferences.resolveDark(service);
        UiKit.Palette palette = new UiKit.Palette(dark, preferences.highContrast());
        panel.setTextColor(palette.ink);
        panel.setTextSize(TypedValue.COMPLEX_UNIT_SP,
                12f * (preferences.largeText() ? 1.13f : 1f));
        panel.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        panel.setBackground(UiKit.overlaySurfaceDrawable(service));
        panel.setLayerType(View.LAYER_TYPE_SOFTWARE, null);
    }

    private int dp(int value) {
        return Math.round(value * service.getResources().getDisplayMetrics().density);
    }
}
