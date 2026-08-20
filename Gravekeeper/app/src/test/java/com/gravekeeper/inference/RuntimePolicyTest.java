package com.gravekeeper.inference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.gravekeeper.config.GuardConfig;

import org.junit.Test;

import java.util.Collections;
import java.util.List;
import java.util.Set;

public final class RuntimePolicyTest {
    private static GuardConfig config() {
        GuardConfig.MediaPolicy shortVideo = new GuardConfig.MediaPolicy(
                GuardConfig.Action.IGNORE, 0.8, 0.0);
        GuardConfig.MediaPolicy live = new GuardConfig.MediaPolicy(
                GuardConfig.Action.SWIPE, 0.5, 0.0);
        GuardConfig.MediaPolicy unknown = new GuardConfig.MediaPolicy(
                GuardConfig.Action.NOTIFY, 0.6, 0.0);
        GuardConfig.Platform platform = new GuardConfig.Platform(
                "douyin", "抖音", true, Set.of("pkg"), Set.of("trusted_1"),
                0.0, shortVideo, live, unknown);
        return new GuardConfig(0.22, List.of("全球购"), -0.18, List.of("科普"),
                List.of("直播间"), List.of("评论"), List.of("抖音号"),
                Collections.singletonList(platform));
    }

    @Test public void globalPurchaseRaisesRiskButNegativeContextCanOffsetIt() {
        GuardConfig config = config();
        RiskPolicyEngine engine = new RiskPolicyEngine(config);
        PolicyDecision global = engine.evaluate(0.45, "全球购 保健品 立即购买", config.platforms.get(0));
        PolicyDecision education = engine.evaluate(0.45, "全球购 保健品 科普 不要购买", config.platforms.get(0));
        assertTrue(global.adjustedScore > 0.60);
        assertTrue(global.positive);
        assertTrue(education.adjustedScore < global.adjustedScore);
    }

    @Test public void mediaPoliciesRemainIndependent() {
        GuardConfig config = config();
        RiskPolicyEngine engine = new RiskPolicyEngine(config);
        PolicyDecision shortVideo = engine.evaluate(0.95, "评论 保健品 立即购买", config.platforms.get(0));
        PolicyDecision live = engine.evaluate(0.95, "直播间 保健品 立即购买", config.platforms.get(0));
        assertFalse(shortVideo.positive);
        assertEquals(GuardConfig.Action.SWIPE, live.action);
        assertTrue(live.positive);
    }

    @Test public void accountIdWhitelistUsesOnlyParsedId() {
        GuardConfig config = config();
        RiskPolicyEngine engine = new RiskPolicyEngine(config);
        PolicyDecision trusted = engine.evaluate(0.99, "抖音号: trusted_1\n直播间 保健品", config.platforms.get(0));
        PolicyDecision renamed = engine.evaluate(0.99, "账号名称: trusted_1\n直播间 保健品", config.platforms.get(0));
        assertTrue(trusted.whitelisted);
        assertFalse(trusted.positive);
        assertFalse(renamed.whitelisted);
        assertTrue(renamed.positive);
    }

    @Test public void accessibilityTextCanIdentifyMediaButCannotWhitelistAccount() {
        GuardConfig config = config();
        RiskPolicyEngine engine = new RiskPolicyEngine(config);
        PolicyDecision decision = engine.evaluate(
                0.99, "", "抖音号: trusted_1\n直播间", config.platforms.get(0));
        assertEquals(GuardConfig.MediaKind.LIVE, decision.mediaKind);
        assertFalse(decision.whitelisted);
        assertTrue(decision.positive);
    }

    @Test public void unknownMediaCannotSwipeWhenSafetyGateIsEnabled() {
        GuardConfig config = config();
        assertFalse(SwipeEligibility.canAutomaticallySwipe(
                config, GuardConfig.MediaKind.UNKNOWN));
        assertTrue(SwipeEligibility.canAutomaticallySwipe(
                config, GuardConfig.MediaKind.SHORT_VIDEO));
        assertTrue(SwipeEligibility.canAutomaticallySwipe(
                config, GuardConfig.MediaKind.LIVE));
    }

    @Test public void liveWhitelistPrefixCoversConfiguredChildAccountName() {
        GuardConfig.MediaPolicy media = new GuardConfig.MediaPolicy(
                GuardConfig.Action.SWIPE, 0.5, 0.0);
        GuardConfig.Platform platform = new GuardConfig.Platform(
                "douyin", "抖音", true, Set.of("pkg"), Set.of("东方甄选"),
                0.0, media, media, media, null,
                GuardConfig.WhitelistMatchMode.PREFIX);
        GuardConfig config = new GuardConfig(0.0, List.of("全球购"), 0.0,
                List.of("科普"), List.of("直播间"), List.of("评论"),
                List.of("抖音号"), Collections.singletonList(platform));
        PolicyDecision decision = new RiskPolicyEngine(config).evaluate(
                0.99, "抖音号: 东方甄选自营产品\n直播间 保健品", platform);
        assertTrue(decision.whitelisted);
        assertFalse(decision.positive);
    }
}
