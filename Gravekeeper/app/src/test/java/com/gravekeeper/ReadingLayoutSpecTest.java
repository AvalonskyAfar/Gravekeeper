package com.gravekeeper;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class ReadingLayoutSpecTest {
    @Test public void bodyCopyUsesReadableTypeAndLeading() {
        assertTrue(ReadingLayoutSpec.BODY_TEXT_SP >= 15f);
        assertTrue(ReadingLayoutSpec.BODY_LINE_MULTIPLIER >= 1.2f);
        assertTrue(ReadingLayoutSpec.BODY_LINE_EXTRA_DP >= 2f);
    }

    @Test public void mediaIsInsetInsideItsFrameAndKeepsRoundedCorners() {
        assertTrue(ReadingLayoutSpec.MEDIA_WELL_INSET_DP >= 3);
        assertTrue(ReadingLayoutSpec.MEDIA_WELL_IMAGE_RADIUS_DP > 0f);
        assertTrue(ReadingLayoutSpec.FIRST_LAUNCH_MEDIA_RADIUS_DP > 0f);
        assertTrue(ReadingLayoutSpec.FIRST_LAUNCH_MEDIA_MIN_HEIGHT_DP >= 200);
        assertTrue(ReadingLayoutSpec.TUTORIAL_MEDIA_HEIGHT_DP == 152);
    }

    @Test public void firstLaunchHierarchyIsLargerThanLegacySizes() {
        assertTrue(ReadingLayoutSpec.FIRST_LAUNCH_TITLE_SP > 17f);
        assertTrue(ReadingLayoutSpec.FIRST_LAUNCH_SUBTITLE_SP > 12.5f);
        assertTrue(ReadingLayoutSpec.FIRST_LAUNCH_ACTION_SP > 15f);
    }

    @Test public void tutorialUsesTitleSubtitleLeadAndSectionLevels() {
        assertTrue(ReadingLayoutSpec.TUTORIAL_PAGE_TITLE_SP
                > ReadingLayoutSpec.TUTORIAL_PAGE_SUBTITLE_SP);
        assertTrue(ReadingLayoutSpec.TUTORIAL_LEAD_SP
                > ReadingLayoutSpec.TUTORIAL_SECTION_BODY_SP);
        assertTrue(ReadingLayoutSpec.TUTORIAL_SECTION_TITLE_SP
                > ReadingLayoutSpec.TUTORIAL_SECTION_BODY_SP);
        assertTrue(ReadingLayoutSpec.TUTORIAL_SECTION_BODY_SP >= 14.5f);
        assertTrue(ReadingLayoutSpec.TUTORIAL_SECTION_HORIZONTAL_DP >= 10);
        assertTrue(ReadingLayoutSpec.TUTORIAL_SECTION_VERTICAL_DP >= 12);
    }
}
