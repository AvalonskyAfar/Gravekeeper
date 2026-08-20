package com.gravekeeper;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.HashSet;
import java.util.Set;

import org.junit.Test;

public final class TutorialPageCatalogTest {
    @Test public void keepsThreeCardsAndTheirChildPagesInOneCatalog() {
        assertEquals(3, TutorialPageCatalog.PAGE_COUNT);
        assertEquals("无障碍权限", TutorialPageCatalog.page(0).rootLabel);
        assertEquals("隐藏桌面图标", TutorialPageCatalog.page(1).rootLabel);
        assertEquals("隐藏 App", TutorialPageCatalog.page(2).rootLabel);
        for (int index = 0; index < TutorialPageCatalog.PAGE_COUNT; index++) {
            TutorialPageCatalog.PageSpec page = TutorialPageCatalog.page(index);
            assertNotEquals(0, page.illustrationRes);
            assertFalse(page.childTitle.contains("占位"));
            assertFalse(page.body.contains("占位"));
            assertEquals(page, TutorialPageCatalog.find(page.rootLabel));
        }
        assertNull(TutorialPageCatalog.find("不存在的教程"));
    }

    @Test public void usesThreeDistinctIllustrations() {
        Set<Integer> resources = new HashSet<>();
        for (int index = 0; index < TutorialPageCatalog.PAGE_COUNT; index++) {
            resources.add(TutorialPageCatalog.page(index).illustrationRes);
        }
        assertEquals(3, resources.size());
    }

    @Test public void keepsRealAccessibilityActionOnOnlyItsOwnPage() {
        assertTrue(TutorialPageCatalog.page(0).accessibilityAction);
        assertFalse(TutorialPageCatalog.page(1).accessibilityAction);
        assertFalse(TutorialPageCatalog.page(2).accessibilityAction);
        assertNotNull(TutorialPageCatalog.find("无障碍权限"));
    }

    @Test public void retainsApprovedTutorialContent() {
        assertTrue(TutorialPageCatalog.page(0).body.contains("处理结果不会上传至服务器"));
        assertTrue(TutorialPageCatalog.page(1).body.contains("小米／REDMI"));
        assertTrue(TutorialPageCatalog.page(1).body.contains("OPPO 及三星"));
        assertTrue(TutorialPageCatalog.page(2).body.contains("自启动管理"));
        assertTrue(TutorialPageCatalog.page(2).body.contains("三星（One UI）"));
    }

    @Test public void everyChildPageMatchesTheApprovedMarkdownStructure() {
        for (int index = 0; index < TutorialPageCatalog.PAGE_COUNT; index++) {
            TutorialPageCatalog.PageSpec page = TutorialPageCatalog.page(index);
            assertFalse(page.childTitle.isBlank());
            assertTrue(page.subtitle.isBlank());
            assertFalse(page.lead.isBlank());
            assertTrue(page.groupTitle.isBlank());
            for (TutorialPageCatalog.SectionSpec section : page.sections) {
                assertFalse(section.heading.isBlank());
                assertFalse(section.body.isBlank());
                assertTrue(page.body.contains(section.heading));
                assertTrue(page.body.contains(section.body));
            }
        }
        assertTrue(TutorialPageCatalog.page(0).groupDetail.isBlank());
        assertEquals(0, TutorialPageCatalog.page(0).sections.length);
        assertTrue(TutorialPageCatalog.page(1).groupDetail.isBlank());
        assertEquals(5, TutorialPageCatalog.page(1).sections.length);
        assertFalse(TutorialPageCatalog.page(2).groupDetail.isBlank());
        assertEquals(5, TutorialPageCatalog.page(2).sections.length);
    }

    @Test public void usesNotesAndClosingsForDifferentSemanticRoles() {
        TutorialPageCatalog.PageSpec accessibility = TutorialPageCatalog.page(0);
        TutorialPageCatalog.PageSpec launcher = TutorialPageCatalog.page(1);
        TutorialPageCatalog.PageSpec recentTask = TutorialPageCatalog.page(2);
        assertNull(accessibility.noteTitle);
        assertNull(accessibility.noteBody);
        assertEquals("注：", launcher.noteTitle);
        assertTrue(launcher.noteBody.contains("深度隐藏空间"));
        assertNull(recentTask.noteTitle);
        assertTrue(recentTask.closingTitle.isBlank());
        assertTrue(recentTask.closingBody.contains("后台平稳运行"));
    }
}
