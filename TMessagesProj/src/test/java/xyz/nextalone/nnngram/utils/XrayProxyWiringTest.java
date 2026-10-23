package xyz.nextalone.nnngram.utils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lightweight JVM regression test for the Xray app-side wiring. It has no
 * Android or JUnit dependency, so it compiles with plain javac:
 *
 * <pre>
 * javac -d /tmp/out TMessagesProj/src/test/java/xyz/nextalone/nnngram/utils/XrayProxyWiringTest.java
 * java -cp /tmp/out xyz.nextalone.nnngram.utils.XrayProxyWiringTest .
 * </pre>
 *
 * The optional argument is the repository root (default: current directory).
 *
 * <p>It asserts the invariants that are cheap to check statically and were the
 * actual failure modes of the design:
 * <ul>
 *   <li>Xray persists as type int 3 and keeps the original link in {@code secret};</li>
 *   <li>the share link never becomes the native proxy {@code secret};</li>
 *   <li>every Xray native hand-off goes through a loopback address, never empty;</li>
 *   <li>the Xray editor is reachable from the add-proxy row, the per-item gear and
 *       QR scanning, and the old editor is not used for XRAY entries;</li>
 *   <li>the three localisation files carry the same Xray keys.</li>
 * </ul>
 */
public final class XrayProxyWiringTest {

    private static final String JAVA = "TMessagesProj/src/main/java/";
    private static final String RES = "TMessagesProj/src/main/res/";

    private static final String PROXY_SETTINGS = JAVA + "org/telegram/utils/proxy/ProxySettings.java";
    private static final String CONNECTIONS = JAVA + "org/telegram/tgnet/ConnectionsManager.java";
    private static final String PROXY_LIST = JAVA + "org/telegram/ui/ProxyListActivity.java";
    private static final String QR_HELPER = JAVA + "xyz/nextalone/nnngram/helpers/QrHelper.kt";
    private static final String XRAY_HELPER = JAVA + "xyz/nextalone/nnngram/helpers/XrayProxyHelper.java";
    private static final String XRAY_ACTIVITY = JAVA + "xyz/nextalone/nnngram/activity/XrayProxyActivity.java";

    private static int failures;

    public static void main(String[] args) throws IOException {
        Path root = Paths.get(args.length > 0 ? args[0] : ".").toAbsolutePath().normalize();

        final String proxySettings = read(root, PROXY_SETTINGS);
        final String connections = read(root, CONNECTIONS);
        final String proxyList = read(root, PROXY_LIST);
        final String qrHelper = read(root, QR_HELPER);
        final String xrayHelper = read(root, XRAY_HELPER);
        final String xrayActivity = read(root, XRAY_ACTIVITY);

        // Persistence: XRAY is append-only int 3, the old values keep their meaning.
        assertContains(proxySettings, "XRAY persistence int", "case XRAY:\n                return 3;");
        assertContains(proxySettings, "XRAY read int 3", "case 3:\n                return Type.XRAY;");
        assertContains(proxySettings, "legacy SOCKS5 int", "case SOCKS5:\n                return 0;");
        assertContains(proxySettings, "legacy MTPROTO int", "case MTPROTO:\n                return 1;");
        assertContains(proxySettings, "legacy WEB int", "case WEB:\n                return 2;");
        assertContains(proxySettings, "XRAY writes secret+port", "case XRAY:");
        assertContains(proxySettings, "XRAY share link is the link", "if (type == Type.XRAY) {\n            return secret;\n        }");

        // The share link must never reach the native secret parameter.
        assertNotContains(connections, "no link as native secret", "native_setProxySettings(a, address, port, username, password, secret);\n            } else if (enabled");
        assertContains(connections, "xray native hand-off clears secret",
                "native_setProxySettings(a, address, port, username, password, \"\");");
        assertContains(connections, "runtime path retries a failed start", "pushProxyLocked(UserConfig.MAX_ACCOUNT_COUNT, 0, enabled, settings, true)");
        assertContains(connections, "cold start re-applies current intent after native_init", "applyXrayLocked(null);");
        assertContains(connections, "xray applies credentials",
                "native_setProxySettings(a, \"127.0.0.1\", endpoint.port, endpoint.username, endpoint.password, \"\");");

        // Fail-closed: the cold start must not sit behind an isValid guard.
        assertContains(connections, "cold start serialised under proxy lock", "synchronized (proxySettingsLock) {");
        assertContains(connections, "async apply re-reads persisted intent", "applyXrayLocked(");
        assertContains(connections, "cold start fan-out count", "pushProxyLocked(1, currentAccount, desiredEnabled, desiredSettings, false)");
        assertContains(connections, "runtime switch fans out", "pushProxyLocked(UserConfig.MAX_ACCOUNT_COUNT, 0, enabled, settings, true)");
        assertContains(connections, "intent initialized once flag", "private static boolean desiredInitialized;");
        assertContains(connections, "async apply gates on desired intent",
                "if (!desiredEnabled || desiredSettings == null || desiredSettings.getType() != ProxySettings.Type.XRAY) {");
        assertContains(connections, "async apply pushes loopback to all accounts",
                "native_setProxySettings(a, \"127.0.0.1\", endpoint.port, endpoint.username, endpoint.password, \"\");");
        assertContains(read(root, JAVA + "xyz/nextalone/nnngram/helpers/XrayEndpoint.java"),
                "fail-closed placeholder", "FAIL_CLOSED_PORT = 9");
        assertContains(read(root, JAVA + "xyz/nextalone/nnngram/helpers/XraySessionController.java"),
                "empty link fails closed and reports", "Never silently go direct.");
        assertContains(read(root, JAVA + "xyz/nextalone/nnngram/helpers/XraySessionController.java"),
                "probe release enqueued with slot held", "worker.execute(() -> releaseProbe(probe, session, time));");
        assertContains(read(root, JAVA + "xyz/nextalone/nnngram/helpers/XraySessionController.java"),
                "cancel skips already-finished probe (no second callback)",
                "if (currentProbe != null && !currentProbe.finished) {\n                cancelled.addFirst(currentProbe);");

        // Switching away releases the core; the WEB path is untouched.
        assertContains(connections, "stop xray when disabled", "XrayProxyHelper.stop();");
        assertContains(connections, "web proxy still starts", "WebProxyTransport.start(address, secret)");
        assertContains(connections, "web proxy still stops", "WebProxyTransport.stop();");

        // Detection always produces a result, never a cached success.
        assertContains(connections, "xray check callback", "XrayProxyHelper.probe(settings,");
        assertContains(xrayHelper, "probe wired to controller", "controller.probe(");
        assertContains(read(root, JAVA + "xyz/nextalone/nnngram/helpers/XraySessionController.java"), "probe closes session on worker", "closeQuietly(session)");

        // UI routing.
        assertContains(proxyList, "add row offers the new editor", "new XrayProxyActivity()");
        assertContains(proxyList, "add picker names legacy protocols", "XrayProxyAddLegacy");
        assertContains(proxyList, "notification refreshes enabled flag",
                "useProxySettings = MessagesController.getGlobalMainSettings().getBoolean(\"proxy_enabled\", false) && !SharedConfig.proxyList.isEmpty();");
        assertContains(proxyList, "gear routes xray entries", "currentInfo.settings.getType() == ProxySettings.Type.XRAY");
        assertContains(proxyList, "list hides loopback", "xrayLabel(proxyInfo.settings.getSecret())");
        assertNotContains(proxyList, "no loopback in list label", "\"127.0.0.1\"");
        assertContains(qrHelper, "qr routes xray links", "XrayProxyActivity(link)");
        assertContains(qrHelper, "qr keeps unknown links", "Browser.openUrl(fragment.parentActivity, link, true, false)");
        assertContains(qrHelper, "qr recognizes vless links", "\"vless://\"");

        // VLESS display: REALITY distinct from TLS, vless recognized everywhere.
        assertContains(xrayActivity, "editor names VLESS", "\"vless\".equals(protocol)");
        assertContains(xrayActivity, "REALITY shown distinctly", "\"reality\".equals(security)");
        assertContains(xrayActivity, "REALITY never plain TLS", "transport += \" + REALITY\"");
        assertContains(proxyList, "list labels VLESS", "\"vless://\", 0, 8");

        // The editor keeps secrets out of its own error path.
        assertNotContains(xrayActivity, "no link logging", "Log.e(");
        assertContains(xrayActivity, "revision guarded parse", "requestRevision != revision");
        assertContains(xrayActivity, "no personalized learning on imeOptions",
                "linkField.setImeOptions(EditorInfo.IME_ACTION_DONE | EditorInfo.IME_FLAG_NO_EXTRACT_UI\n                | EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);");
        assertNotContains(xrayActivity, "no personalized learning in inputType",
                "InputType.TYPE_TEXT_VARIATION_URI | EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING");
        assertContains(xrayActivity, "oversized input rejected", "MAX_LINK_LENGTH");
        assertContains(xrayActivity, "canonical add", "SharedConfig.addProxy(candidate)");
        assertContains(xrayActivity, "replace object on edit", "SharedConfig.proxyList.set(index, candidate)");
        // Localisation parity.
        final List<String> en = xrayKeys(read(root, RES + "values/strings_nullgram.xml"));
        final List<String> zh = xrayKeys(read(root, RES + "values-zh/strings_nullgram.xml"));
        final List<String> tw = xrayKeys(read(root, RES + "values-zh-rTW/strings_nullgram.xml"));
        assertTrue("xray strings present", en.size() >= 17);
        assertTrue("zh xray strings match: " + zh, en.equals(zh));
        assertTrue("zh-rTW xray strings match: " + tw, en.equals(tw));

        if (failures > 0) {
            System.out.println(failures + " assertion(s) failed");
            System.exit(1);
        }
        System.out.println("XrayProxyWiringTest OK");
    }

    private static List<String> xrayKeys(String xml) {
        final Pattern pattern = Pattern.compile("<string name=\"(Xray[A-Za-z0-9_]+)\"");
        final Matcher matcher = pattern.matcher(xml);
        final List<String> keys = new ArrayList<>();
        while (matcher.find()) {
            keys.add(matcher.group(1));
        }
        return keys;
    }

    private static String read(Path root, String relative) throws IOException {
        return new String(Files.readAllBytes(root.resolve(relative)), StandardCharsets.UTF_8);
    }

    private static void assertContains(String source, String what, String needle) {
        final String normalized = source.replace("\r\n", "\n");
        assertTrue(what, normalized.contains(needle.replace("\r\n", "\n")));
    }

    private static void assertNotContains(String source, String what, String needle) {
        final String normalized = source.replace("\r\n", "\n");
        assertTrue(what, !normalized.contains(needle.replace("\r\n", "\n")));
    }

    private static void assertTrue(String what, boolean condition) {
        if (!condition) {
            failures++;
            System.out.println("FAIL: " + what);
        }
    }
}
