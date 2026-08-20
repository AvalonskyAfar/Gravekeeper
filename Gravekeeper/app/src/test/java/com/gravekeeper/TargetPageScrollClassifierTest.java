package com.gravekeeper;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class TargetPageScrollClassifierTest {
    private final TargetPageScrollClassifier classifier =
            new TargetPageScrollClassifier(0.70, 0.80, 0.75, true);
    private final WindowLayoutClassifier.Bounds target =
            new WindowLayoutClassifier.Bounds(0, 100, 1080, 2300);

    @Test public void fullPageVerticalScrollIsAccepted() {
        assertTrue(classifier.isMainPageScroll(evidence(
                new WindowLayoutClassifier.Bounds(0, 100, 1080, 2300),
                0, 900, -1, -1)));
    }

    @Test public void fullPageIndexedScrollWithoutDeltasIsAccepted() {
        assertTrue(classifier.isMainPageScroll(evidence(
                new WindowLayoutClassifier.Bounds(0, 100, 1080, 2300),
                0, 0, 3, 4)));
    }

    @Test public void oemEventWithoutDirectionIsAcceptedForFullPageSource() {
        assertTrue(classifier.isMainPageScroll(evidence(
                new WindowLayoutClassifier.Bounds(0, 100, 1080, 2300),
                0, 0, -1, -1)));
    }

    @Test public void liveCommentListIsRejected() {
        assertFalse(classifier.isMainPageScroll(evidence(
                new WindowLayoutClassifier.Bounds(20, 1500, 1060, 2250),
                0, 280, 10, 15)));
    }

    @Test public void productShelfIsRejectedWhenItDoesNotCoverMainPage() {
        assertFalse(classifier.isMainPageScroll(evidence(
                new WindowLayoutClassifier.Bounds(0, 900, 1080, 2300),
                0, 500, 2, 5)));
    }

    @Test public void horizontalCarouselIsRejected() {
        assertFalse(classifier.isMainPageScroll(evidence(
                new WindowLayoutClassifier.Bounds(0, 100, 1080, 2300),
                900, 40, 0, 1)));
    }

    @Test public void sourceOutsideSplitWindowIsRejected() {
        WindowLayoutClassifier.Bounds splitTarget =
                new WindowLayoutClassifier.Bounds(0, 1200, 1080, 2400);
        TargetPageScrollClassifier.Evidence value =
                new TargetPageScrollClassifier.Evidence(splitTarget,
                        new WindowLayoutClassifier.Bounds(0, 0, 1080, 1100),
                        0, 700, 0, 1);

        assertFalse(classifier.isMainPageScroll(value));
    }

    @Test public void missingSourceIsRejected() {
        TargetPageScrollClassifier.Evidence value =
                new TargetPageScrollClassifier.Evidence(target, null,
                        0, 800, 0, 1);

        assertFalse(classifier.isMainPageScroll(value));
    }

    @Test public void missingSourceFallbackRequiresVerticalPagingSignal() {
        assertTrue(classifier.isStrongVerticalPagingSignal(0, 800, -1, -1, false));
        assertTrue(classifier.isStrongVerticalPagingSignal(0, 0, 7, 8, false));
        assertFalse(classifier.isStrongVerticalPagingSignal(900, 40, -1, -1, false));
        assertFalse(classifier.isStrongVerticalPagingSignal(0, 0, -1, -1, false));
    }

    @Test public void missingSourceFallbackCanBeConfiguredForOemEventsWithoutMetadata() {
        assertTrue(classifier.isStrongVerticalPagingSignal(0, 0, -1, -1, true));
    }

    private TargetPageScrollClassifier.Evidence evidence(
            WindowLayoutClassifier.Bounds source, int deltaX, int deltaY,
            int fromIndex, int toIndex) {
        return new TargetPageScrollClassifier.Evidence(
                target, source, deltaX, deltaY, fromIndex, toIndex);
    }
}
