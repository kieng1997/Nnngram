package xyz.nextalone.nnngram.ui;

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
 * Behaviour regression for the selection action bar's Save counters (upstream fix 65b269dcc,
 * adapted to this fork).
 *
 * <p>Save/Download used to be counted from the chat-wide noforwards flag alone, so a protected
 * document or audio received from a bot (per-message {@code noforwards}) was still offered for
 * saving. Selection and deselection must use the same decision, otherwise the counters drift.</p>
 *
 * <p>The test extracts the <b>actual production bodies</b> of {@code canSaveSelectedMessage}
 * and both save-counter branches (add and remove) from {@code ChatActivity.java}, compiles them
 * into a fixture whose message/model surroundings are fakes, and drives the real decision. The
 * fork switch {@code ignoreChatStrict} is exercised through the production helper it calls.</p>
 *
 * <pre>
 * javac -d TMessagesProj/build/tmp/save-counters/test-classes \
 *     TMessagesProj/src/test/java/xyz/nextalone/nnngram/ui/SaveCountersTest.java
 * java -cp TMessagesProj/build/tmp/save-counters/test-classes \
 *     xyz.nextalone.nnngram.ui.SaveCountersTest .
 * </pre>
 */
public final class SaveCountersTest {

    public static void main(String[] args) throws Exception {
        Path root = Paths.get(args.length > 0 ? args[0] : ".").toAbsolutePath().normalize();
        String chat = new String(Files.readAllBytes(
                root.resolve("TMessagesProj/src/main/java/org/telegram/ui/ChatActivity.java")), StandardCharsets.UTF_8);

        String helper = extractMethod(chat, "private boolean canSaveSelectedMessage(MessageObject messageObject)");
        String removeBranch = counterBranch(chat, "canSaveMusicCount--");
        String addBranch = counterBranch(chat, "canSaveMusicCount++");

        Path work = root.resolve("TMessagesProj/build/tmp/save-counters");
        Path fixtureSrc = work.resolve("fixture-src");
        Path fixtureClasses = work.resolve("fixture-classes");
        Files.createDirectories(fixtureSrc);
        Files.createDirectories(fixtureClasses);
        Path fixtureFile = fixtureSrc.resolve("fixture/SelectionCounters.java");
        Files.createDirectories(fixtureFile.getParent());

        Path eligibility = root.resolve("TMessagesProj/src/main/java/xyz/nextalone/nnngram/helpers/MessageSaveEligibility.java");
        Files.write(fixtureFile, fixtureSource(helper, removeBranch, addBranch, eligibility)
                .getBytes(StandardCharsets.UTF_8));

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException("no JDK compiler on this JVM; run with a JDK, not a JRE");
        }
        ByteArrayOutputStream javacOut = new ByteArrayOutputStream();
        int rc = compiler.run(null, null, new PrintStream(javacOut, true, StandardCharsets.UTF_8),
                "-d", fixtureClasses.toString(), fixtureFile.toString(), eligibility.toString());
        if (rc != 0) {
            throw new IllegalStateException("fixture failed to compile:\n" + javacOut.toString(StandardCharsets.UTF_8));
        }

        URLClassLoader loader = new URLClassLoader(
                new URL[]{fixtureClasses.toUri().toURL()}, SaveCountersTest.class.getClassLoader());
        try {
            Class<?> clazz = loader.loadClass("fixture.SelectionCounters");
            Class<?> messageClass = loader.loadClass("fixture.SelectionCounters$MessageObject");
            // ordinary document: saveable, and deselecting undoes it exactly
            Object ordinary = messageClass.getConstructor(boolean.class, boolean.class, boolean.class, boolean.class)
                    .newInstance(true, false, false, false);
            Object selected = select(clazz, ordinary);
            assertCounts("ordinary document offered for save", selected, 0, 1, 0);
            assertCounts("ordinary document deselected", deselect(clazz, selected, ordinary), 0, 0, 0);

            // bot-protected document: not offered
            Object protectedDoc = messageClass.getConstructor(boolean.class, boolean.class, boolean.class, boolean.class)
                    .newInstance(true, false, true, false);
            Object selectedProtected = select(clazz, protectedDoc);
            assertCounts("protected document not offered for save", selectedProtected, 0, 0, 1);
            assertCounts("protected document deselected symmetrically",
                    deselect(clazz, selectedProtected, protectedDoc), 0, 0, 0);

            // chat-wide restriction: not offered
            Object protectedChat = messageClass.getConstructor(boolean.class, boolean.class, boolean.class, boolean.class)
                    .newInstance(true, true, false, false);
            Object selectedChat = select(clazz, protectedChat);
            assertCounts("chat-wide restriction blocks saving", selectedChat, 0, 0, 1);
            assertCounts("chat-wide restriction deselected symmetrically",
                    deselect(clazz, selectedChat, protectedChat), 0, 0, 0);

            // fork switch lifts both noforwards restrictions
            Class<?> config = loader.loadClass("fixture.SelectionCounters$Config");
            config.getField("ignoreChatStrict").setBoolean(null, true);
            Object lifted = messageClass.getConstructor(boolean.class, boolean.class, boolean.class, boolean.class)
                    .newInstance(true, true, true, false);
            Object selectedLifted = select(clazz, lifted);
            assertCounts("ignoreChatStrict lifts noforwards", selectedLifted, 0, 1, 0);
            assertCounts("ignoreChatStrict deselection symmetrical",
                    deselect(clazz, selectedLifted, lifted), 0, 0, 0);

            // view-once media stays unsaveable even with the switch on
            Object roundOnce = messageClass.getConstructor(boolean.class, boolean.class, boolean.class, boolean.class)
                    .newInstance(true, false, false, true);
            Object selectedRoundOnce = select(clazz, roundOnce);
            assertCounts("round-once media never offered", selectedRoundOnce, 0, 0, 1);
            assertCounts("round-once deselection symmetrical",
                    deselect(clazz, selectedRoundOnce, roundOnce), 0, 0, 0);
        } finally {
            loader.close();
        }
        System.out.println("OK: Save is offered only for saveable selections and the counters stay in sync");
    }

    private static Object select(Class<?> clazz, Object message) throws Exception {
        Object target = clazz.getDeclaredConstructor().newInstance();
        clazz.getMethod("select", message.getClass()).invoke(target, message);
        return target;
    }

    private static Object deselect(Class<?> clazz, Object target, Object message) throws Exception {
        clazz.getMethod("deselect", message.getClass()).invoke(target, message);
        return target;
    }

    private static void assertCounts(String label, Object target, int music, int documents, int cant)
            throws Exception {
        Class<?> clazz = target.getClass();
        int actualMusic = field(clazz, target, "canSaveMusicCount");
        int actualDocuments = field(clazz, target, "canSaveDocumentsCount");
        int actualCant = field(clazz, target, "cantSaveMessagesCount");
        if (actualMusic != music || actualDocuments != documents || actualCant != cant) {
            throw new AssertionError(label + ": expected music=" + music + " documents=" + documents
                    + " cant=" + cant + " but was music=" + actualMusic + " documents=" + actualDocuments
                    + " cant=" + actualCant);
        }
    }

    private static int field(Class<?> clazz, Object target, String name) throws Exception {
        Field field = clazz.getDeclaredField(name);
        field.setAccessible(true);
        return field.getInt(target);
    }

    /** Extracts the whole "if (music) ... else if (document) ... else ..." chain around a save counter. */
    private static String counterBranch(String source, String anchor) {
        int at = source.indexOf(anchor);
        if (at < 0) {
            throw new IllegalStateException("save counter branch not found: " + anchor);
        }
        int start = source.lastIndexOf("if (messageObject.isMusic()", at);
        int cant = source.indexOf("cantSaveMessagesCount", at);
        int close = source.indexOf('}', source.indexOf(';', cant));
        if (start < 0 || cant < 0 || close < 0) {
            throw new IllegalStateException("malformed save counter chain for: " + anchor);
        }
        return source.substring(start, close + 1);
    }

    private static String extractMethod(String source, String signature) {
        int start = source.indexOf(signature);
        if (start < 0) {
            throw new IllegalStateException("method not found: " + signature);
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

    private static String fixtureSource(String helper, String removeBranch, String addBranch, Path eligibility) {
        String eligibilitySource;
        try {
            eligibilitySource = new String(Files.readAllBytes(eligibility), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("cannot read save eligibility helper", e);
        }
        return """
                package fixture;

                import xyz.nextalone.nnngram.helpers.MessageSaveEligibility;

                /** Test fixture: production save decision and counter branches; model fakes elsewhere. */
                public class SelectionCounters {

                    public static class TLRPC {
                        public static class Message { public boolean noforwards; }
                    }

                    public static class MessageObject {
                        public final TLRPC.Message messageOwner = new TLRPC.Message();
                        private final boolean document;
                        private final boolean roundOnce;

                        public MessageObject(boolean document, boolean peerNoForwards, boolean messageNoForwards,
                                             boolean roundOnce) {
                            this.document = document;
                            this.roundOnce = roundOnce;
                            messageOwner.noforwards = messageNoForwards;
                            this.peerNoForwards = peerNoForwards;
                        }

                        public final boolean peerNoForwards;

                        public boolean isMusic() { return false; }
                        public boolean isDocument() { return document; }
                        public boolean isRoundOnce() { return roundOnce; }
                        public boolean isVoiceOnce() { return false; }
                    }

                    /** Mirrors Defines.kt: @BooleanConfig defaults to false. */
                    public static class Config {
                        public static boolean ignoreChatStrict;
                    }

                    public boolean peerNoForwards;
                    public int canSaveMusicCount;
                    public int canSaveDocumentsCount;
                    public int cantSaveMessagesCount;

                    public boolean isPeerNoForwards() { return peerNoForwards; }

                    public void select(MessageObject messageObject) {
                        peerNoForwards = messageObject.peerNoForwards;
                        addSaveCounters(messageObject);
                    }

                    public void deselect(MessageObject messageObject) {
                        peerNoForwards = messageObject.peerNoForwards;
                        removeSaveCounters(messageObject);
                    }

                    // ===== production method, extracted verbatim from ChatActivity.java =====
                """ + helper + """

                    private void addSaveCounters(MessageObject messageObject) {
                """ + addBranch + """
                    }

                    private void removeSaveCounters(MessageObject messageObject) {
                """ + removeBranch + """
                    }
                }
                """;
    }
}
