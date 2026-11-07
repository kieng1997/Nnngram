package xyz.nextalone.nnngram.helpers;

/**
 * Behaviour regression for the header title of the main screen: what text the user
 * sees as the app title. Shared by DialogsActivity's action bar and
 * DialogStoriesCell's title slot, which otherwise draws the hardcoded
 * telegram_logo_2.
 *
 * <p>Plain JDK, no Android / JUnit. Run it with:</p>
 *
 * <pre>
 * javac -encoding UTF-8 -d TMessagesProj/build/tmp/title-helper/test-classes \
 *     TMessagesProj/src/main/java/xyz/nextalone/nnngram/helpers/TitleHelper.java \
 *     TMessagesProj/src/test/java/xyz/nextalone/nnngram/helpers/TitleHelperTest.java
 * java -cp TMessagesProj/build/tmp/title-helper/test-classes \
 *     xyz.nextalone.nnngram.helpers.TitleHelperTest
 * </pre>
 *
 * <p>Boundary: these are pure decision tests. The Android lifecycle wiring
 * (createView / onResume) is not covered by any automated test in this repo.</p>
 */
public final class TitleHelperTest {

    /** Label shown when nothing is customised, e.g. "NnngramX". */
    private static final String APP_LABEL = "NnngramX";

    public static void main(String[] args) {
        savedTitleIsShown();
        appLabelFallsBackToStockLogo();
        emptyOrNullFallsBackToStockLogo();
        resolutionHasNoCache();
        storiesTitleWinsOverCustomTitle();
        customTitleTakesTheStoriesLogoSlot();
        freshActionBarMustBeInitializedEvenWithStaleCache();
        renderIsSkippedWhileAnotherTitleOwnsTheBar();
        renderGoesThroughWhileThisScreenOwnsTheBar();
    }

    private static void savedTitleIsShown() {
        assertEquals("saved title is shown", "My Title", TitleHelper.headerText("My Title", APP_LABEL));
        assertEquals("surrounding spaces are ignored", "spaced", TitleHelper.headerText("  spaced  ", APP_LABEL));
    }

    /** Choosing "Default" in settings stores the app label and must restore the logo. */
    private static void appLabelFallsBackToStockLogo() {
        assertEquals("app label means default", null, TitleHelper.headerText(APP_LABEL, APP_LABEL));
    }

    private static void emptyOrNullFallsBackToStockLogo() {
        assertEquals("unset value shows logo", null, TitleHelper.headerText(null, APP_LABEL));
        assertEquals("empty value shows logo", null, TitleHelper.headerText("", APP_LABEL));
        assertEquals("blank value shows logo", null, TitleHelper.headerText("   ", APP_LABEL));
    }

    /** The decision reads the configured value it is given, it never keeps one. */
    private static void resolutionHasNoCache() {
        assertEquals("first value", "First", TitleHelper.headerText("First", APP_LABEL));
        assertEquals("second value wins", "Second", TitleHelper.headerText("Second", APP_LABEL));
    }

    /** Stories / My story / uploading text must keep winning over the custom title. */
    private static void storiesTitleWinsOverCustomTitle() {
        assertEquals("stories count is kept", "12 Stories",
                TitleHelper.storiesHeaderText("12 Stories", "My Title", APP_LABEL));
        assertEquals("uploading state is kept", "Uploading…",
                TitleHelper.storiesHeaderText("Uploading…", "My Title", APP_LABEL));
    }

    /** On the chat list the stories title is empty and the slot used to show the logo. */
    private static void customTitleTakesTheStoriesLogoSlot() {
        assertEquals("custom title takes the logo slot", "My Title",
                TitleHelper.storiesHeaderText(null, "My Title", APP_LABEL));
        assertEquals("empty stories title falls back to custom title", "My Title",
                TitleHelper.storiesHeaderText("", "My Title", APP_LABEL));
        assertEquals("logo slot stays the logo without a custom title", null,
                TitleHelper.storiesHeaderText(null, APP_LABEL, APP_LABEL));
        assertEquals("stories title survives an unset custom title", "My story",
                TitleHelper.storiesHeaderText("My story", "", APP_LABEL));
    }

    /**
     * createView rebuilds the action bar, so its title is empty while the cache still
     * holds what a previous build rendered. The stale cache must not block the first
     * render or the title stays blank.
     */
    private static void freshActionBarMustBeInitializedEvenWithStaleCache() {
        assertTrue("fresh action bar is initialized",
                TitleHelper.shouldRender(true, "Old Title", null));
    }

    /** A folder tab owns the bar while selected; its title must not be overwritten. */
    private static void renderIsSkippedWhileAnotherTitleOwnsTheBar() {
        assertFalse("folder tab title is kept",
                TitleHelper.shouldRender(false, "Old Title", "Some Folder"));
    }

    /** Returning to the screen has to repaint with the value saved meanwhile. */
    private static void renderGoesThroughWhileThisScreenOwnsTheBar() {
        assertTrue("title still ours is repainted",
                TitleHelper.shouldRender(false, "Old Title", "Old Title"));
        assertTrue("logo slot still ours is repainted",
                TitleHelper.shouldRender(false, "T", "T"));
    }

    private static void assertEquals(String label, Object expected, Object actual) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError(label + ": expected " + expected + ", got " + actual);
        }
    }

    private static void assertTrue(String label, boolean actual) {
        if (!actual) {
            throw new AssertionError(label + ": expected true");
        }
    }

    private static void assertFalse(String label, boolean actual) {
        if (actual) {
            throw new AssertionError(label + ": expected false");
        }
    }
}
