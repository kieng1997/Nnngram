package xyz.nextalone.nnngram.utils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Merge-regression checks for upstream fixes ported from main into dev.
 *
 * <p>These are <b>source-structure checks, not runnable Android tests</b>: they prove the
 * ported resource/guard is still present in the working tree so a later upstream merge cannot
 * silently drop it. Behaviour is covered by the JVM tests under
 * {@code TMessagesProj/src/test/java/xyz/nextalone/nnngram/ui} and
 * {@code .../helpers}, which execute real production method bodies.</p>
 *
 * <pre>
 * javac -d TMessagesProj/build/tmp/main-sync-ports/test-classes \
 *     TMessagesProj/src/test/java/xyz/nextalone/nnngram/utils/MainSyncPortsTest.java
 * java -cp TMessagesProj/build/tmp/main-sync-ports/test-classes \
 *     xyz.nextalone.nnngram.utils.MainSyncPortsTest .
 * </pre>
 */
public final class MainSyncPortsTest {

    private static final String JAVA = "TMessagesProj/src/main/java/";
    private static final String RES = "TMessagesProj/src/main/res/";

    public static void main(String[] args) throws IOException {
        Path root = Paths.get(args.length > 0 ? args[0] : ".").toAbsolutePath().normalize();
        assertDialogAnimationGuard(root);
        assertSpeedIconCallback(root);
        assertTranslationResources(root);
        assertSettingsCategoryRows(root);
        assertBanUnbanStrings(root);
        assertSaveSelectionWiring(root);
        assertReplyAndViewerPorts(root);
        System.out.println("OK: ported main fixes are still in place");
    }

    /**
     * 65b269dcc adapted to this fork: both the add and the remove selection paths must use the
     * same saveability decision, and that decision must receive the fork switch.
     */
    private static void assertSaveSelectionWiring(Path root) throws IOException {
        String chat = read(root, JAVA + "org/telegram/ui/ChatActivity.java");
        assertPresent(chat, "save decision delegates to the fork helper",
                "MessageSaveEligibility.canSave(Config.ignoreChatStrict, isPeerNoForwards(),");
        int musicSites = countOccurrences(chat, "messageObject.isMusic() && canSaveSelectedMessage(messageObject)");
        int documentSites = countOccurrences(chat, "messageObject.isDocument() && canSaveSelectedMessage(messageObject)");
        if (musicSites != 2 || documentSites != 2) {
            throw new AssertionError("save counters must use the shared decision on both add and remove paths,"
                    + " found music=" + musicSites + " document=" + documentSites);
        }
        assertMissing(chat, "music no longer saved unconditionally",
                "messageObject.isMusic()) {\n                        canSaveMusicCount++");
        System.out.println("[PASS] protected messages cannot be saved from the selection bar");
    }

    /** fed767bba / c8d94dcaa / 5441fe422 / 35a371307: dev-compatible ports stay in place. */
    private static void assertReplyAndViewerPorts(Path root) throws IOException {
        String chat = read(root, JAVA + "org/telegram/ui/ChatActivity.java");
        int guard = chat.indexOf("case OPTION_REPLY: {");
        int crossChat = chat.indexOf("ChatObject.isNotInChat(currentChat)", guard);
        int noforwards = chat.indexOf("selectedObject.messageOwner.noforwards", guard);
        if (guard < 0 || crossChat < 0 || noforwards < 0 || noforwards < crossChat) {
            throw new AssertionError("the noforwards reply guard must live inside the reply-in-another-chat"
                    + " branch only, so in-chat replies to protected messages keep working");
        }
        System.out.println("[PASS] protected messages reply in-chat and stay blocked across chats");

        String viewer = read(root, JAVA + "org/telegram/ui/PhotoViewer.java");
        assertPresent(viewer, "live photo edits keep full resolution",
                "return entry.isLivePhoto() ? AndroidUtilities.getPhotoSize() : thumbSize;");
        assertPresent(viewer, "fullscreen video uses the visible navigation bar inset",
                "final int bottom = newInsetsCompat.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout()).bottom;");

        String popup = read(root, JAVA + "org/telegram/ui/PopupNotificationActivity.java");
        // 35a371307: the close button must be tinted with the action bar icon color. Upstream
        // 13.2.x routes the same call through ThemeBind, so accept either spelling.
        if (!popup.contains("ThemeBind.actionBarItemsColor(actionBar, Theme.key_actionBarDefaultIcon, false);")
                && !popup.contains("actionBar.setItemsColor(Theme.getColor(Theme.key_actionBarDefaultIcon), false);")) {
            throw new AssertionError("popup close button is tinted: missing the action bar icon tint");
        }
        System.out.println("[PASS] viewer resolution/inset fixes and popup close tint are in place");
    }

    /** 3132eec80: unresolved window animations must not reach AnimationUtils.loadAnimation(). */
    private static void assertDialogAnimationGuard(Path root) throws IOException {
        String source = read(root, JAVA + "org/telegram/ui/ActionBar/AlertDialogDecor.java");
        assertPresent(source, "adaptive dialog animation guard", "private Animation loadAnimation(int res)");
        assertPresent(source, "adaptive dialog catches missing animation resource",
                "catch (Resources.NotFoundException e)");
        assertPresent(source, "adaptive dialog checks unset animation ids",
                "if (res == 0 || res == -1)");
        assertMissing(source, "enter animation no longer loaded unchecked",
                "contentView.startAnimation(AnimationUtils.loadAnimation(getContext(), resEnterAnimation))");
        assertMissing(source, "exit animation no longer loaded unchecked",
                "Animation animation = AnimationUtils.loadAnimation(getContext(), resExitAnimation)");
        System.out.println("[PASS] adaptive dialogs skip a missing window animation");
    }

    /**
     * fe8d38266: the text drawable's callback is held weakly by Drawable.setCallback(), so the
     * owner must be the strongly-referenced drawable itself, not an anonymous object stored only
     * in a field R8 may remove.
     */
    private static void assertSpeedIconCallback(Path root) throws IOException {
        String source = read(root, JAVA + "org/telegram/ui/Components/SpeedIconDrawable.java");
        assertPresent(source, "speed icon is its own drawable callback",
                "public class SpeedIconDrawable extends Drawable implements Drawable.Callback");
        assertPresent(source, "speed icon text drawable uses the drawable as callback",
                "textDrawable.setCallback(this);");
        assertMissing(source, "speed icon no longer passes a collectable callback object",
                "textDrawable.setCallback(callback);");
        System.out.println("[PASS] speed icon text drawable keeps a strongly reachable callback");
    }

    /**
     * 73c4b9fe6: texts that used to be hard-coded English now come from string resources, and
     * every key exists exactly once (no duplicated resource, no untranslated literal left).
     */
    private static void assertTranslationResources(Path root) throws IOException {
        String strings = read(root, RES + "values/strings.xml");
        Map<String, String> keys = new LinkedHashMap<>();
        keys.put("InternalNotificationsSystemSetting", "Internal notifications");
        keys.put("NewContactPhoneNotOnTelegram", "This phone number is not on Telegram. **Invite >**");
        keys.put("NewContactPhoneInContacts", "This phone number is already in your contacts. **View >**");
        keys.put("NewContactPhoneOnTelegram", "This phone number is on Telegram.");
        keys.put("StoryPrivacyGroupNoContacts", "All group members are not in your contact list.");
        keys.put("StoryPrivacyGroupNonContacts_one", "%1$d member is not in your contact list");
        keys.put("StoryPrivacyGroupNonContacts_other", "%1$d members are not in your contact list");
        keys.put("StoryPrivacyGroupAddContacts_one", "Add %1$d contact");
        keys.put("StoryPrivacyGroupAddContacts_other", "Add %1$d contacts");
        keys.put("ForwardHideSenderNamePremium",
                "Subscribe to **Telegram Premium** to forward formatted messages without the sender’s name.");
        keys.put("StoryEditWaitUpload", "Wait until current upload is complete");
        keys.put("UnsupportedContent", "Unsupported content");
        keys.put("LocationPoweredBy", "Powered by");
        keys.put("SettingsExperimental", "Experimental");
        for (Map.Entry<String, String> entry : keys.entrySet()) {
            int occurrences = countOccurrences(strings, "name=\"" + entry.getKey() + "\"");
            if (occurrences != 1) {
                throw new AssertionError(entry.getKey() + ": expected exactly one resource, found " + occurrences);
            }
            assertEquals(entry.getKey() + " value", entry.getValue(), stringValue(strings, entry.getKey()));
        }

        String notifications = read(root, JAVA + "org/telegram/messenger/NotificationsController.java");
        assertPresent(notifications, "internal notifications channel name localized",
                "final String channelName = LocaleController.getString(R.string.InternalNotificationsSystemSetting)");
        assertPresent(notifications, "existing internal notifications channel renamed",
                "notificationChannel.setName(channelName)");
        assertMissing(notifications, "no hard-coded internal notifications channel name",
                "\"Internal notifications\"");
        // Upstream 13.2.x dropped the "Powered by Telegram" location footer entirely
        // (LocationPoweredCell retired); the resource is kept for the remaining references.
        if (Files.exists(root.resolve(JAVA + "org/telegram/ui/Cells/LocationPoweredCell.java"))) {
            assertMissing(read(root, JAVA + "org/telegram/ui/Cells/LocationPoweredCell.java"),
                    "location footer localized", "\"Powered by\"");
        }
        assertMissing(read(root, JAVA + "org/telegram/ui/Components/MessagePreviewView.java"),
                "forward hide-sender notice localized", "without the sender’s name");
        assertMissing(read(root, JAVA + "org/telegram/ui/LaunchActivity.java"),
                "unsupported-content toast localized", "\"Unsupported content\"");
        assertMissing(read(root, JAVA + "org/telegram/ui/NewContactBottomSheet.java"),
                "new contact phone status localized", "\"This phone number is");
        assertMissing(read(root, JAVA + "org/telegram/ui/SettingsActivity.java"),
                "experimental settings header localized", "asHeader(\"Experimental\")");
        assertMissing(read(root, JAVA + "org/telegram/ui/Stories/PeerStoriesView.java"),
                "story upload wait bulletin localized", "\"Wait until current upload is complete\"");
        String privacy = read(root, JAVA + "org/telegram/ui/Stories/recorder/StoryPrivacyBottomSheet.java");
        assertMissing(privacy, "story privacy group message localized",
                "All group members are not in your contact list.");
        assertMissing(privacy, "story privacy member count localized", "members are not in your contact list");
        assertMissing(privacy, "story privacy add contacts localized", "\"Add \" + groupUsers.size()");
        assertMissing(privacy, "story privacy cancel localized", "setNegativeButton(\"Cancel\", null)");
        System.out.println("[PASS] previously hard-coded texts come from existing, unique string resources");
    }

    /** 4ca7d011b: nnn settings keeps its category rows enabled without enabling headers. */
    private static void assertSettingsCategoryRows(Path root) throws IOException {
        String main = read(root, JAVA + "xyz/nextalone/nnngram/activity/MainSettingActivity.java");
        assertMissing(main, "nnn settings no longer overrides row enablement",
                "public boolean isEnabled(RecyclerView.ViewHolder holder)");
        String base = read(root, JAVA + "xyz/nextalone/nnngram/activity/BaseActivity.java");
        assertPresent(base, "base adapter keeps text rows enabled", "type == TYPE_TEXT");
        assertMissing(base, "base adapter keeps headers disabled", "type == TYPE_HEADER");
        System.out.println("[PASS] nnn settings falls back to BaseActivity row enablement");
    }

    /** cabae1854: Unban is labelled as Unban and chooses on the participant's banned rights. */
    private static void assertBanUnbanStrings(Path root) throws IOException {
        String strings = read(root, RES + "values/strings.xml");
        assertEquals("UnbanUserMonoforum label", "Unban User From Channel",
                stringValue(strings, "UnbanUserMonoforum"));
        String topics = read(root, JAVA + "org/telegram/ui/Components/TopicsTabsView.java");
        assertPresent(topics, "monoforum ban state from participant rights",
                "participant instanceof TLRPC.TL_channelParticipantBanned && participant.banned_rights != null && participant.banned_rights.view_messages");
        assertMissing(topics, "non-member no longer treated as banned", "final boolean banned = !isInChat;");
        System.out.println("[PASS] channel Direct Messages offer Unban only to actually banned users");
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int index = 0;
        while ((index = haystack.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }

    private static String stringValue(String xml, String key) {
        Matcher matcher = Pattern
                .compile("<string\\s+name=\"" + Pattern.quote(key) + "\"[^>]*>(.*?)</string>", Pattern.DOTALL)
                .matcher(xml);
        if (!matcher.find()) {
            throw new AssertionError("missing string " + key);
        }
        return matcher.group(1).trim();
    }

    private static String read(Path root, String relative) throws IOException {
        Path path = root.resolve(relative);
        if (!Files.isRegularFile(path)) {
            throw new AssertionError("missing file: " + path);
        }
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    private static void assertMissing(String source, String label, String needle) {
        if (source.contains(needle)) {
            throw new AssertionError(label + ": unexpected '" + needle + "'");
        }
    }

    private static void assertPresent(String source, String label, String needle) {
        if (!source.contains(needle)) {
            throw new AssertionError(label + ": missing '" + needle + "'");
        }
    }

    private static void assertEquals(String label, String expected, String actual) {
        if (!expected.equals(actual)) {
            throw new AssertionError(label + ": expected '" + expected + "' but was '" + actual + "'");
        }
    }
}
