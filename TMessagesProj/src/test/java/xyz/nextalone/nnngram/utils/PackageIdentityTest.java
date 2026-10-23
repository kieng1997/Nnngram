package xyz.nextalone.nnngram.utils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Lightweight JVM regression test asserting that the install identity of this fork
 * ({@code xyz.nextalone.nnngramx}) is consistent everywhere the OS sees it, so the
 * original Nnngram app can be installed side by side.
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

    private static final String PACKAGE_ID = "xyz.nextalone.nnngramx";
    private static final String APP_NAME = "NnngramX";
    private static final String OLD_ID = "\"xyz.nextalone.nnngram\"";

    public static void main(String[] args) throws IOException {
        Path root = Paths.get(args.length > 0 ? args[0] : ".").toAbsolutePath().normalize();

        String gradle = read(root, "TMessagesProj/build.gradle.kts");
        assertPresent(gradle, "applicationId", "applicationId = \"" + PACKAGE_ID + "\"");
        assertPresent(gradle, "namespace unchanged", "namespace = \"org.telegram.messenger\"");

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

        assertPresent(read(root, RES + "xml/auth.xml"), "authenticator account type",
                "android:accountType=\"" + PACKAGE_ID + "\"");
        assertPresent(read(root, RES + "xml/sync_contacts.xml"), "sync adapter account type",
                "android:accountType=\"" + PACKAGE_ID + "\"");
        assertPresent(read(root, RES + "xml/auth_menu.xml"), "account settings target package",
                "android:targetPackage=\"" + PACKAGE_ID + "\"");
        String contactsXml = read(root, RES + "xml/contacts.xml");
        assertMissing(contactsXml, "contact data kind mime", "org.telegram.messenger.android");
        assertPresent(contactsXml, "contact data kind mime prefix",
                "vnd.android.cursor.item/vnd." + PACKAGE_ID + ".android.profile");

        String manifest = read(root, MANIFEST);
        assertPresent(manifest, "application label", "android:label=\"@string/NullgramName\"");
        assertPresent(manifest, "call mime filter", "vnd.android.cursor.item/vnd.${applicationId}.android.call\"");
        assertMissing(manifest, "stale upstream call mime", "vnd.org.telegram.messenger.android.call");
        for (String authority : new String[]{".provider", ".notification_image_provider", ".call_sound_provider"}) {
            assertPresent(manifest, "provider authority " + authority,
                    "android:authorities=\"${applicationId}" + authority + "\"");
        }

        String defaultStrings = read(root, RES + "values/strings.xml");
        assertPresent(defaultStrings, "AppName", "name=\"AppName\" translatable=\"false\">" + APP_NAME + "<");
        assertPresent(defaultStrings, "AppNameBeta", "name=\"AppNameBeta\" translatable=\"false\">" + APP_NAME + " Beta<");
        assertPresent(read(root, RES + "values/strings_nullgram.xml"), "default display name",
                "<string name=\"NullgramName\" translatable=\"false\">" + APP_NAME + "</string>");
        assertPresent(read(root, RES + "values-zh/strings_nullgram.xml"), "zh display name",
                "<string name=\"NullgramName\">" + APP_NAME + "</string>");
        assertPresent(read(root, RES + "values-zh-rTW/strings_nullgram.xml"), "zh-rTW display name",
                "<string name=\"NullgramName\">" + APP_NAME + "</string>");

        System.out.println("OK: install identity, account/contact types, provider authorities and display name"
                + " consistently use " + PACKAGE_ID + " / " + APP_NAME);
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
