package xyz.nextalone.nnngram.ui;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import xyz.nextalone.nnngram.helpers.DisguiseTapGate;

/**
 * Behaviour and wiring regression test for the disguise screen.
 *
 * <p>Plain JDK only: no Android, no JUnit. Compile and run with javac:
 *
 * <pre>
 * javac -d /tmp/out \
 *   TMessagesProj/src/main/java/xyz/nextalone/nnngram/helpers/DisguiseTapGate.java \
 *   TMessagesProj/src/test/java/xyz/nextalone/nnngram/ui/DisguiseModeTest.java
 * java -cp /tmp/out xyz.nextalone.nnngram.ui.DisguiseModeTest .
 * </pre>
 *
 * <p>The optional argument is the repository root (default: current directory).
 */
public final class DisguiseModeTest {

    private static final String JAVA = "TMessagesProj/src/main/java/";
    private static final String RES = "TMessagesProj/src/main/res/";
    private static final String ANDROID = "http://schemas.android.com/apk/res/android";
    private static final long WINDOW = 1500;

    public static void main(String[] args) throws Exception {
        Path root = Paths.get(args.length > 0 ? args[0] : ".").toAbsolutePath().normalize();

        // Behaviour: only three taps inside the window unlock the real app.
        unlockOnThreeFastTaps();
        ignoreThreeSlowTaps();
        acceptTapExactlyAtWindowBoundary();
        ignoreTapOneMillisecondPastWindow();
        notRetriggeredByTheFourthTap();
        restartCountingAfterTooOldTap();

        // Wiring: the toggle, the row, the three locales, the manifest entry and
        // the launch gate must all be in place, or the feature is unreachable.
        assertToggleDeclared(root);
        assertSettingsRowWired(root);
        assertFakePageStrings(root);
        assertManifestEntry(root);
        assertLaunchGateWired(root);
        assertWarmReopenGated(root);
        assertFakePageLayout(root);

        System.out.println("OK: disguise tap gate and wiring");
    }

    private static void assertToggleDeclared(Path root) throws IOException {
        String defines = read(root, JAVA + "xyz/nextalone/nnngram/utils/Defines.kt");
        assertPresent(defines, "disguise toggle declared",
                "@BooleanConfig const val disguiseMode = \"disguiseMode\"");
    }

    private static void assertSettingsRowWired(Path root) throws IOException {
        String general = read(root, JAVA + "xyz/nextalone/nnngram/activity/GeneralSettingActivity.java");
        assertPresent(general, "disguise row field", "private int disguiseModeRow;");
        assertPresent(general, "disguise row registered", "disguiseModeRow = addRow(\"disguiseMode\");");
        assertPresent(general, "disguise row toggles the config", "Config.toggleDisguiseMode();");
        assertPresent(general, "disguise row title localized",
                "LocaleController.getString(\"disguiseMode\", R.string.disguiseMode)");
        assertPresent(general, "disguise row shows the saved state", "Config.disguiseMode, true");
        // The row has to sit inside the general section, which is what makes
        // getItemViewType return the check-cell type for it.
        int generalRow = general.indexOf("generalRow = addRow();");
        int disguiseRow = general.indexOf("disguiseModeRow = addRow(\"disguiseMode\");");
        int general2Row = general.indexOf("general2Row = addRow();");
        assertTrue(generalRow >= 0 && disguiseRow > generalRow && general2Row > disguiseRow,
                "disguise row sits between the general header and its divider");
    }

    private static void assertFakePageStrings(Path root) throws IOException {
        String[] keys = {"disguiseMode", "FakeApkTitle", "FakeApkSubtitle", "FakeApkInfoLabel",
                "FakeApkStatus", "FakeApkAnalyzing", "FakeApkAnalysisFailed", "FakeApkAnalyze"};
        for (String locale : new String[]{"values", "values-zh", "values-zh-rTW"}) {
            String xml = read(root, RES + locale + "/strings_nullgram.xml");
            for (String key : keys) {
                stringValue(xml, key, locale);
            }
        }
        assertStringValue(root, "values", "disguiseMode", "Disguise Screen");
        assertStringValue(root, "values-zh", "disguiseMode", "伪装界面");
        assertStringValue(root, "values-zh-rTW", "disguiseMode", "偽裝介面");
        assertStringValue(root, "values", "FakeApkAnalyze", "Analyze");
        assertStringValue(root, "values-zh", "FakeApkAnalyze", "分析");
        assertStringValue(root, "values-zh-rTW", "FakeApkAnalyze", "分析");
        assertStringValue(root, "values-zh", "FakeApkAnalysisFailed", "分析失败");
    }

    private static void assertManifestEntry(Path root) throws Exception {
        var factory = javax.xml.parsers.DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        var document = factory.newDocumentBuilder()
                .parse(root.resolve("TMessagesProj/src/main/AndroidManifest.xml").toFile());
        org.w3c.dom.Element fake = null;
        org.w3c.dom.NodeList activities = document.getElementsByTagName("activity");
        for (int i = 0; i < activities.getLength(); i++) {
            org.w3c.dom.Element activity = (org.w3c.dom.Element) activities.item(i);
            if ("xyz.nextalone.nnngram.activity.FakeApkTestActivity"
                    .equals(activity.getAttributeNS(ANDROID, "name"))) {
                fake = activity;
            }
        }
        if (fake == null) {
            throw new AssertionError("manifest: fake page activity is not declared");
        }
        assertTrue("false".equals(fake.getAttributeNS(ANDROID, "exported")),
                "fake page activity must not be exported");
        assertTrue("true".equals(fake.getAttributeNS(ANDROID, "excludeFromRecents")),
                "fake page task must stay out of recents");
        org.w3c.dom.NodeList categories = fake.getElementsByTagName("category");
        for (int i = 0; i < categories.getLength(); i++) {
            String name = ((org.w3c.dom.Element) categories.item(i)).getAttributeNS(ANDROID, "name");
            if ("android.intent.category.LAUNCHER".equals(name)) {
                throw new AssertionError("fake page activity must not be a launcher entry");
            }
        }
    }

    private static void assertLaunchGateWired(Path root) throws IOException {
        String launch = read(root, JAVA + "org/telegram/ui/LaunchActivity.java");
        int gate = launch.indexOf("DisguiseLauncher.showIfNeeded(this)");
        int postInit = launch.indexOf("ApplicationLoader.postInitApplication();");
        int realUi = launch.indexOf("Theme.createCommonChatResources();");
        assertTrue(gate >= 0, "LaunchActivity consults the disguise gate");
        assertTrue(postInit >= 0 && gate > postInit,
                "the gate runs after ApplicationLoader.postInitApplication()");
        assertTrue(realUi > gate, "the gate runs before the real UI is built");
        assertPresent(launch, "LaunchActivity imports the gate",
                "import xyz.nextalone.nnngram.helpers.DisguiseLauncher;");

        String launcher = read(root, JAVA + "xyz/nextalone/nnngram/helpers/DisguiseLauncher.java");
        assertPresent(launcher, "gate reads the saved toggle", "Config.disguiseMode");
        assertPresent(launcher, "gate consumes the in-process permit",
                "if (launchPermitted) {\n            launchPermitted = false;");
        assertPresent(launcher, "only the fake page can authorize the next launch",
                "public static void permitNextLaunch()");
        assertPresent(launcher, "gate copies the original launch intent",
                "new Intent(intent)");
        assertPresent(launcher, "gate opens the decoy in its own task",
                "Intent.FLAG_ACTIVITY_CLEAR_TASK");
        assertTrue(!launcher.contains("disguise_unlocked") && !launcher.contains("getBooleanExtra"),
                "no public Intent extra can unlock the gate");
        assertPresent(launcher, "gate starts the fake page",
                "FakeApkTestActivity.class");

        String fake = read(root, JAVA + "xyz/nextalone/nnngram/activity/FakeApkTestActivity.java");
        assertPresent(fake, "fake page reuses the triple-tap gate", "new DisguiseTapGate(");
        assertPresent(fake, "fake page unlocks with the stored launch intent",
                "getParcelableExtra(DisguiseLauncher.EXTRA_REAL_LAUNCH_INTENT)");
        assertPresent(fake, "fake page targets the real activity", "LaunchActivity.class");
        assertPresent(fake, "fake page authorizes the real launch", "DisguiseLauncher.permitNextLaunch();");
        assertPresent(fake, "fake page fakes the analysis failure",
                "R.string.FakeApkAnalysisFailed");
        assertPresent(fake, "fake page shows the analyze button",
                "R.string.FakeApkAnalyze");
    }

    private static void assertWarmReopenGated(Path root) throws IOException {
        String launch = read(root, JAVA + "org/telegram/ui/LaunchActivity.java");
        int newIntent = launch.indexOf("public void onNewIntent(Intent intent) {");
        int gate = launch.indexOf("DisguiseLauncher.showIfNeeded(this, intent)", newIntent);
        int handle = launch.indexOf("handleIntent(intent, true, false, false, null, true, true);", newIntent);
        assertTrue(newIntent >= 0, "LaunchActivity overrides onNewIntent(Intent)");
        assertTrue(gate > newIntent, "warm reopen consults the disguise gate");
        assertTrue(handle > gate, "the gate runs before the incoming Intent is handled");
    }

    /**
     * Source/wiring check, not a runtime measurement: the decoy page must let the
     * info label keep its width, scroll on short viewports, and use real insets.
     */
    private static void assertFakePageLayout(Path root) throws IOException {
        String fake = read(root, JAVA + "xyz/nextalone/nnngram/activity/FakeApkTestActivity.java");
        int row = fake.indexOf("private void addInfoRow(");
        int rowEnd = fake.indexOf("private void onAnalyze()", row);
        assertTrue(row >= 0 && rowEnd > row, "fake page keeps an info row builder");
        String rowSource = fake.substring(row, rowEnd);
        assertPresent(rowSource, "info value keeps its intrinsic width",
                "row.addView(valueView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));");
        assertTrue(!rowSource.contains("row.addView(valueView, wrap())"),
                "info value must not take the full row width");

        assertPresent(fake, "fake page body scrolls on short viewports", "new ScrollView(this)");
        assertPresent(fake, "fake page applies system bar insets",
                "ViewCompat.setOnApplyWindowInsetsListener");
        assertTrue(!fake.contains("AndroidUtilities.dp(48), pad"),
                "fake page must not fake the status bar with a fixed top padding");
    }

    private static void assertStringValue(Path root, String locale, String key, String expected)
            throws IOException {
        String value = stringValue(read(root, RES + locale + "/strings_nullgram.xml"), key, locale);
        if (!expected.equals(value)) {
            throw new AssertionError(locale + " " + key + ": expected '" + expected + "' but was '" + value + "'");
        }
    }

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

    private static void assertPresent(String source, String label, String needle) {
        if (!source.contains(needle)) {
            throw new AssertionError(label + ": missing '" + needle + "'");
        }
    }

    private static void unlockOnThreeFastTaps() {
        DisguiseTapGate gate = new DisguiseTapGate(WINDOW);
        assertFalse(gate.onTap(1000), "first fast tap unlocks");
        assertFalse(gate.onTap(1400), "second fast tap unlocks");
        assertTrue(gate.onTap(1800), "third fast tap unlocks");
    }

    private static void ignoreThreeSlowTaps() {
        DisguiseTapGate gate = new DisguiseTapGate(WINDOW);
        assertFalse(gate.onTap(1000), "slow tap 1 unlocks");
        assertFalse(gate.onTap(1900), "slow tap 2 unlocks");
        assertFalse(gate.onTap(2600), "slow tap 3 unlocks");
    }

    private static void acceptTapExactlyAtWindowBoundary() {
        DisguiseTapGate gate = new DisguiseTapGate(WINDOW);
        assertFalse(gate.onTap(0), "boundary tap 1 unlocks");
        assertFalse(gate.onTap(700), "boundary tap 2 unlocks");
        assertTrue(gate.onTap(1500), "tap exactly at the window edge still counts");
    }

    private static void ignoreTapOneMillisecondPastWindow() {
        DisguiseTapGate gate = new DisguiseTapGate(WINDOW);
        assertFalse(gate.onTap(0), "past-boundary tap 1 unlocks");
        assertFalse(gate.onTap(750), "past-boundary tap 2 unlocks");
        assertFalse(gate.onTap(1501), "tap past the window starts over");
    }

    private static void notRetriggeredByTheFourthTap() {
        DisguiseTapGate gate = new DisguiseTapGate(WINDOW);
        gate.onTap(0);
        gate.onTap(400);
        assertTrue(gate.onTap(900), "unlock before checking the fourth tap");
        assertFalse(gate.onTap(950), "fourth tap immediately after unlock unlocks again");
    }

    private static void restartCountingAfterTooOldTap() {
        DisguiseTapGate gate = new DisguiseTapGate(WINDOW);
        assertFalse(gate.onTap(0), "restart tap 1 unlocks");
        assertFalse(gate.onTap(500), "restart tap 2 unlocks");
        assertFalse(gate.onTap(2100), "stale tap 3 unlocks");
        assertFalse(gate.onTap(2300), "restart tap 4 unlocks");
        assertTrue(gate.onTap(2500), "three fresh taps after the gap unlock");
    }

    private static void assertTrue(boolean actual, String label) {
        if (!actual) {
            throw new AssertionError(label + ": expected true but was false");
        }
    }

    private static void assertFalse(boolean actual, String label) {
        if (actual) {
            throw new AssertionError(label + ": expected false but was true");
        }
    }

    private static String read(Path root, String relative) throws IOException {
        Path path = root.resolve(relative);
        if (!Files.isRegularFile(path)) {
            throw new AssertionError("missing file: " + path);
        }
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
