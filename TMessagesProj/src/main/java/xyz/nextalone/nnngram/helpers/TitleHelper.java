package xyz.nextalone.nnngram.helpers;

/**
 * Decides what the main screen shows as the app title. Both renderers —
 * DialogsActivity's action bar and DialogStoriesCell's title slot, which otherwise
 * draws the hardcoded telegram_logo_2 — share this so they cannot disagree, and the
 * configured value is read on every call instead of being cached.
 */
public final class TitleHelper {

    private TitleHelper() {
    }

    /**
     * @return the title text, or {@code null} when the stock Telegram logo must be drawn
     */
    public static String headerText(String configured, String appLabel) {
        if (configured == null) {
            return null;
        }
        String text = configured.trim();
        return text.isEmpty() || text.equals(appLabel) ? null : text;
    }

    /** Stories text (stories count, "My story", uploading state) wins over the custom title. */
    public static CharSequence storiesHeaderText(CharSequence storiesTitle, String configured, String appLabel) {
        if (storiesTitle != null && storiesTitle.length() > 0) {
            return storiesTitle;
        }
        return headerText(configured, appLabel);
    }

    /**
     * Whether the freshly resolved title may be written into the action bar. The caller
     * keeps its cache up to date either way; only the write is skipped while a different
     * title (a folder tab's own title, an overlay) is on screen.
     *
     * @param initial createView rebuilt the action bar, whose title is still empty, so
     *                ownership cannot be read back from it
     */
    public static boolean shouldRender(boolean initial, CharSequence previouslyRendered, CharSequence displayedNow) {
        return initial || String.valueOf(previouslyRendered).equals(displayedNow);
    }
}
