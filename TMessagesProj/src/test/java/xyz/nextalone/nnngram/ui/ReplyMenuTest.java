package xyz.nextalone.nnngram.ui;

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
 * Behaviour regression for replying to protected messages from the message menu (upstream fix
 * 5441fe422).
 *
 * <p>Tapping a message sent with protect_content (or any message with the noforwards flag) and
 * choosing "Reply" did nothing: the guard returned early for every noforwards message, while
 * swipe-to-reply and the selection action bar already replied to the same messages. The guard
 * belongs to the "reply in another chat" flow only, because quoting a protected message
 * elsewhere would leak its content.</p>
 *
 * <p>The test extracts the <b>actual production body</b> of {@code case OPTION_REPLY} from
 * {@code ChatActivity.java} and compiles it into a fixture where the three observable outcomes
 * are recorded: opening the in-chat reply panel, opening the cross-chat picker, or doing
 * nothing. Navigation UI and message objects are fakes; the decision code is production.</p>
 *
 * <pre>
 * javac -d TMessagesProj/build/tmp/reply-menu/test-classes \
 *     TMessagesProj/src/test/java/xyz/nextalone/nnngram/ui/ReplyMenuTest.java
 * java -cp TMessagesProj/build/tmp/reply-menu/test-classes \
 *     xyz.nextalone.nnngram.ui.ReplyMenuTest .
 * </pre>
 */
public final class ReplyMenuTest {

    public static void main(String[] args) throws Exception {
        Path root = Paths.get(args.length > 0 ? args[0] : ".").toAbsolutePath().normalize();
        String source = new String(Files.readAllBytes(
                root.resolve("TMessagesProj/src/main/java/org/telegram/ui/ChatActivity.java")), StandardCharsets.UTF_8);
        String block = extractCase(source);

        Path work = root.resolve("TMessagesProj/build/tmp/reply-menu");
        Path fixtureSrc = work.resolve("fixture-src");
        Path fixtureClasses = work.resolve("fixture-classes");
        Files.createDirectories(fixtureSrc);
        Files.createDirectories(fixtureClasses);
        Path fixtureFile = fixtureSrc.resolve("fixture/ChatActivity.java");
        Files.createDirectories(fixtureFile.getParent());
        Files.write(fixtureFile, fixtureSource(block).getBytes(StandardCharsets.UTF_8));

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
                new URL[]{fixtureClasses.toUri().toURL()}, ReplyMenuTest.class.getClassLoader());
        try {
            Class<?> clazz = loader.loadClass("fixture.ChatActivity");
            Class<?> messageClass = loader.loadClass("fixture.ChatActivity$MessageObject");
            Class<?> chatClass = loader.loadClass("fixture.ChatActivity$Chat");

            // ordinary chat, ordinary message: the reply panel opens
            assertOutcome("ordinary in-chat reply", "FIELD_PANEL", clazz, messageClass, chatClass, false, false, false);
            // ordinary chat, protected message: the reply panel opens too (the regression)
            assertOutcome("in-chat reply to a protected message", "FIELD_PANEL", clazz, messageClass, chatClass, true, false, false);
            // read-only chat, ordinary message: the cross-chat picker opens
            assertOutcome("reply in another chat", "CROSS_CHAT", clazz, messageClass, chatClass, false, true, false);
            // read-only chat, protected message: nothing happens, quoting would leak the content
            assertOutcome("protected message in another chat", "NONE", clazz, messageClass, chatClass, true, true, false);
        } finally {
            loader.close();
        }
        System.out.println("OK: protected messages reply in-chat but never quote into another chat");
    }

    private static void assertOutcome(String label, String expected, Class<?> clazz, Class<?> messageClass,
                                      Class<?> chatClass, boolean noforwards, boolean crossChatOnly,
                                      boolean unused) throws Exception {
        Object activity = clazz.getDeclaredConstructor().newInstance();
        Object message = messageClass.getDeclaredConstructor(boolean.class).newInstance(noforwards);
        Object chat = chatClass.getDeclaredConstructor(boolean.class).newInstance(crossChatOnly);
        clazz.getField("selectedObject").set(activity, message);
        clazz.getField("currentChat").set(activity, chat);
        clazz.getField("outcome").set(activity, "NONE");

        Method process = clazz.getDeclaredMethod("selectReply");
        process.setAccessible(true);
        process.invoke(activity);

        String actual = (String) clazz.getField("outcome").get(activity);
        if (!expected.equals(actual)) {
            throw new AssertionError(label + ": expected " + expected + " but was " + actual);
        }
    }

    private static String extractCase(String source) {
        int start = source.indexOf("case OPTION_REPLY: {");
        if (start < 0) {
            throw new IllegalStateException("OPTION_REPLY case not found in ChatActivity.java");
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
                if (depth == 0) {
                    // keep the case label and its block, drop the trailing break
                    String body = source.substring(open + 1, i);
                    int breakAt = body.lastIndexOf("break;");
                    return breakAt >= 0 ? body.substring(0, breakAt) : body;
                }
            }
        }
        throw new IllegalStateException("unbalanced braces for OPTION_REPLY");
    }

    private static String fixtureSource(String block) {
        return """
                package fixture;

                /** Test fixture: the production OPTION_REPLY body runs as selectReply(); the rest is fake. */
                public class ChatActivity {

                    public static class TLRPC { public static class Message { public boolean noforwards; } }

                    public static class MessageObject {
                        public final TLRPC.Message messageOwner = new TLRPC.Message();
                        public MessageObject(boolean noforwards) { messageOwner.noforwards = noforwards; }
                        public long getGroupId() { return 0; }
                        public Object getFromPeer() { return null; }
                        public static class GroupedMessages { public MessageObject captionMessage; }
                    }

                    public static class Chat {
                        public final boolean crossChatOnly;
                        public final boolean megagroup = false;
                        public Chat(boolean crossChatOnly) { this.crossChatOnly = crossChatOnly; }
                    }

                    public static class ChatObject {
                        public static boolean isNotInChat(Chat chat) { return chat.crossChatOnly; }
                        public static boolean isMonoForum(Chat chat) { return false; }
                        public static boolean isChannel(Chat chat) { return false; }
                        public static boolean canPost(Chat chat) { return true; }
                        public static boolean canSendMessages(Chat chat) { return !chat.crossChatOnly; }
                    }

                    public static class Bundle {
                        public void putBoolean(String key, boolean value) { }
                        public void putInt(String key, int value) { }
                        public void putLong(String key, long value) { }
                    }

                    public static class DialogsActivity {
                        public static final int DIALOGS_TYPE_FORWARD = 1;
                        public final Bundle args;
                        public DialogsActivity(Bundle args) { this.args = args; }
                        public void setDelegate(ChatActivity activity) { }
                    }

                    public static class DialogObject {
                        public static long getPeerDialogId(Object peer) { return 0; }
                    }

                    public static class UserConfig {
                        public long getClientUserId() { return 1; }
                    }

                    public MessageObject selectedObject;
                    public Chat currentChat;
                    public MessageObject replyingMessageObject;
                    public String outcome = "NONE";

                    public boolean isThreadChat() { return false; }
                    public MessageObject.GroupedMessages getGroup(long id) { return null; }
                    public long getDialogId() { return 2; }
                    public UserConfig getUserConfig() { return new UserConfig(); }
                    public void presentFragment(DialogsActivity fragment) { outcome = "CROSS_CHAT"; }
                    public void showFieldPanelForReply(MessageObject message) { outcome = "FIELD_PANEL"; }

                    // ===== production case body, extracted verbatim from ChatActivity.java =====
                    public void selectReply() {
                        if (false) { }
                """ + block + """
                    }
                }
                """;
    }
}
