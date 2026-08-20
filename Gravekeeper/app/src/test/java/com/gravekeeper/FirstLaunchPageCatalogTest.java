package com.gravekeeper;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class FirstLaunchPageCatalogTest {
    @Test public void keepsEightPresentationPagesBeforeExistingDisclosure() {
        assertEquals(8, FirstLaunchPageCatalog.INTRO_PAGE_COUNT);
        assertEquals(8, FirstLaunchPageCatalog.DISCLOSURE_PAGE_INDEX);
        assertEquals(9, FirstLaunchPageCatalog.PAGE_COUNT);
        assertTrue(FirstLaunchPageCatalog.isDisclosure(8));
        assertFalse(FirstLaunchPageCatalog.isDisclosure(7));
    }

    @Test public void mapsIllustrationsOnlyToPagesTwoThroughSeven() {
        assertFalse(FirstLaunchPageCatalog.introPage(0).hasIllustration());
        for (int index = 1; index <= 6; index++) {
            assertTrue("page " + (index + 1),
                    FirstLaunchPageCatalog.introPage(index).hasIllustration());
            assertNotEquals(0, FirstLaunchPageCatalog.introPage(index).illustrationRes);
        }
        assertFalse(FirstLaunchPageCatalog.introPage(7).hasIllustration());
    }

    @Test public void noImagePagesKeepTheirOriginalCompleteParagraphs() {
        FirstLaunchPageCatalog.PageSpec opening = FirstLaunchPageCatalog.introPage(0);
        FirstLaunchPageCatalog.PageSpec limitations = FirstLaunchPageCatalog.introPage(7);
        assertTrue(opening.body.startsWith("内置专门训练的本地视觉与文本深度学习模型"));
        assertTrue(opening.body.contains("精准识别夸大功效"));
        assertTrue(opening.body.endsWith("用前沿端侧 AI 为你守住眼前清朗。"));
        assertTrue(limitations.body.startsWith("作为基于端侧 AI 模型的辅助工具"));
        assertTrue(limitations.body.contains("白名单识别偶有失效"));
        assertTrue(limitations.body.endsWith("改进防护体验。"));
    }

    @Test public void usesApprovedTitlesWithoutPrototypeLabels() {
        assertEquals("守目人", FirstLaunchPageCatalog.introPage(0).title);
        assertEquals("端侧 AI 驱动的短视频健康防线",
                FirstLaunchPageCatalog.introPage(0).subtitle);
        for (int index = 0; index < FirstLaunchPageCatalog.INTRO_PAGE_COUNT; index++) {
            FirstLaunchPageCatalog.PageSpec page = FirstLaunchPageCatalog.introPage(index);
            assertFalse(page.title.contains("介绍页"));
            assertFalse(page.title.contains("占位"));
            assertFalse(page.body.contains("占位"));
            if (index > 0) assertNull(page.subtitle);
        }
    }
}
