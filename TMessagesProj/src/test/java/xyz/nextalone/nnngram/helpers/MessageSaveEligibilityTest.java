package xyz.nextalone.nnngram.helpers;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Behaviour test for {@link MessageSaveEligibility}, the fork-side decision behind the
 * selection action bar's Save action (upstream fix 65b269dcc, adapted to this fork).
 *
 * <p>Two independent restrictions must both hide Save: the chat-wide noforwards flag and the
 * per-message {@code noforwards} flag (bot protect_content). The fork setting
 * {@code ignoreChatStrict} lifts both, but view-once media (round video, voice) must stay
 * unsaveable — the fork still offers those save paths elsewhere.</p>
 *
 * <p>The production class is compiled and executed as-is against a plain JDK; no Android
 * runtime and no mocks are involved.</p>
 *
 * <pre>
 * javac -d TMessagesProj/build/tmp/save-eligibility/test-classes \
 *     TMessagesProj/src/test/java/xyz/nextalone/nnngram/helpers/MessageSaveEligibilityTest.java
 * java -cp TMessagesProj/build/tmp/save-eligibility/test-classes \
 *     xyz.nextalone.nnngram.helpers.MessageSaveEligibilityTest .
 * </pre>
 */
public final class MessageSaveEligibilityTest {

    public static void main(String[] args) throws Exception {
        Path root = Paths.get(args.length > 0 ? args[0] : ".").toAbsolutePath().normalize();
        Path production = root.resolve(
                "TMessagesProj/src/main/java/xyz/nextalone/nnngram/helpers/MessageSaveEligibility.java");

        Path classes = root.resolve("TMessagesProj/build/tmp/save-eligibility/classes");
        Files.createDirectories(classes);

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException("no JDK compiler on this JVM; run with a JDK, not a JRE");
        }
        ByteArrayOutputStream javacOut = new ByteArrayOutputStream();
        int rc = compiler.run(null, null, new PrintStream(javacOut, true, StandardCharsets.UTF_8),
                "-d", classes.toString(), production.toString());
        if (rc != 0) {
            throw new IllegalStateException("production class failed to compile:\n" + javacOut.toString(StandardCharsets.UTF_8));
        }

        URLClassLoader loader = new URLClassLoader(
                new URL[]{classes.toUri().toURL()}, MessageSaveEligibilityTest.class.getClassLoader());
        try {
            Class<?> eligibility = loader.loadClass("xyz.nextalone.nnngram.helpers.MessageSaveEligibility");
            Method canSave = eligibility.getMethod("canSave",
                    boolean.class, boolean.class, boolean.class, boolean.class, boolean.class);

            // ordinary message in an ordinary chat
            assertSave("ordinary document", true, canSave, false, false, false, false, false);
            // chat-wide restriction alone
            assertSave("chat-wide noforwards", false, canSave, false, true, false, false, false);
            // per-message restriction alone (a bot's protect_content)
            assertSave("message noforwards", false, canSave, false, false, true, false, false);
            // both restrictions
            assertSave("both noforwards flags", false, canSave, false, true, true, false, false);
            // fork switch lifts both noforwards restrictions
            assertSave("ignoreChatStrict lifts chat-wide", true, canSave, true, true, false, false, false);
            assertSave("ignoreChatStrict lifts per-message", true, canSave, true, false, true, false, false);
            assertSave("ignoreChatStrict lifts both", true, canSave, true, true, true, false, false);
            // view-once media stays unsaveable, even with the switch on
            assertSave("round video stays unsaveable", false, canSave, false, false, false, true, false);
            assertSave("voice message stays unsaveable", false, canSave, false, false, false, false, true);
            assertSave("round video unsaveable with ignoreChatStrict", false, canSave, true, true, true, true, false);
            assertSave("voice unsaveable with ignoreChatStrict", false, canSave, true, true, true, false, true);
        } finally {
            loader.close();
        }
        System.out.println("OK: save eligibility honours both noforwards flags and keeps view-once media unsaveable");
    }

    private static void assertSave(String label, boolean expected, Method canSave, boolean ignoreChatStrict,
                                   boolean peerNoForwards, boolean messageNoForwards, boolean roundOnce,
                                   boolean voiceOnce) throws Exception {
        boolean actual = (Boolean) canSave.invoke(null, ignoreChatStrict, peerNoForwards, messageNoForwards,
                roundOnce, voiceOnce);
        if (actual != expected) {
            throw new AssertionError(label + ": expected canSave=" + expected + " but was " + actual);
        }
    }
}
