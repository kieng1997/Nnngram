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
 * Lightweight JVM regression test for permanently public fork settings.
 * Intentionally free of Android and JUnit dependencies, so it can be compiled
 * and run with a plain JDK:
 *
 * <pre>
 * javac -d /tmp/out TMessagesProj/src/test/java/xyz/nextalone/nnngram/utils/HiddenSettingsPublicTest.java
 * java -cp /tmp/out xyz.nextalone.nnngram.utils.HiddenSettingsPublicTest .
 * </pre>
 *
 * This is a static source regression test: it proves the settings are still
 * registered and no longer gated, and that the ignoreChatStrict row title is
 * wired to a localized resource in all three maintained locales. It is not a
 * UI runtime test.
 *
 * The optional argument is the repository root (default: current directory).
 */
public final class HiddenSettingsPublicTest {

    private static final String JAVA = "TMessagesProj/src/main/java/";
    private static final String RES = "TMessagesProj/src/main/res/";

    /** The 9 ExperimentSettingActivity rows that used to hide behind showHiddenSettings. */
    private static final String[] EXPERIMENT_ROWS = {
            "blockSponsorAds",
            "hideProxySponsorChannel",
            "disableSendTyping",
            "keepOnlineStatusAs",
            "storyStealthMode",
            "hidePremiumStickerAnim",
            "fastSpeedUpload",
            "modifyDownloadSpeed",
            "ignoreChatStrict",
    };

    /** Toggle declarations that must not be retuned just to expose the rows. */
    private static final String[] UNCHANGED_DEFINES = {
            "@BooleanConfig const val blockSponsorAds = \"blockSponsorMessages\"",
            "@BooleanConfig const val allowScreenshotOnNoForwardChat = \"allowScreenshotOnNoForwardChat\"",
            "@BooleanConfig const val disableSendTyping = \"disableSendTyping\"",
            "@BooleanConfig const val hidePremiumStickerAnim = \"hidePremiumStickerAnim\"",
            "@BooleanConfig const val fastSpeedUpload = \"fastSpeedUpload\"",
            "@IntConfig(512) const val modifyDownloadSpeed = \"modifyDownloadSpeed\"",
            "@BooleanConfig const val hideProxySponsorChannel = \"hideProxySponsorChannel\"",
            "@BooleanConfig const val storyStealthMode = \"storyStealthMode\"",
            "@IntConfig(0) const val keepOnlineStatusAs = \"keepOnlineStatusAs\"",
            "@BooleanConfig const val ignoreChatStrict = \"ignoreChatStrict\"",
    };

    private static final Map<String, String> IGNORE_CHAT_STRICT_LABELS = new LinkedHashMap<>();

    static {
        IGNORE_CHAT_STRICT_LABELS.put("values", "Ignore content protection restrictions");
        IGNORE_CHAT_STRICT_LABELS.put("values-zh", "忽略内容保护限制");
        IGNORE_CHAT_STRICT_LABELS.put("values-zh-rTW", "忽略內容保護限制");
    }

    public static void main(String[] args) throws IOException {
        Path root = Paths.get(args.length > 0 ? args[0] : ".").toAbsolutePath().normalize();
        assertEntriesAlwaysVisible(root);
        assertIgnoreChatStrictLabel(root);
        System.out.println("OK: hidden settings are permanently public and ignoreChatStrict is labelled");
    }

    /** Behaviour 1: the 11 entries stay registered and the gating is gone. */
    private static void assertEntriesAlwaysVisible(Path root) throws IOException {
        String experiment = read(root, JAVA + "xyz/nextalone/nnngram/activity/ExperimentSettingActivity.java");
        for (String row : EXPERIMENT_ROWS) {
            assertPresent(experiment, "experiment row " + row + " registered", "addRow(\"" + row + "\")");
        }
        assertMissing(experiment, "experiment rows no longer gated", "Config.showHiddenSettings");

        String chat = read(root, JAVA + "xyz/nextalone/nnngram/activity/ChatSettingActivity.java");
        assertPresent(chat, "chat screenshot row registered",
                "allowScreenshotOnNoForwardChatRow = addRow(\"allowScreenshotOnNoForwardChat\")");
        assertMissing(chat, "chat screenshot row no longer gated", "Config.showHiddenSettings");

        String login = read(root, JAVA + "org/telegram/ui/LoginActivity.java");
        assertPresent(login, "custom api login sub item registered", "moreButtonView.addSubItem(3,");
        assertPresent(login, "custom api login click handler kept", "id == 3");
        assertMissing(login, "custom api login no longer gated", "Config.showHiddenSettings");

        String main = read(root, JAVA + "xyz/nextalone/nnngram/activity/MainSettingActivity.java");
        assertMissing(main, "long press no longer toggles hidden settings", "toggleShowHiddenSettings");
        assertMissing(main, "main settings no longer gated", "Config.showHiddenSettings");

        String defines = read(root, JAVA + "xyz/nextalone/nnngram/utils/Defines.kt");
        assertMissing(defines, "dead showHiddenSettings config removed", "const val showHiddenSettings");
        for (String declaration : UNCHANGED_DEFINES) {
            assertPresent(defines, "unchanged config declaration", declaration);
        }
    }

    /** Behaviour 2: ignoreChatStrict row title comes from localized resources. */
    private static void assertIgnoreChatStrictLabel(Path root) throws IOException {
        String experiment = read(root, JAVA + "xyz/nextalone/nnngram/activity/ExperimentSettingActivity.java");
        assertPresent(experiment, "ignoreChatStrict row title localized",
                "LocaleController.getString(\"ignoreChatStrict\", R.string.ignoreChatStrict)");
        assertMissing(experiment, "ignoreChatStrict row no longer blank", "setTextAndCheck(\"\", Config.ignoreChatStrict");

        for (Map.Entry<String, String> entry : IGNORE_CHAT_STRICT_LABELS.entrySet()) {
            String xml = read(root, RES + entry.getKey() + "/strings_nullgram.xml");
            String value = stringValue(xml, "ignoreChatStrict", entry.getKey());
            if (!entry.getValue().equals(value)) {
                throw new AssertionError(entry.getKey() + " ignoreChatStrict: expected '" + entry.getValue()
                        + "' but was '" + value + "'");
            }
        }
    }

    /** Extracts the text of &lt;string name="key"&gt;...&lt;/string&gt;, refusing empty or missing values. */
    private static String stringValue(String xml, String key, String locale) {
        Matcher matcher = Pattern
                .compile("<string\\s+name=\"" + Pattern.quote(key) + "\"[^>]*>(.*?)</string>", Pattern.DOTALL)
                .matcher(xml);
        if (!matcher.find()) {
            throw new AssertionError(locale + ": missing string " + key);
        }
        String value = matcher.group(1).trim();
        if (value.isEmpty()) {
            throw new AssertionError(locale + ": empty string " + key);
        }
        return value;
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
}
