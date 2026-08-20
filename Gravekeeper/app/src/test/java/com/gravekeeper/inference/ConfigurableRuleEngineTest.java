package com.gravekeeper.inference;

import static org.junit.Assert.assertEquals;

import com.gravekeeper.config.GuardConfig;

import org.junit.Test;

import java.util.Collections;
import java.util.List;
import java.util.Set;

public final class ConfigurableRuleEngineTest {
    @Test public void fusionRuleKeywordsComeFromRuntimeConfig() throws Exception {
        GuardConfig.MediaPolicy media = new GuardConfig.MediaPolicy(
                GuardConfig.Action.IGNORE, 0.5, 0.0);
        GuardConfig.Platform platform = new GuardConfig.Platform(
                "test", "测试", true, Set.of("pkg"), Set.of(),
                0.0, media, media, media);
        GuardConfig config = new GuardConfig(
                0.0, List.of("全球购"), 0.0, List.of("科普"),
                List.of("直播间"), List.of("评论"), List.of("账号ID"),
                Collections.singletonList(platform));
        RuleEngine engine = new RuleEngine(config);

        assertEquals(1.0, engine.evaluate("保健品", "", true).ordered()[0], 0.0);
        assertEquals(0.0, engine.evaluate("普通内容", "", true).ordered()[0], 0.0);
    }
}
