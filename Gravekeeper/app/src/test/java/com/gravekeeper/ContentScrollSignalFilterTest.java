package com.gravekeeper;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class ContentScrollSignalFilterTest {
    @Test public void automaticScrollWithoutTouchIsRejected() {
        ContentScrollSignalFilter filter = filter();

        assertFalse(filter.accept(5000L));
    }

    @Test public void scrollDuringTargetTouchWithPackageLessEventIsAccepted() {
        ContentScrollSignalFilter filter = filter();
        filter.onTouchStart(5000L, true);

        assertTrue(filter.accept(5000L));
    }

    @Test public void momentumScrollShortlyAfterTouchIsAccepted() {
        ContentScrollSignalFilter filter = filter();
        filter.onTouchStart(5000L, true);
        filter.onTouchEnd(5100L, true);

        assertTrue(filter.accept(5500L));
    }

    @Test public void scrollAfterTouchWindowIsRejected() {
        ContentScrollSignalFilter filter = filter();
        filter.onTouchStart(5000L, true);
        filter.onTouchEnd(5000L, true);

        assertFalse(filter.accept(5801L));
    }

    @Test public void repeatedEventsFromOneGestureAreDebounced() {
        ContentScrollSignalFilter filter = filter();
        filter.onTouchStart(5000L, true);

        assertTrue(filter.accept(5000L));
        assertFalse(filter.accept(5500L));
        assertTrue(filter.accept(6000L));
    }

    @Test public void touchRequirementCanBeCustomized() {
        ContentScrollSignalFilter filter = new ContentScrollSignalFilter(
                true, false, 0L, 0L);

        assertTrue(filter.accept(5000L));
    }

    @Test public void disabledFilterRejectsEveryScroll() {
        ContentScrollSignalFilter filter = new ContentScrollSignalFilter(
                false, false, 0L, 0L);

        assertFalse(filter.accept(5000L));
    }

    @Test public void contextResetClearsDebounceState() {
        ContentScrollSignalFilter filter = filter();
        filter.onTouchStart(5000L, true);
        assertTrue(filter.accept(5000L));

        filter.reset();

        assertFalse(filter.accept(5100L));
        filter.onTouchStart(5100L, true);
        assertTrue(filter.accept(5100L));
    }

    @Test public void touchOutsideTargetContextCannotAuthorizeTargetScroll() {
        ContentScrollSignalFilter filter = filter();
        filter.onTouchStart(5000L, false);
        filter.onTouchEnd(5100L, false);

        assertFalse(filter.accept(5200L));
    }

    @Test public void activeTouchRemainsValidUntilItEnds() {
        ContentScrollSignalFilter filter = filter();
        filter.onTouchStart(5000L, true);

        assertTrue(filter.accept(7000L));
    }

    @Test public void mainPageEvidenceCanReplaceUnavailableTouchEvents() {
        ContentScrollSignalFilter filter = strictFilter();

        assertTrue(filter.accept(5000L, true));
    }

    @Test public void localScrollIsRejectedEvenWithTouchWhenPageEvidenceRequired() {
        ContentScrollSignalFilter filter = strictFilter();
        filter.onTouchStart(5000L, true);

        assertFalse(filter.accept(5100L, false));
    }

    @Test public void mainPageFallbackCanBeDisabledByConfiguration() {
        ContentScrollSignalFilter filter = new ContentScrollSignalFilter(
                true, true, 800L, 900L, true, false);

        assertFalse(filter.accept(5000L, true));
    }

    private static ContentScrollSignalFilter filter() {
        return new ContentScrollSignalFilter(true, true, 800L, 900L);
    }

    private static ContentScrollSignalFilter strictFilter() {
        return new ContentScrollSignalFilter(
                true, true, 800L, 900L, true, true);
    }
}
