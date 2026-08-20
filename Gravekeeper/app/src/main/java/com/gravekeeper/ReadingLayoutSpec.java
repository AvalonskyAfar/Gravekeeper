package com.gravekeeper;

/** Shared dimensions for long-form copy and image wells. */
final class ReadingLayoutSpec {
    static final float BODY_TEXT_SP = 15.5f;
    static final float BODY_LINE_MULTIPLIER = 1.24f;
    static final float BODY_LINE_EXTRA_DP = 3f;

    static final int PLAIN_TEXT_EXTRA_HORIZONTAL_DP = 10;
    static final int PLAIN_TEXT_EXTRA_VERTICAL_DP = 8;

    static final float FIRST_LAUNCH_TITLE_SP = 20.5f;
    static final float FIRST_LAUNCH_SUBTITLE_SP = 14f;
    static final float FIRST_LAUNCH_ACTION_SP = 16.5f;
    static final int FIRST_LAUNCH_HEADING_HEIGHT_DP = 86;
    static final int FIRST_LAUNCH_BODY_HORIZONTAL_DP = 22;
    static final int FIRST_LAUNCH_BODY_VERTICAL_DP = 18;
    static final int FIRST_LAUNCH_MEDIA_EDGE_INSET_DP = 1;
    static final float FIRST_LAUNCH_MEDIA_RADIUS_DP = 7f;
    static final int FIRST_LAUNCH_MEDIA_MIN_HEIGHT_DP = 220;

    // First-launch pages without artwork use an article hierarchy rather than the
    // generic explanatory-copy treatment used by developer and information pages.
    static final float FIRST_LAUNCH_ARTICLE_LEAD_SP = 16.5f;
    static final float FIRST_LAUNCH_ARTICLE_GROUP_SP = 14f;
    static final float FIRST_LAUNCH_ARTICLE_ITEM_TITLE_SP = 17f;
    static final float FIRST_LAUNCH_ARTICLE_ITEM_BODY_SP = 14.5f;
    static final float FIRST_LAUNCH_ARTICLE_CLOSING_SP = 15f;

    // Tutorial child pages have their own editorial hierarchy. Keep these values
    // independent from PLAIN_TEXT so developer-mode copy remains unchanged.
    static final float TUTORIAL_PAGE_TITLE_SP = 20f;
    static final float TUTORIAL_PAGE_SUBTITLE_SP = 14f;
    static final float TUTORIAL_STATUS_SP = 14.5f;
    static final float TUTORIAL_EYEBROW_SP = 13.5f;
    static final float TUTORIAL_GROUP_TITLE_SP = 18f;
    static final float TUTORIAL_LEAD_SP = 16f;
    static final float TUTORIAL_SECTION_TITLE_SP = 15.5f;
    static final float TUTORIAL_SECTION_BODY_SP = 14.5f;
    static final int TUTORIAL_SECTION_HORIZONTAL_DP = 12;
    static final int TUTORIAL_SECTION_VERTICAL_DP = 14;

    static final int MEDIA_WELL_INSET_DP = 4;
    static final float MEDIA_WELL_IMAGE_RADIUS_DP = 4f;
    static final int TUTORIAL_MEDIA_HEIGHT_DP = 152;

    private ReadingLayoutSpec() {}
}
