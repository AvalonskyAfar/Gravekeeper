package com.gravekeeper;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public final class MainActivityNavigationTest {
    @Test public void everyNestedPageReturnsToItsImmediateParent() {
        assertEquals(MainActivity.Page.MORE,
                MainActivity.backTarget(MainActivity.Page.MORE_DETAIL));
        assertEquals(MainActivity.Page.TUTORIAL,
                MainActivity.backTarget(MainActivity.Page.TUTORIAL_CHILD));
        assertEquals(MainActivity.Page.ADVANCED,
                MainActivity.backTarget(MainActivity.Page.PERFORMANCE));
        assertEquals(MainActivity.Page.SETTINGS,
                MainActivity.backTarget(MainActivity.Page.ADVANCED));
        assertEquals(MainActivity.Page.SETTINGS,
                MainActivity.backTarget(MainActivity.Page.WHITELIST));
    }

    @Test public void nestedPagesNeverSkipStraightToMain() {
        assertNotEquals(MainActivity.Page.MAIN,
                MainActivity.backTarget(MainActivity.Page.MORE_DETAIL));
        assertNotEquals(MainActivity.Page.MAIN,
                MainActivity.backTarget(MainActivity.Page.TUTORIAL_CHILD));
        assertNotEquals(MainActivity.Page.MAIN,
                MainActivity.backTarget(MainActivity.Page.PERFORMANCE));
        assertNotEquals(MainActivity.Page.MAIN,
                MainActivity.backTarget(MainActivity.Page.ADVANCED));
        assertNotEquals(MainActivity.Page.MAIN,
                MainActivity.backTarget(MainActivity.Page.WHITELIST));
    }

    @Test public void sectionRootsReturnToMainAndMainHasNoParent() {
        assertEquals(MainActivity.Page.MAIN,
                MainActivity.backTarget(MainActivity.Page.MORE));
        assertEquals(MainActivity.Page.MAIN,
                MainActivity.backTarget(MainActivity.Page.TUTORIAL));
        assertEquals(MainActivity.Page.MAIN,
                MainActivity.backTarget(MainActivity.Page.SETTINGS));
        assertNull(MainActivity.backTarget(MainActivity.Page.MAIN));
    }
}
