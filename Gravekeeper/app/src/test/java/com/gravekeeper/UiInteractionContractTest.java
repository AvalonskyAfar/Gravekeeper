package com.gravekeeper;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.Test;

/** Structural gates for interaction defects that do not require an Android runtime. */
public final class UiInteractionContractTest {
    @Test public void firstLaunchCentersTheCompleteActionAndAllPresentationCopy()
            throws IOException {
        String source = mainSource("FirstLaunchActivity.java");
        assertTrue(source.contains("LinearLayout actionLabelGroup"));
        assertTrue(source.contains("new FrameLayout.LayoutParams(-2, -1, Gravity.CENTER)"));
        assertFalse(source.contains("positionActionArrow"));
        assertTrue(source.contains("copy.setTextAlignment(View.TEXT_ALIGNMENT_CENTER)"));
        assertTrue(source.contains("view.setTextAlignment(View.TEXT_ALIGNMENT_CENTER)"));
    }

    @Test public void noImageOnboardingPagesUseOnlyTheOriginalBodyParagraph()
            throws IOException {
        String activity = mainSource("FirstLaunchActivity.java");
        String catalog = mainSource("FirstLaunchPageCatalog.java");
        String noImage = between(activity,
                "// The two no-image pages use the same approved body treatment",
                "private TextView articleText", activity.length());
        assertTrue(noImage.contains("ui.explanatoryText(pageSpec.body)"));
        assertTrue(noImage.contains("content.setGravity(Gravity.CENTER_VERTICAL)"));
        assertFalse(noImage.contains("ui.heading("));
        assertFalse(catalog.contains("ArticleSpec"));
        assertFalse(catalog.contains("守护方式"));
    }

    @Test public void tutorialIndexUsesS1ButChildPagesStayUnframed() throws IOException {
        String source = mainSource("MainActivity.java");
        String index = between(source, "private View tutorialPage()",
                "private View tutorialChildPage()", source.length());
        String children = between(source, "private View tutorialChildPage()",
                "private void preloadTutorialIllustrations()", source.length());
        assertTrue(index.contains("ui.surface()"));
        assertFalse(children.contains("ui.surface()"));
        assertFalse(children.contains("plainTextSurface"));
        assertTrue(children.contains("从后台任务中清除"));
        assertTrue(children.contains("去开启无障碍权限"));
    }

    @Test public void tutorialParagraphsFillEachLineBeforeWrapping()
            throws IOException {
        String source = mainSource("MainActivity.java");
        String paragraph = between(source, "private TextView markdownTutorialParagraph(",
                "/** Prevent Chinese closing punctuation", source.length());
        assertTrue(paragraph.contains("BREAK_STRATEGY_SIMPLE"));
        assertFalse(paragraph.contains("BREAK_STRATEGY_BALANCED"));
    }

    @Test public void recentTaskActionOnlyRemovesThisApplicationsTasks()
            throws IOException {
        String source = mainSource("LowVisibilityManager.java");
        assertTrue(source.contains("setHideRecents(true)"));
        assertTrue(source.contains("manager.getAppTasks()"));
        assertTrue(source.contains("task.finishAndRemoveTask()"));
        assertFalse(source.contains("killBackgroundProcesses"));
    }

    @Test public void notificationSettingsCommitOnlyThroughThePermissionGate()
            throws IOException {
        String source = mainSource("MainActivity.java");
        assertTrue(occurrences(source, "runWithNotificationPermission(control, commit)") >= 3);
        assertTrue(source.contains("REQUEST_NOTIFICATION_PERMISSION"));
        assertTrue(source.contains("onRequestPermissionsResult"));
        assertTrue(source.contains("通知权限未开启，相关设置保持关闭"));
        assertTrue(source.contains("Settings.ACTION_APP_NOTIFICATION_SETTINGS"));
    }

    @Test public void whitelistLivesInsideTheMainGestureHostWithoutWindowSnapshots()
            throws IOException {
        String main = mainSource("MainActivity.java");
        String whitelist = mainSource("WhitelistAccountsPage.java");
        String manifest = project("app/src/main/AndroidManifest.xml");
        assertTrue(main.contains("MAIN, SETTINGS, ADVANCED, WHITELIST"));
        assertTrue(main.contains("show(Page.WHITELIST, 1, true)"));
        assertTrue(main.contains("case WHITELIST: return Page.SETTINGS"));
        assertTrue(main.contains("new WhitelistAccountsPage(this, store, ui).build()"));
        assertFalse(main.contains("startSpatialWindow("));
        assertFalse(manifest.contains("WhitelistAccountsActivity"));
        assertFalse(whitelist.contains("extends Activity"));
        assertFalse(whitelist.contains("applySystemInsets"));
    }

    @Test public void morePageIsCachedWithoutAnyForcedScrollReset() throws IOException {
        String source = mainSource("MainActivity.java");
        assertTrue(source.contains("destination != Page.ADVANCED && destination != Page.MORE"));
        assertFalse(source.contains("scrollCurrentPageToTop"));
        assertFalse(source.contains("page == Page.MORE) scroll"));
    }

    @Test public void sharedScrollHostAvoidsUnneededNestedAndEdgeDispatch() throws IOException {
        String source = mainSource("UiKit.java");
        assertTrue(source.contains("scroll.setNestedScrollingEnabled(false)"));
        assertTrue(source.contains("View.OVER_SCROLL_IF_CONTENT_SCROLLS"));
        assertTrue(source.contains("scroll.setSmoothScrollingEnabled(true)"));
    }

    @Test public void neighborWarmupBuildsIdleNeighborsAndCancelsOnTouch() throws IOException {
        // The fix for scroll jank on fresh entry moved neighbor warmup from a long
        // postDelayed(650ms) to an immediate build on idle swaps (replace), with a
        // short settle delay after animated transitions. The ACTION_DOWN cancel is
        // preserved and must operate on Handler-queued runnables (View.post), which
        // removeCallbacks can unhook. The old 650ms-only timing is a regression.
        String source = mainSource("MainActivity.java");
        assertFalse(source.contains("NEIGHBOR_WARMUP_DELAY_MS"));
        assertTrue(source.contains("postWarmNeighbor(page, true)"));
        assertTrue(source.contains("post(holder[0])"));
        assertTrue(source.contains("NEIGHBOR_WARMUP_SETTLE_DELAY_MS = 250L"));
        assertTrue(source.contains("NEIGHBOR_WARMUP_STEP_MS = 120L"));
        assertTrue(source.contains("cancelPageWarmups();"));
        assertTrue(source.contains("event.getActionMasked() == MotionEvent.ACTION_DOWN"));
    }

    @Test public void moreInformationUsesConsolidatedStableEntries() throws IOException {
        String source = mainSource("MainActivity.java");
        String more = between(source, "private View morePage()",
                "private void addInfoGroup", source.length());
        assertTrue(more.contains("addInfoGroup(root, \"应用说明\""));
        assertTrue(more.contains("MoreDetail.ABOUT"));
        assertTrue(more.contains("MoreDetail.PRIVACY"));
        assertTrue(more.contains("MoreDetail.OPEN_SOURCE"));
        assertTrue(more.contains("MoreDetail.FAQ"));
        assertFalse(more.contains("应用信息"));
        assertFalse(more.contains("隐私与透明度"));
        assertFalse(more.contains("人员与项目"));
        assertFalse(more.contains("帮助与反馈"));
        assertFalse(more.contains("问题报告"));
        assertTrue(source.contains("state.putString(\"detail_id\""));
        assertTrue(source.contains("switch (entry.detail)"));
        assertFalse(source.contains("openDetail(String title, String summary)"));
    }

    @Test public void navigationStabilizesBeforeRoutingAndValidatesCachedPageIdentity()
            throws IOException {
        String source = mainSource("MainActivity.java");
        assertTrue(source.contains("host.stabilizeForNavigation();"));
        assertTrue(source.contains("settlingSource = page;"));
        assertTrue(source.contains("pageIdentities.get(view) != destination"));
        assertFalse(source.contains("void transition(Page from, Page to"));
    }

    @Test public void developerSubpagesCannotReopenFromAStaleRefreshCallback()
            throws IOException {
        String source = mainSource("DeveloperOptionsActivity.java");
        assertTrue(source.contains("refreshDetailIfCurrent(activeDetail)"));
        assertTrue(source.contains("stabilizePageTransition();"));
        assertTrue(source.contains("generation == pageTransitionGeneration"));
        assertFalse(source.contains("postDelayed(() -> showDetail(detailKey, false)"));
    }

    @Test public void hapticPreferenceIsCommittedBeforeHapticDecision() throws IOException {
        String source = mainSource("UiKit.java");
        String commit = between(source, "private void commit(int value)",
                "private void animateThumb(float target", source.length());
        assertTrue(commit.indexOf("listener.changed(value)")
                < commit.indexOf("ui.haptic(this)"));
    }

    @Test public void transientFeedbackReusesHardwareLayersWithoutWindowBlur()
            throws IOException {
        String source = mainSource("UiKit.java");
        assertTrue(occurrences(source, "View.LAYER_TYPE_HARDWARE") >= 2);
        assertFalse(source.contains("FLAG_BLUR_BEHIND"));
        assertFalse(source.contains("setBlurBehindRadius"));
    }

    @Test public void releaseVersionAdvancesForTheTwoOneRelease() throws IOException {
        String build = project("app/build.gradle");
        assertTrue(build.contains("versionCode 23"));
        assertTrue(build.contains("versionName '2.1'"));
    }

    @Test public void notifyAlertFallsBackToOverlayWhenDisabled() throws IOException {
        // notifyAlert() should check areNotificationsEnabled before posting;
        // when disabled it writes to the overlay status line instead of a
        // silently dropped notify() call.
        String service = mainSource("GuardAccessibilityService.java");
        assertTrue(service.contains("areNotificationsEnabled()"));
        assertTrue(service.contains("writeStatus(\"通知未开启，无法发送"));
    }

    @Test public void overlayIsDraggableAndNotFullWidth() throws IOException {
        String controller = mainSource("StatusOverlayController.java");
        assertTrue(controller.contains("WRAP_CONTENT"));
        assertFalse(controller.contains("MATCH_PARENT"));
        assertTrue(controller.contains("FLAG_NOT_FOCUSABLE"));
        assertFalse(controller.contains("FLAG_NOT_TOUCHABLE"));
        assertTrue(controller.contains("updateViewLayout"));
    }

    @Test public void mediaStrategyNotifyPromptsNotificationPermission() throws IOException {
        // When a user selects "提醒" (level 1) the app should nudge for
        // notification permission without blocking the strategy commit.
        String activity = mainSource("MainActivity.java");
        assertTrue(activity.contains("promptNotificationPermissionIfNotifyAndNeeded"));
        assertTrue(activity.contains("开启通知以接收提醒"));
    }

    @Test public void configParsingIsCachedAndInvalidatedOnWrite() throws IOException {
        // Page renderers re-parse the bundled config JSON on every non-cached build.
        // The parsed defaults and the effective config must be cached per-store and
        // rebuilt after every committed write, or the synchronous JSON work runs on
        // the UI thread inside the first drag frame.
        String store = mainSource("config/ConfigStore.java");
        assertTrue(store.contains("private JSONObject defaultsCache"));
        assertTrue(store.contains("private JSONObject effectiveJsonCache"));
        assertTrue(store.contains("private GuardConfig effectiveConfigCache"));
        assertTrue(store.contains("adoptEffective(migrated)"));
        assertTrue(store.indexOf("effectiveJsonCache = migrated") >= 0);
        // Fresh-install (no user config) must not hand out the shared cached defaults:
        // callers mutate the returned JSON before saving.
        assertTrue(store.contains("new JSONObject(defaults.toString())"));
    }

    @Test public void capsuleKeepsItsOriginalGeometryWhileCenteringText()
            throws IOException {
        String ui = mainSource("UiKit.java");
        String capsule = between(ui, "public TextView capsule(",
                "public LinearLayout plainTextSurface", ui.length());
        assertTrue(capsule.contains("button.setGravity(Gravity.CENTER)"));
        assertTrue(capsule.contains("button.setIncludeFontPadding(false)"));
        assertTrue(capsule.contains("button.setPadding(dp(22), dp(13), dp(22), dp(13))"));
        assertFalse(capsule.contains("setMinimumHeight"));
    }

    @Test public void selectedLauncherArtFeedsApplicationAliasAndAdaptiveIcons()
            throws IOException {
        String manifest = project("app/src/main/AndroidManifest.xml");
        String legacy = resource("mipmap-anydpi/ic_gravekeeper_launcher.xml");
        String adaptive = resource("mipmap-anydpi-v26/ic_gravekeeper_launcher.xml");
        String foreground = resource("drawable/ic_gravekeeper_launcher_foreground.xml");
        assertTrue(occurrences(manifest,
                "android:icon=\"@mipmap/ic_gravekeeper_launcher\"") >= 2);
        assertTrue(manifest.contains(
                "android:roundIcon=\"@mipmap/ic_gravekeeper_launcher\""));
        assertTrue(legacy.contains("@drawable/ic_launcher_art"));
        assertTrue(adaptive.contains("@drawable/ic_gravekeeper_launcher_foreground"));
        assertTrue(foreground.contains("@drawable/ic_launcher_art"));
        assertFalse(legacy.contains("ic_notification"));
    }

    private static String mainSource(String file) throws IOException {
        return project("app/src/main/java/com/gravekeeper/" + file);
    }

    private static String resource(String file) throws IOException {
        return project("app/src/main/res/" + file);
    }

    private static String project(String relative) throws IOException {
        Path root = Path.of(System.getProperty("user.dir"));
        Path candidate = root.resolve(relative);
        if (!Files.isRegularFile(candidate)) {
            candidate = root.resolve("..").resolve(relative).normalize();
        }
        return new String(Files.readAllBytes(candidate), StandardCharsets.UTF_8);
    }

    private static String between(String source, String start, String end, int fallbackEnd) {
        int from = source.indexOf(start);
        int to = from < 0 ? -1 : source.indexOf(end, from + start.length());
        assertTrue("missing start marker: " + start, from >= 0);
        return source.substring(from, to >= 0 ? to : fallbackEnd);
    }

    private static int occurrences(String source, String needle) {
        int result = 0;
        int offset = 0;
        while ((offset = source.indexOf(needle, offset)) >= 0) {
            result++;
            offset += needle.length();
        }
        return result;
    }
}
