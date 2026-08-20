package com.gravekeeper;

/** Single source of truth for user-visible product naming. */
final class BrandIdentity {
    static final String DISPLAY_NAME = "守目人";
    static final String ENGLISH_NAME = "Gravekeeper";
    static final String FULL_NAME = DISPLAY_NAME + "（" + ENGLISH_NAME + "）";
    static final String DIAGNOSTIC_FILE_NAME = ENGLISH_NAME + "-diagnostic.txt";

    private BrandIdentity() {}
}
