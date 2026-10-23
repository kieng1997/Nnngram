package xyz.nextalone.nnngram.utils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Stream;

/**
 * Lightweight JVM regression test asserting that the app builds and runs without Firebase /
 * Google Services (no google-services.json required), while normal networking, local logging,
 * keep-alive push service and the earlier updater removal stay intact.
 *
 * <pre>
 * javac -d /tmp/out TMessagesProj/src/test/java/xyz/nextalone/nnngram/utils/FirebaseRemovalTest.java
 * java -cp /tmp/out xyz.nextalone.nnngram.utils.FirebaseRemovalTest .
 * </pre>
 *
 * The optional argument is the repository root (default: current directory).
 */
public final class FirebaseRemovalTest {

    private static final String JAVA = "TMessagesProj/src/main/java/";
    private static final String MANIFEST = "TMessagesProj/src/main/AndroidManifest.xml";

    private static final String[] CONFIG_MANIFESTS = {
            "TMessagesProj/config/debug/AndroidManifest.xml",
            "TMessagesProj/config/debug/AndroidManifest_SDK23.xml",
            "TMessagesProj/config/release/AndroidManifest.xml",
            "TMessagesProj/config/release/AndroidManifest_SDK23.xml",
            "TMessagesProj/config/release/AndroidManifest_standalone.xml",
    };

    public static void main(String[] args) throws IOException {
        Path root = Paths.get(args.length > 0 ? args[0] : ".").toAbsolutePath().normalize();

        // --- build wiring: no Firebase plugins, dependencies or symbols upload ---
        String moduleGradle = read(root, "TMessagesProj/build.gradle.kts");
        assertMissing(moduleGradle, "firebase crashlytics plugin", "firebase.crashlytics");
        assertMissing(moduleGradle, "google services plugin", "google.services");
        assertMissing(moduleGradle, "firebase bom", "firebase.bom");
        assertMissing(moduleGradle, "firebase messaging", "firebase.messaging");
        assertMissing(moduleGradle, "firebase config", "firebase.config");
        assertMissing(moduleGradle, "crashlytics ndk", "crashlytics.ndk");
        assertMissing(moduleGradle, "crashlytics extension", "CrashlyticsExtension");

        String rootGradle = read(root, "build.gradle.kts");
        assertMissing(rootGradle, "root firebase plugin", "firebase.crashlytics");
        assertMissing(rootGradle, "root google services plugin", "google.services");

        String catalog = read(root, "gradle/libs.versions.toml");
        assertMissing(catalog, "catalog firebase bom", "firebase-bom");
        assertMissing(catalog, "catalog firebase messaging", "firebase-messaging");
        assertMissing(catalog, "catalog firebase config", "firebase-config");
        assertMissing(catalog, "catalog crashlytics", "firebase.crashlytics");
        assertMissing(catalog, "catalog google services", "com.google.gms.google-services");

        String ci = read(root, ".github/workflows/ci.yml");
        assertMissing(ci, "CI google-services.json injection", "GOOGLE_SERVICES_JSON");
        assertMissing(ci, "CI crashlytics symbol upload", "uploadCrashlyticsSymbolFile");

        // --- manifest: no Firebase messaging service or icon metadata ---
        for (String manifest : concat(MANIFEST, CONFIG_MANIFESTS)) {
            String xml = read(root, manifest);
            assertMissing(xml, manifest + " firebase messaging action", "com.google.firebase.MESSAGING_EVENT");
            assertMissing(xml, manifest + " gcm service", "GcmPushListenerService");
            assertMissing(xml, manifest + " c2dm permission", "com.google.android.c2dm.permission.RECEIVE");
        }
        assertMissing(read(root, MANIFEST), "firebase notification icon metadata",
                "com.google.firebase.messaging.default_notification_icon");

        // --- runtime: no Firebase SDK symbol anywhere in main sources ---
        assertNoFirebaseImports(root.resolve(JAVA));
        assertMissing(read(root, JAVA + "xyz/nextalone/nnngram/utils/Log.kt"),
                "Log.kt crashlytics logging", "FirebaseCrashlytics");
        assertMissing(read(root, JAVA + "org/telegram/messenger/PushListenerController.java"),
                "push provider firebase token fetch", "FirebaseMessaging");
        if (Files.exists(root.resolve(JAVA + "xyz/nextalone/nnngram/utils/AnalyticsUtils.kt"))) {
            throw new AssertionError("AnalyticsUtils.kt still present");
        }
        if (Files.exists(root.resolve(JAVA + "org/telegram/messenger/GcmPushListenerService.java"))) {
            throw new AssertionError("GcmPushListenerService.java still present");
        }
        if (Files.exists(root.resolve("TMessagesProj/src/main/res/raw/tg_keep.xml"))) {
            throw new AssertionError("crashlytics tg_keep.xml still present");
        }

        // --- disabled push contract: provider stays non-null but truthfully reports no services ---
        String push = read(root, JAVA + "org/telegram/messenger/PushListenerController.java");
        assertPresent(push, "push provider interface kept", "public interface IPushListenerServiceProvider");
        assertPresent(push, "push provider singleton kept", "GooglePushListenerServiceProvider INSTANCE");
        assertPresent(push, "push provider disabled", "return false;");
        String appLoader = read(root, JAVA + "org/telegram/messenger/ApplicationLoader.java");
        assertPresent(appLoader, "push provider hook kept", "onCreatePushProvider()");
        assertMissing(appLoader, "firebase generating status", "__FIREBASE_GENERATING_SINCE_");

        // --- stale FCM token must not survive upgrade ---
        String sharedConfig = read(root, JAVA + "org/telegram/messenger/SharedConfig.java");
        assertPresent(sharedConfig, "drop legacy push token", "remove(\"pushString2\")");
        assertMissing(sharedConfig, "legacy push token restore", "getString(\"pushString2\"");

        // --- preserved behaviour: local logging, crash listener, networking, keep-alive ---
        String log = read(root, JAVA + "xyz/nextalone/nnngram/utils/Log.kt");
        assertPresent(log, "local file logging", "writeToFile(Level.FATAL");
        assertPresent(log, "local crash logging entry", "fun fatal(throwable: Throwable?)");
        assertPresent(read(root, JAVA + "xyz/nextalone/nnngram/Listeners.kt"),
                "local CrashListener registered", "object CrashListener");
        assertPresent(appLoader, "CrashListener init kept", "CrashListener.INSTANCE.init()");
        assertPresent(appLoader, "keep-alive push service kept", "public static void startPushService()");
        assertPresent(read(root, JAVA + "org/telegram/tgnet/ConnectionsManager.java"),
                "normal networking init kept", "native_init(currentAccount");
        assertPresent(read(root, JAVA + "org/telegram/messenger/GoogleLocationProvider.java"),
                "non-firebase Google location kept", "LocationServices.getFusedLocationProviderClient");

        System.out.println("OK: Firebase/Google Services build wiring, SDK calls and manifest services are removed;"
                + " push provider is a truthful no-op, legacy token is dropped, logging/network/keep-alive/updater stay intact");
    }

    private static String[] concat(String first, String[] rest) {
        String[] all = new String[rest.length + 1];
        all[0] = first;
        System.arraycopy(rest, 0, all, 1, rest.length);
        return all;
    }

    /** No Firebase SDK import may remain in the shipped sources. */
    private static void assertNoFirebaseImports(Path javaRoot) throws IOException {
        try (Stream<Path> files = Files.walk(javaRoot)) {
            for (Path file : (Iterable<Path>) files.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java") || p.toString().endsWith(".kt"))::iterator) {
                String content = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
                if (content.contains("com.google.firebase")) {
                    throw new AssertionError("Firebase SDK reference in " + javaRoot.relativize(file));
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
