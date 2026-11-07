package xyz.nextalone.nnngram.message;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Behaviour regression for sponsored ads vs. the {@code filterAction} cache.
 *
 * <p>MessageObject is a huge Android class and this repo has no Robolectric /
 * instrumentation harness, so this test compiles the <b>actual production method
 * bodies</b> of {@code getFilterAction()} / {@code isBlockedMessage()} /
 * {@code isSponsored()} — extracted verbatim from MessageObject.java at run time —
 * into a tiny fixture whose Android / controller / rule-engine dependencies are
 * simple fakes. The methods under test are never mocked or rewritten; only their
 * surroundings are stubbed. This is an isolated method-behaviour test, not a real
 * Android/UI integration test.</p>
 *
 * <p>It reproduces the real call chain: MessagesController assigns
 * {@code sponsoredId} <i>after</i> the MessageObject constructor has already
 * queried {@code getFilterAction()} (sponsoredId still null) and cached the
 * result.</p>
 *
 * <pre>
 * javac -d TMessagesProj/build/tmp/sponsored-filter-action/test-classes \
 *     TMessagesProj/src/test/java/xyz/nextalone/nnngram/message/SponsoredMessageFilterActionTest.java
 * java -cp TMessagesProj/build/tmp/sponsored-filter-action/test-classes \
 *     xyz.nextalone.nnngram.message.SponsoredMessageFilterActionTest .
 * </pre>
 *
 * The optional argument is the repository root (default: current directory).
 * Generated sources and classes go to {@code TMessagesProj/build/tmp/sponsored-filter-action/}.
 */
public final class SponsoredMessageFilterActionTest {

    private static Class<?> messageObject;
    private static Class<?> config;
    private static Class<?> rules;

    public static void main(String[] args) throws Exception {
        Path root = Paths.get(args.length > 0 ? args[0] : ".").toAbsolutePath().normalize();
        Path production = root.resolve("TMessagesProj/src/main/java/org/telegram/messenger/MessageObject.java");
        String source = new String(Files.readAllBytes(production), StandardCharsets.UTF_8);

        String extracted = extractMethod(source, "public int getFilterAction()")
                + "\n" + extractMethod(source, "public boolean isBlockedMessage()")
                + "\n" + extractMethod(source, "public boolean isSponsored()");

        Path work = root.resolve("TMessagesProj/build/tmp/sponsored-filter-action");
        Path fixtureSrc = work.resolve("fixture-src");
        Path fixtureClasses = work.resolve("fixture-classes");
        Files.createDirectories(fixtureSrc);
        Files.createDirectories(fixtureClasses);
        Path fixtureFile = fixtureSrc.resolve("MessageObject.java");
        Files.write(fixtureFile, fixtureSource(extracted).getBytes(StandardCharsets.UTF_8));

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException("no JDK compiler on this JVM; run with a JDK, not a JRE");
        }
        ByteArrayOutputStream javacOut = new ByteArrayOutputStream();
        int rc = compiler.run(null, null, new PrintStream(javacOut, true, StandardCharsets.UTF_8),
                "-d", fixtureClasses.toString(), fixtureFile.toString());
        if (rc != 0) {
            throw new IllegalStateException("fixture failed to compile:\n" + javacOut.toString(StandardCharsets.UTF_8));
        }

        URLClassLoader loader = new URLClassLoader(
                new URL[]{fixtureClasses.toUri().toURL()}, SponsoredMessageFilterActionTest.class.getClassLoader());
        try {
            messageObject = loader.loadClass("fixture.MessageObject");
            config = loader.loadClass("fixture.MessageObject$Config");
            rules = loader.loadClass("fixture.MessageObject$MessageFilterRules");

            scenarioA_staleNoneCacheMustNotLetSponsoredAdThrough();
            scenarioB_adSwitchDoesNotLeakCachedState();
            scenarioD_coldCacheLeavesNoResidualAdHide();
            scenarioC_ordinaryPromoPostStaysVisible();
        } finally {
            loader.close();
        }
        System.out.println("ALL OK");
    }

    /**
     * A. The constructor queries getFilterAction() while sponsoredId is still null
     * (MessagesController assigns it afterwards), so a NONE result gets cached.
     * With blockSponsorAds on, that same object must still be treated as blocked.
     */
    private static void scenarioA_staleNoneCacheMustNotLetSponsoredAdThrough() throws Exception {
        resetFakes();
        setStatic(config, "blockSponsorAds", true);

        Object mo = newMessageObject();
        // constructor-time query: no sponsoredId yet -> NONE, and it is cached
        assertEquals("pre-sponsor query caches ACTION_NONE",
                rules.getField("ACTION_NONE").getInt(null), filterAction(mo));
        assertTrue("pre-sponsor query is not blocked", !isBlockedMessage(mo));

        // MessagesController.getSponsoredMessages() assigns sponsoredId afterwards
        setField(mo, "sponsoredId", new Object());

        assertTrue("stale ACTION_NONE cache must not let a sponsored ad through",
                isBlockedMessage(mo));
        assertEquals("sponsored ad resolves to ACTION_HIDE",
                rules.getField("ACTION_HIDE").getInt(null), filterAction(mo));
        System.out.println("[PASS] A: stale NONE cache does not unblock sponsored ads");
    }

    /**
     * B. Toggling blockSponsorAds on one and the same object must not leak cached
     * state in either direction: no stale NONE that lets the ad through, no residual
     * HIDE after the switch is turned off. Rule-derived cache entries keep their own
     * result across the toggles.
     */
    private static void scenarioB_adSwitchDoesNotLeakCachedState() throws Exception {
        resetFakes();
        Object ad = newMessageObject();
        filterAction(ad); // constructor-time query, sponsoredId still null -> cached
        setField(ad, "sponsoredId", new Object());

        setStatic(config, "blockSponsorAds", true);
        assertTrue("ad hidden while switch on", isBlockedMessage(ad));
        setStatic(config, "blockSponsorAds", false);
        assertFalse("ad visible again after switch off (no residual cached HIDE)", isBlockedMessage(ad));
        setStatic(config, "blockSponsorAds", true);
        assertTrue("ad hidden again after switch on (no stale NONE cache)", isBlockedMessage(ad));
        setStatic(config, "blockSponsorAds", false);
        assertFalse("ad still visible after a second switch off", isBlockedMessage(ad));

        int hide = rules.getField("ACTION_HIDE").getInt(null);
        for (int rule : new int[]{
                rules.getField("ACTION_SPOILER").getInt(null),
                rules.getField("ACTION_COLLAPSE").getInt(null),
                hide}) {
            resetFakes();
            setStatic(rules, "matchResult", rule);
            Object mo = newMessageObject();
            assertEquals("rule result cached on first query", rule, filterAction(mo));
            setStatic(config, "blockSponsorAds", true);
            assertEquals("rule result kept while ad switch on", rule, filterAction(mo));
            // the very same object is then marked as a sponsored ad (late sponsoredId)
            setField(mo, "sponsoredId", new Object());
            assertEquals("ad switch wins over the cached rule while on", hide, filterAction(mo));
            assertTrue("sponsored message hidden while ad switch on", isBlockedMessage(mo));
            setStatic(config, "blockSponsorAds", false);
            assertEquals("cached rule restored after ad switch off", rule, filterAction(mo));
            assertTrue("blocked iff the rule says HIDE", isBlockedMessage(mo) == (rule == hide));
        }

        // a sponsored message whose own rule says HIDE stays hidden once the ad switch goes off
        resetFakes();
        setStatic(rules, "matchResult", hide);
        Object sponsored = newMessageObject();
        setField(sponsored, "sponsoredId", new Object());
        setStatic(config, "blockSponsorAds", true);
        assertTrue("sponsored message hidden by the ad switch", isBlockedMessage(sponsored));
        setStatic(config, "blockSponsorAds", false);
        assertTrue("rule HIDE still applies after the ad switch is turned off", isBlockedMessage(sponsored));
        System.out.println("[PASS] B: ad switch toggling leaks no cached state");
    }

    /**
     * B'. A sponsored object that has never been queried before (cache never warmed up):
     * first query with the switch on hides it, and turning the switch off must leave no
     * residual ad HIDE - it falls back to the plain rules (NONE here).
     */
    private static void scenarioD_coldCacheLeavesNoResidualAdHide() throws Exception {
        resetFakes();
        setStatic(config, "blockSponsorAds", true);
        Object ad = newMessageObject();
        setField(ad, "sponsoredId", new Object()); // sponsored before the first query

        assertEquals("first query with switch on returns ACTION_HIDE",
                rules.getField("ACTION_HIDE").getInt(null), filterAction(ad));
        assertTrue("cold-cache sponsored ad hidden while switch on", isBlockedMessage(ad));

        setStatic(config, "blockSponsorAds", false);
        assertEquals("no residual ad HIDE once the switch goes off",
                rules.getField("ACTION_NONE").getInt(null), filterAction(ad));
        assertFalse("cold-cache sponsored ad visible again after switch off", isBlockedMessage(ad));
        System.out.println("[PASS] B': cold cache leaves no residual ad HIDE");
    }

    /**
     * C. A message without sponsoredId - e.g. a channel admin's ordinary promo post -
     * must not be hidden by the ad switch alone; it still goes through the normal rules.
     */
    private static void scenarioC_ordinaryPromoPostStaysVisible() throws Exception {
        int none = rules.getField("ACTION_NONE").getInt(null);
        int hide = rules.getField("ACTION_HIDE").getInt(null);

        resetFakes();
        setStatic(config, "blockSponsorAds", true);
        Object promo = newMessageObject();
        promo.getClass().getField("messageText").set(promo, "本周新品上架，欢迎订阅频道");
        assertEquals("ordinary promo post keeps ACTION_NONE with ad switch on", none, filterAction(promo));
        assertFalse("ordinary promo post is not blocked by the ad switch", isBlockedMessage(promo));

        // the very same post is still hidden when a normal rule asks for it
        resetFakes();
        setStatic(config, "blockSponsorAds", true);
        setStatic(rules, "matchResult", hide);
        Object promo2 = newMessageObject();
        promo2.getClass().getField("messageText").set(promo2, "本周新品上架，欢迎订阅频道");
        assertTrue("normal rules still apply to non-sponsored messages", isBlockedMessage(promo2));
        System.out.println("[PASS] C: ordinary promo posts are not hidden by the ad switch");
    }



    // ------------------------------------------------------------------ harness

    private static String extractMethod(String source, String signature) {
        int start = source.indexOf(signature);
        if (start < 0) {
            throw new IllegalStateException("method not found in MessageObject.java: " + signature);
        }
        int open = source.indexOf('{', start);
        int depth = 0;
        boolean line = false, block = false, str = false, chr = false;
        for (int i = open; i < source.length(); i++) {
            char c = source.charAt(i);
            char n = i + 1 < source.length() ? source.charAt(i + 1) : '\0';
            if (line) {
                if (c == '\n') line = false;
                continue;
            }
            if (block) {
                if (c == '*' && n == '/') { block = false; i++; }
                continue;
            }
            if (str) {
                if (c == '\\') i++;
                else if (c == '"') str = false;
                continue;
            }
            if (chr) {
                if (c == '\\') i++;
                else if (c == '\'') chr = false;
                continue;
            }
            if (c == '/' && n == '/') { line = true; i++; continue; }
            if (c == '/' && n == '*') { block = true; i++; continue; }
            if (c == '"') { str = true; continue; }
            if (c == '\'') { chr = true; continue; }
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) return source.substring(start, i + 1);
            }
        }
        throw new IllegalStateException("unbalanced braces for: " + signature);
    }

    private static String fixtureSource(String extracted) {
        return "package fixture;\n"
                + "\n"
                + "import java.util.regex.Pattern;\n"
                + "\n"
                + "/** Test fixture: production bodies pasted in at the marker; everything else is a stub. */\n"
                + "public class MessageObject {\n"
                + "\n"
                + "    // --- fakes for external dependencies (not under test) ---\n"
                + "    public static class TLRPC {\n"
                + "        public static class Peer { public long id; }\n"
                + "        public static class Fwd { public Peer from_id; }\n"
                + "        public static class Message {\n"
                + "            public Fwd fwd_from = new Fwd();\n"
                + "            public long via_bot_id;\n"
                + "            public String via_bot_name;\n"
                + "        }\n"
                + "    }\n"
                + "    public static class MessageFilterRules {\n"
                + "        public static final int ACTION_NONE = 0;\n"
                + "        public static final int ACTION_SPOILER = 1;\n"
                + "        public static final int ACTION_COLLAPSE = 2;\n"
                + "        public static final int ACTION_HIDE = 3;\n"
                + "        public static int matchResult = ACTION_NONE;\n"
                + "        public static int match(CharSequence messageText, CharSequence caption, long senderId, long viaBotId, String viaBotName) {\n"
                + "            return matchResult;\n"
                + "        }\n"
                + "    }\n"
                + "    public static class Config {\n"
                + "        public static boolean blockSponsorAds;\n"
                + "        public static boolean ignoreBlockedUser;\n"
                + "        public static String messageFilter;\n"
                + "        public static String getMessageFilter() { return messageFilter; }\n"
                + "    }\n"
                + "    public static class UserConfig { public static int selectedAccount; }\n"
                + "    public static class TextUtils {\n"
                + "        public static boolean isEmpty(CharSequence s) { return s == null || s.length() == 0; }\n"
                + "    }\n"
                + "    public static class ChatFull { public boolean blocked; }\n"
                + "    public static class MessagesController {\n"
                + "        public static MessagesController instance = new MessagesController();\n"
                + "        public static MessagesController getInstance(int account) { return instance; }\n"
                + "        public ChatFull chatFull;\n"
                + "        public ChatFull getChatFull(long id) { return chatFull; }\n"
                + "        public static class BlockePeers {\n"
                + "            public final java.util.Set<Long> ids = new java.util.HashSet<>();\n"
                + "            public int indexOfKey(long key) { return ids.contains(key) ? 0 : -1; }\n"
                + "        }\n"
                + "        public BlockePeers blockePeers = new BlockePeers();\n"
                + "    }\n"
                + "\n"
                + "    // --- fixture state mirroring MessageObject ---\n"
                + "    public int filterAction = -1;\n"
                + "    public Object sponsoredId;\n"
                + "    public TLRPC.Message messageOwner = new TLRPC.Message();\n"
                + "    public CharSequence messageText;\n"
                + "    public CharSequence caption;\n"
                + "    public long fromChatId;\n"
                + "    public long senderId;\n"
                + "\n"
                + "    public long getFromChatId() { return fromChatId; }\n"
                + "    public long getSenderId() { return senderId; }\n"
                + "    public static long getPeerId(TLRPC.Peer peer) { return peer == null ? 0 : peer.id; }\n"
                + "\n"
                + "    // ===== production methods, extracted verbatim from MessageObject.java =====\n"
                + extracted + "\n"
                + "}\n";
    }

    private static void resetFakes() throws Exception {
        setStatic(config, "blockSponsorAds", false);
        setStatic(config, "ignoreBlockedUser", false);
        setStatic(config, "messageFilter", null);
        setStatic(rules, "matchResult", rules.getField("ACTION_NONE").getInt(null));
    }

    private static Object newMessageObject() throws Exception {
        return messageObject.getDeclaredConstructor().newInstance();
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        messageObject.getField(name).set(target, value);
    }

    private static void setStatic(Class<?> type, String name, Object value) throws Exception {
        type.getField(name).set(null, value);
    }

    private static int filterAction(Object mo) throws Exception {
        Method m = messageObject.getMethod("getFilterAction");
        return (Integer) m.invoke(mo);
    }

    private static boolean isBlockedMessage(Object mo) throws Exception {
        Method m = messageObject.getMethod("isBlockedMessage");
        return (Boolean) m.invoke(mo);
    }

    private static void assertEquals(String label, int expected, int actual) {
        if (expected != actual) {
            throw new AssertionError(label + ": expected " + expected + " but was " + actual);
        }
    }

    private static void assertTrue(String label, boolean value) {
        if (!value) {
            throw new AssertionError(label);
        }
    }

    private static void assertFalse(String label, boolean value) {
        if (value) {
            throw new AssertionError(label);
        }
    }
}
