package xyz.nextalone.nnngram.utils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Lightweight JVM regression test asserting that every OS-visible identifier of this
 * fork derives from {@code applicationId} instead of a hard-coded literal, so builds
 * with different {@code NNN_APP_ID} values install side by side without colliding on
 * account types, contact mime types, provider authorities or the launcher name.
 *
 * <pre>
 * javac -d /tmp/out TMessagesProj/src/test/java/xyz/nextalone/nnngram/utils/PackageIdentityTest.java
 * java -cp /tmp/out xyz.nextalone.nnngram.utils.PackageIdentityTest .
 * </pre>
 *
 * The optional argument is the repository root (default: current directory).
 */
public final class PackageIdentityTest {

    private static final String JAVA = "TMessagesProj/src/main/java/";
    private static final String RES = "TMessagesProj/src/main/res/";
    private static final String MANIFEST = "TMessagesProj/src/main/AndroidManifest.xml";

    private static final String IDENTITY = "TMessagesProj/identity/xml/";
    private static final String PACKAGE_ID = "xyz.nextalone.nnngramx";
    private static final String OLD_ID = "\"xyz.nextalone.nnngram\"";

    public static void main(String[] args) throws IOException {
        Path root = Paths.get(args.length > 0 ? args[0] : ".").toAbsolutePath().normalize();

        String gradle = read(root, "TMessagesProj/build.gradle.kts");
        assertPresent(gradle, "applicationId from NNN_APP_ID", "defaultConfig.applicationId = appId");
        assertMissing(gradle, "no hard-coded applicationId", "applicationId = \"" + PACKAGE_ID + "\"");
        assertPresent(gradle, "namespace unchanged", "namespace = \"org.telegram.messenger\"");
        assertPresent(gradle, "identity res wired", "addGeneratedSourceDirectory(identityRes");
        assertPresent(read(root, "gradle.properties"), "default install identity",
                "NNN_APP_ID=" + PACKAGE_ID);

        String contacts = read(root, JAVA + "org/telegram/messenger/ContactsController.java");
        assertMissing(contacts, "account type literal", OLD_ID);
        assertMissing(contacts, "contact mime literal", "vnd.xyz.nextalone.nnngram.android");
        assertPresent(contacts, "account type from build", "getAccountsByType(ApplicationLoader.getApplicationId())");
        assertPresent(contacts, "account creation from build", "getClientUserId(), ApplicationLoader.getApplicationId())");
        assertPresent(contacts, "contact mime from build",
                "\"vnd.android.cursor.item/vnd.\" + ApplicationLoader.getApplicationId()");

        String appLoader = read(root, JAVA + "org/telegram/messenger/ApplicationLoader.java");
        assertMissing(appLoader, "files dir fallback old id", "/data/data/xyz.nextalone.nnngram/");
        assertPresent(appLoader, "files dir fallback from build",
                "new File(\"/data/data/\" + BuildConfig.APPLICATION_ID + \"/files\")");

        String launch = read(root, JAVA + "org/telegram/ui/LaunchActivity.java");
        assertPresent(launch, "call mime from build",
                "\"vnd.android.cursor.item/vnd.\" + ApplicationLoader.getApplicationId() + \".android.call\"");
        assertPresent(launch, "video call mime from build",
                "\"vnd.android.cursor.item/vnd.\" + ApplicationLoader.getApplicationId() + \".android.call.video\"");

        assertPresent(read(root, IDENTITY + "auth.xml"), "authenticator account type",
                "android:accountType=\"__APPLICATION_ID__\"");
        assertPresent(read(root, IDENTITY + "sync_contacts.xml"), "sync adapter account type",
                "android:accountType=\"__APPLICATION_ID__\"");
        assertPresent(read(root, IDENTITY + "auth_menu.xml"), "account settings target package",
                "android:targetPackage=\"__APPLICATION_ID__\"");
        String contactsXml = read(root, IDENTITY + "contacts.xml");
        assertMissing(contactsXml, "contact data kind mime", "org.telegram.messenger.android");
        assertPresent(contactsXml, "contact data kind mime prefix",
                "vnd.android.cursor.item/vnd.__APPLICATION_ID__.android.profile");
        for (String name : new String[]{"auth", "auth_menu", "contacts", "sync_contacts"}) {
            if (Files.exists(root.resolve(RES + "xml/" + name + ".xml"))) {
                throw new AssertionError("res/xml/" + name + ".xml must be generated, not checked in");
            }
        }

        String manifest = read(root, MANIFEST);
        assertPresent(manifest, "application label", "android:label=\"@string/NnnAppLabel\"");
        assertPresent(manifest, "call mime filter", "vnd.android.cursor.item/vnd.${applicationId}.android.call\"");
        assertMissing(manifest, "stale upstream call mime", "vnd.org.telegram.messenger.android.call");
        for (String authority : new String[]{".provider", ".notification_image_provider", ".call_sound_provider"}) {
            assertPresent(manifest, "provider authority " + authority,
                    "android:authorities=\"${applicationId}" + authority + "\"");
        }

        String identityTask = read(root,
                "buildSrc/src/main/kotlin/org/telegram/tasks/GenerateIdentityResTask.kt");
        assertPresent(identityTask, "generated label resource", "NnnAppLabel");
        assertPresent(identityTask, "applicationId placeholder", "__APPLICATION_ID__");

        // NnnAppLabel is not in the bundled localization assets, so every read has to go
        // through the resource id. A by-name lookup would return LOC_ERR for it.
        assertMissing(read(root, JAVA + "org/telegram/ui/LanguageSelectActivity.java"),
                "label read by resource", "getString(\"NnngramName\", R.string.NnnAppLabel)");
        assertPresent(read(root, JAVA + "org/telegram/ui/LanguageSelectActivity.java"),
                "label read through AppName override",
                "LocaleController.getString(\"AppName\", R.string.AppName)");

        // The label must not be a checked-in string, or a renamed build would still
        // show the default name on the launcher.
        for (String strings : new String[]{
                RES + "values/strings_nullgram.xml",
                RES + "values-zh/strings_nullgram.xml",
                RES + "values-zh-rTW/strings_nullgram.xml"}) {
            assertMissing(read(root, strings), "checked-in launcher name", "NullgramName");
        }

        System.out.println("OK: install identity derives from applicationId"
                + " (default " + PACKAGE_ID + "); account/contact types, provider authorities,"
                + " FileProvider and the launcher label all follow it");
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
