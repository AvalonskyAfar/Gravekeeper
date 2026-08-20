package com.gravekeeper.inference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.gravekeeper.config.GuardConfig;

import org.junit.Test;

import java.util.List;
import java.util.Set;

public final class AccountDetectionTest {
    private static GuardConfig config() {
        return config(false);
    }

    private static GuardConfig config(boolean allowLiveHeaderName) {
        GuardConfig.MediaPolicy media = new GuardConfig.MediaPolicy(
                GuardConfig.Action.IGNORE, 0.5, 0.0);
        GuardConfig.NormalizedRegion shortRegion = new GuardConfig.NormalizedRegion(
                0.0, 0.55, 0.85, 0.95);
        GuardConfig.NormalizedRegion liveRegion = new GuardConfig.NormalizedRegion(
                0.0, 0.02, 0.82, 0.25);
        GuardConfig.AccountDetection detection = new GuardConfig.AccountDetection(
                false, false, false, allowLiveHeaderName, 2, 64, 0.25, List.of("关注"),
                List.of("粉丝", "销量", "人搜过"),
                List.of(shortRegion), List.of(liveRegion),
                List.of(shortRegion, liveRegion));
        GuardConfig.Platform platform = new GuardConfig.Platform(
                "douyin", "抖音", true, Set.of("pkg"), Set.of("东方甄选"),
                0.0, media, media, media, detection);
        return new GuardConfig(0.0, List.of("全球购"), 0.0, List.of("科普"),
                List.of("直播间"), List.of("评论"),
                List.of("抖音号", "快手号", "账号ID"), List.of(platform));
    }

    @Test public void explicitPlatformIdUsesNormalizedGeometry() {
        GuardConfig config = config();
        OcrDocument document = new OcrDocument("抖音号： trusted_01", List.of(
                new OcrDocument.Line("抖音号： trusted_01",
                        0.03, 0.70, 0.34, 0.74)));
        assertEquals("trusted_01", ContentSignals.parse(document, "", config,
                config.platforms.get(0)).accountId);
    }

    @Test public void explicitIdCanBeSplitIntoAdjacentOcrBoxes() {
        GuardConfig config = config();
        OcrDocument document = new OcrDocument("抖音号\ntrusted_01", List.of(
                new OcrDocument.Line("抖音号", 0.03, 0.70, 0.15, 0.74),
                new OcrDocument.Line("trusted_01", 0.17, 0.70, 0.38, 0.74)));
        assertEquals("trusted_01", ContentSignals.parse(document, "", config,
                config.platforms.get(0)).accountId);
    }

    @Test public void nicknameAndAtHandleDoNotBecomeStableAccountId() {
        GuardConfig config = config();
        OcrDocument document = new OcrDocument("直播间\n北同中老\n关注\n@北同中老", List.of(
                new OcrDocument.Line("北同中老", 0.08, 0.06, 0.31, 0.10),
                new OcrDocument.Line("关注", 0.34, 0.06, 0.46, 0.11),
                new OcrDocument.Line("@北同中老", 0.05, 0.70, 0.28, 0.74)));
        assertEquals("", ContentSignals.parse(document, "", config,
                config.platforms.get(0)).accountId);
    }

    @Test public void normalizedLocationRejectsSameTextInCaptionArea() {
        GuardConfig config = config();
        OcrDocument document = new OcrDocument("抖音号：trusted_01", List.of(
                new OcrDocument.Line("抖音号：trusted_01",
                        0.03, 0.35, 0.34, 0.39)));
        assertEquals("", ContentSignals.parse(document, "", config,
                config.platforms.get(0)).accountId);
    }

    @Test public void accessibilityTextCannotReleaseWhitelist() {
        GuardConfig config = config();
        assertEquals("", ContentSignals.parse(OcrDocument.empty(),
                "抖音号：trusted_01", config, config.platforms.get(0)).accountId);
    }

    @Test public void liveHeaderNameRequiresExplicitLiveNameMode() {
        GuardConfig disabled = config(false);
        GuardConfig enabled = config(true);
        OcrDocument document = new OcrDocument("直播间\n东方甄选\n关注", List.of(
                new OcrDocument.Line("东方甄选", 0.08, 0.06, 0.25, 0.10),
                new OcrDocument.Line("关注", 0.28, 0.06, 0.36, 0.10)));
        assertEquals("", ContentSignals.parse(document, "", disabled,
                disabled.platforms.get(0)).accountId);
        assertEquals("东方甄选", ContentSignals.parse(document, "", enabled,
                enabled.platforms.get(0)).accountId);
    }

    @Test public void liveNameAndFollowAnchorInSameOcrLineAreAccepted() {
        GuardConfig config = config(true);
        OcrDocument document = new OcrDocument("直播间\n东方甄选 关注", List.of(
                new OcrDocument.Line("东方甄选 关注", 0.08, 0.06, 0.38, 0.10)));
        assertEquals("东方甄选", ContentSignals.parse(document, "", config,
                config.platforms.get(0)).accountId);
    }

    @Test public void exactModeRecoversNameBeforeAttachedFollowAnchor() {
        GuardConfig config = config(true);
        GuardConfig.Platform bundled = config.platforms.get(0);
        GuardConfig.Platform exact = new GuardConfig.Platform(
                bundled.id, bundled.name, bundled.enabled, bundled.packages,
                bundled.whitelistIds, bundled.riskBias, bundled.shortVideo, bundled.live,
                bundled.unknown, bundled.accountDetection,
                GuardConfig.WhitelistMatchMode.EXACT);
        OcrDocument document = new OcrDocument("直播间\n东方甄选已关注", List.of(
                new OcrDocument.Line("东方甄选已关注", 0.08, 0.06, 0.38, 0.10)));
        String account = ContentSignals.parse(document, "", config, exact).accountId;
        assertEquals("东方甄选", account);
        assertTrue(exact.whitelistMatches(account));
    }

    @Test public void screenSharingOverlayCannotBeatSameRowAccountName() {
        GuardConfig config = config(true);
        OcrDocument document = new OcrDocument(
                "直播间\n东方甄选\n8.3万本场点袋\n屏幕共享\n关注", List.of(
                new OcrDocument.Line("东方甄选", 0.128, 0.062, 0.244, 0.077),
                new OcrDocument.Line("8.3万本场点袋", 0.128, 0.080, 0.256, 0.090),
                new OcrDocument.Line("D屏幕共享", 0.244, 0.010, 0.481, 0.032),
                new OcrDocument.Line("关注", 0.329, 0.070, 0.386, 0.083)));
        assertEquals("东方甄选", ContentSignals.parse(document, "", config,
                config.platforms.get(0)).accountId);
    }

    @Test public void configuredWhitelistRecoversNameAfterAvatarOcrNoise() {
        GuardConfig config = config(true);
        OcrDocument document = new OcrDocument("直播间\n而广劑市 东方甄选\n关注", List.of(
                new OcrDocument.Line("而广劑市 东方甄选", 0.047, 0.060, 0.266, 0.080),
                new OcrDocument.Line("关注", 0.329, 0.070, 0.386, 0.083)));
        ContentSignals signals = ContentSignals.parse(document, "", config,
                config.platforms.get(0));
        assertEquals("东方甄选", signals.accountId);
        assertTrue(config.platforms.get(0).whitelistMatches(signals.accountId));
    }

    @Test public void promotionalTextBelowHeaderCannotReleaseWhitelist() {
        GuardConfig config = config(true);
        OcrDocument document = new OcrDocument("直播间\n东方甄选营养膳食旗舰店\n关注", List.of(
                new OcrDocument.Line("东方甄选营养膳食旗舰店",
                        0.369, 0.132, 0.652, 0.147),
                new OcrDocument.Line("关注", 0.329, 0.070, 0.386, 0.083)));
        assertEquals("", ContentSignals.parse(document, "", config,
                config.platforms.get(0)).accountId);
    }

    @Test public void oneCharacterOcrErrorCanRecoverConfiguredWhitelist() {
        GuardConfig config = config(true);
        for (String recognized : List.of("东方郵选", "時东甄选..", "f东甄选")) {
            OcrDocument document = new OcrDocument("直播间\n" + recognized + "\n关注",
                    List.of(new OcrDocument.Line(recognized,
                                    0.047, 0.060, 0.266, 0.080),
                            new OcrDocument.Line("关注",
                                    0.329, 0.070, 0.386, 0.083)));
            assertEquals(recognized, "东方甄选", ContentSignals.parse(document, "", config,
                    config.platforms.get(0)).accountId);
        }
    }

    @Test public void twoCharacterDifferenceCannotFuzzilyReleaseWhitelist() {
        GuardConfig config = config(true);
        for (String recognized : List.of("美微甄选", "东方的")) {
            OcrDocument document = new OcrDocument("直播间\n" + recognized + "\n关注",
                    List.of(new OcrDocument.Line(recognized,
                                    0.047, 0.060, 0.266, 0.080),
                            new OcrDocument.Line("关注",
                                    0.329, 0.070, 0.386, 0.083)));
            String account = ContentSignals.parse(document, "", config,
                    config.platforms.get(0)).accountId;
            assertTrue(recognized, !config.platforms.get(0).whitelistMatches(account));
        }
    }

    @Test public void liveHeaderWithoutFollowAnchorDoesNotReleaseWhitelist() {
        GuardConfig config = config(true);
        OcrDocument document = new OcrDocument("直播间\n东方甄选", List.of(
                new OcrDocument.Line("东方甄选", 0.128, 0.062, 0.244, 0.077)));
        assertEquals("", ContentSignals.parse(document, "", config,
                config.platforms.get(0)).accountId);
    }

    @Test public void shortVideoNeverUsesLiveHeaderNameWhitelist() {
        GuardConfig config = config(true);
        OcrDocument document = new OcrDocument("评论\n东方甄选\n关注", List.of(
                new OcrDocument.Line("东方甄选", 0.128, 0.062, 0.244, 0.077),
                new OcrDocument.Line("关注", 0.329, 0.070, 0.386, 0.083)));
        assertEquals("", ContentSignals.parse(document, "", config,
                config.platforms.get(0)).accountId);
    }
}
