package xyz.nextalone.nnngram.utils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Lightweight JVM regression test for the removal of the in-app version check.
 * Intentionally free of Android and JUnit dependencies, so it can be compiled
 * and run with a plain JDK:
 *
 * <pre>
 * javac -d /tmp/out TMessagesProj/src/test/java/xyz/nextalone/nnngram/utils/UpdateCheckRemovedTest.java
 * java -cp /tmp/out xyz.nextalone.nnngram.utils.UpdateCheckRemovedTest .
 * </pre>
 *
 * The optional argument is the repository root (default: current directory).
 */
public final class UpdateCheckRemovedTest {

    private static final String JAVA = "TMessagesProj/src/main/java/";
    private static final String RES = "TMessagesProj/src/main/res/";

    private static final String[] REMOVED_STRINGS = {
            "CheckUpdate",
            "AutoCheckUpdateOFF",
            "AutoCheckUpdateSwitch",
            "AutoCheckUpdateStable",
            "AutoCheckUpdatePreview",
            "VersionUpdateNoUpdate",
            "NoUpdateAvailablePlay",
            "NoUpdateAvailablePlayDelay",
            "noAvailableUpdate",
            "updateAvailable",
            "variantsNotFound",
            "skipUpdate",
            "notNow",
            "updateConfirm",
            "CheckingUpdate",
            "LastUpdateNever",
            "LastUpdateDateFormatted",
            "LastUpdateFormatted",
            "AppUpdateNullgram",
            "SwitchVersion",
    };

    public static void main(String[] args) throws IOException {
        Path root = Paths.get(args.length > 0 ? args[0] : ".").toAbsolutePath().normalize();

        String launch = read(root, JAVA + "org/telegram/ui/LaunchActivity.java");
        assertMissing(launch, "LaunchActivity.checkAppUpdate", "checkAppUpdate");
        assertMissing(launch, "LaunchActivity update pipeline", "UpdateUtils");
        assertMissing(launch, "tg://update deep link", "tg://update");
        assertMissing(launch, "tg:update deep link", "tg:update");
        assertMissing(launch, "LaunchActivity showUpdateActivity", "showUpdateActivity");
        assertMissing(launch, "LaunchActivity auto-check throttle key", "lastCheckUpdateTime");
        assertMissing(launch, "LaunchActivity forced update page", "can_not_skip");
        assertMissing(launch, "LaunchActivity legacy update restore", "pendingAppUpdate");

        String updateUtils = read(root, JAVA + "xyz/nextalone/nnngram/utils/UpdateUtils.kt");
        assertMissing(updateUtils, "fork update network check", "fun checkUpdate");
        assertMissing(updateUtils, "fork update metadata model", "UpdateMetadata");
        assertMissing(updateUtils, "fork update channel ids", "previewMetadataChannelID");
        assertPresent(updateUtils, "channel follow reminder", "fun postCheckFollowChannel");

        String sharedConfig = read(root, JAVA + "org/telegram/messenger/SharedConfig.java");
        assertMissing(sharedConfig, "legacy update cache restore", "getString(\"appUpdate\"");
        assertMissing(sharedConfig, "legacy update build restore", "getInt(\"appUpdateBuild\"");
        assertMissing(sharedConfig, "legacy update time restore", "getLong(\"appUpdateCheckTime\"");
        assertMissing(sharedConfig, "fork update setter", "setNewAppVersionAvailable");
        assertPresent(sharedConfig, "clear legacy update key", "remove(\"appUpdate\")");
        assertPresent(sharedConfig, "clear legacy update build key", "remove(\"appUpdateBuild\")");
        assertPresent(sharedConfig, "clear legacy update time key", "remove(\"appUpdateCheckTime\")");

        String defines = read(root, JAVA + "xyz/nextalone/nnngram/utils/Defines.kt");
        assertMissing(defines, "dead auto update throttle config", "lastCheckUpdateTime");
        assertMissing(defines, "dead update channel config", "const val updateChannel =");
        assertMissing(defines, "dead stable channel config", "const val stableChannel");
        assertMissing(defines, "dead disable auto update config", "const val disableAutoUpdate");
        assertPresent(defines, "channel reminder config", "const val updateChannelSkip");

        String mainSettings = read(root, JAVA + "xyz/nextalone/nnngram/activity/MainSettingActivity.java");
        assertMissing(mainSettings, "nnn settings update row", "updateRow = addRow()");
        assertMissing(mainSettings, "nnn settings update row click", "position == updateRow");
        assertMissing(mainSettings, "nnn settings update entry", "tg://update");

        assertMissing(read(root, JAVA + "org/telegram/ui/SettingsActivity.java"),
                "settings version sheet update entry", "checkAppUpdate");
        assertMissing(read(root, JAVA + "org/telegram/ui/ProfileActivity.java"),
                "profile debug menu update entry", "checkAppUpdate");
        assertMissing(read(root, JAVA + "org/telegram/ui/Components/BlockingUpdateView.java"),
                "blocking update view recheck", "UpdateUtils");

        for (String locale : new String[]{"values", "values-zh", "values-zh-rTW"}) {
            String xml = read(root, RES + locale + "/strings_nullgram.xml");
            for (String key : REMOVED_STRINGS) {
                assertMissing(xml, locale + " removed string " + key, "name=\"" + key + "\"");
            }
        }
        assertUpdateStringsGoneEverywhere(root);

        System.out.println("OK: in-app update check, update entry and legacy update cache are removed");
    }

    /** Update-specific string keys must be gone from every locale, not only the three maintained ones. */
    private static void assertUpdateStringsGoneEverywhere(Path root) throws IOException {
        Set<String> keys = new LinkedHashSet<>(java.util.Arrays.asList(REMOVED_STRINGS));
        Path res = root.resolve(RES);
        for (Path dir : Files.newDirectoryStream(res, "values*")) {
            Path xml = dir.resolve("strings_nullgram.xml");
            if (!Files.isRegularFile(xml)) {
                continue;
            }
            String content = new String(Files.readAllBytes(xml), StandardCharsets.UTF_8);
            for (String key : keys) {
                if (content.contains("name=\"" + key + "\"")) {
                    throw new AssertionError("stale update string " + key + " in " + dir.getFileName());
                }
            }
        }
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
