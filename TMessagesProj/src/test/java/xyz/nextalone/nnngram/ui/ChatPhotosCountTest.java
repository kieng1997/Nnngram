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
 * Behaviour regression for removed chat photos in the profile gallery (upstream fix 98831ad3c).
 *
 * <p>For groups and channels the photo history comes from {@code messages.search} with
 * {@code inputMessagesFilterChatPhotos}. The results also include
 * {@code messageActionChatDeletePhoto} service messages, which carry no photo; they were
 * skipped but still counted, so the gallery made a page for each one and showed black or
 * loading images.</p>
 *
 * <p>The test extracts the <b>actual production block</b> from
 * {@code MessagesController.getChatPhotos(...)}'s response handler verbatim and compiles it
 * into a fixture whose TLRPC classes are fakes. The production code is not rewritten. The
 * pagination rule matters: only a response that holds the whole history may adjust the count,
 * otherwise page totals differ and {@code onLoaded()} rebuilds the list, dropping photos.</p>
 *
 * <pre>
 * javac -d TMessagesProj/build/tmp/chat-photos/test-classes \
 *     TMessagesProj/src/test/java/xyz/nextalone/nnngram/ui/ChatPhotosCountTest.java
 * java -cp TMessagesProj/build/tmp/chat-photos/test-classes \
 *     xyz.nextalone.nnngram.ui.ChatPhotosCountTest .
 * </pre>
 */
public final class ChatPhotosCountTest {

    private static final String START = "TLRPC.photos_Photos res = new TLRPC.TL_photos_photos();";
    private static final String END = "onLoaded(offset, count, res);";

    public static void main(String[] args) throws Exception {
        Path root = Paths.get(args.length > 0 ? args[0] : ".").toAbsolutePath().normalize();
        Path production = root.resolve("TMessagesProj/src/main/java/org/telegram/messenger/MessagesController.java");
        String source = new String(Files.readAllBytes(production), StandardCharsets.UTF_8);
        String block = extractBlock(source, START, END);

        Path work = root.resolve("TMessagesProj/build/tmp/chat-photos");
        Path fixtureSrc = work.resolve("fixture-src");
        Path fixtureClasses = work.resolve("fixture-classes");
        Files.createDirectories(fixtureSrc);
        Files.createDirectories(fixtureClasses);
        Path fixtureFile = fixtureSrc.resolve("fixture/ChatPhotosFixture.java");
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
                new URL[]{fixtureClasses.toUri().toURL()}, ChatPhotosCountTest.class.getClassLoader());
        try {
            Class<?> fixture = loader.loadClass("fixture.ChatPhotosFixture");
            Class<?> messagesClass = loader.loadClass("fixture.ChatPhotosFixture$Messages");
            Class<?> messageClass = loader.loadClass("fixture.ChatPhotosFixture$TLRPC$Message");
            Class<?> actionClass = loader.loadClass("fixture.ChatPhotosFixture$Action");
            Class<?> photoClass = loader.loadClass("fixture.ChatPhotosFixture$Photo");
            Class<?> typedPhotoClass = loader.loadClass("fixture.ChatPhotosFixture$TLRPC$TL_photo");

            Method handle = fixture.getMethod("handle", messagesClass, int.class, int.class);

            // A. one response holding the whole history: a deleted-photo service message is not counted.
            Object page = messagesClass.getDeclaredConstructor().newInstance();
            set(messagesClass, page, "count", 3);
            addMessage(messagesClass, messageClass, actionClass, photoClass, typedPhotoClass, page, true);
            addMessage(messagesClass, messageClass, actionClass, photoClass, typedPhotoClass, page, false);
            addMessage(messagesClass, messageClass, actionClass, photoClass, typedPhotoClass, page, true);
            handle.invoke(fixture.getDeclaredConstructor().newInstance(), page, 0, 100);
            assertEquals("photos kept", 2, (int) read(fixture, "lastPhotos"));
            assertEquals("deleted photo subtracted once", 2, (int) read(fixture, "lastCount"));

            // B. paginated history (page smaller than the total): the count must stay untouched,
            //    otherwise pages disagree and onLoaded() rebuilds the list.
            Object partialPage = messagesClass.getDeclaredConstructor().newInstance();
            set(messagesClass, partialPage, "count", 100);
            addMessage(messagesClass, messageClass, actionClass, photoClass, typedPhotoClass, partialPage, true);
            addMessage(messagesClass, messageClass, actionClass, photoClass, typedPhotoClass, partialPage, false);
            handle.invoke(fixture.getDeclaredConstructor().newInstance(), partialPage, 0, 100);
            assertEquals("paginated count untouched", 100, (int) read(fixture, "lastCount"));

            // C. a later page (offset > 0) that happens to be complete must not adjust the count either.
            Object laterPage = messagesClass.getDeclaredConstructor().newInstance();
            set(messagesClass, laterPage, "count", 2);
            addMessage(messagesClass, messageClass, actionClass, photoClass, typedPhotoClass, laterPage, true);
            addMessage(messagesClass, messageClass, actionClass, photoClass, typedPhotoClass, laterPage, false);
            handle.invoke(fixture.getDeclaredConstructor().newInstance(), laterPage, 1, 100);
            assertEquals("later page count untouched", 2, (int) read(fixture, "lastCount"));

            // D. a chat photo that is not a TL_photo object is skipped and not counted.
            Object weirdPage = messagesClass.getDeclaredConstructor().newInstance();
            set(messagesClass, weirdPage, "count", 1);
            Object weird = messageClass.getDeclaredConstructor().newInstance();
            Object weirdAction = actionClass.getDeclaredConstructor().newInstance();
            actionClass.getField("photo").set(weirdAction, photoClass.getDeclaredConstructor().newInstance());
            messageClass.getField("action").set(weird, weirdAction);
            ((java.util.List<Object>) messagesClass.getField("messages").get(weirdPage)).add(weird);
            handle.invoke(fixture.getDeclaredConstructor().newInstance(), weirdPage, 0, 100);
            assertEquals("non-photo payload skipped", 0, (int) read(fixture, "lastPhotos"));
            assertEquals("non-photo payload subtracted", 0, (int) read(fixture, "lastCount"));
        } finally {
            loader.close();
        }
        System.out.println("OK: removed chat photos are neither listed nor counted");
    }

    private static void addMessage(Class<?> messagesClass, Class<?> messageClass, Class<?> actionClass,
                                   Class<?> photoClass, Class<?> typedPhotoClass, Object page, boolean withPhoto)
            throws Exception {
        Object message = messageClass.getDeclaredConstructor().newInstance();
        Object action = actionClass.getDeclaredConstructor().newInstance();
        if (withPhoto) {
            actionClass.getField("photo").set(action, typedPhotoClass.getDeclaredConstructor().newInstance());
        }
        messageClass.getField("action").set(message, action);
        ((java.util.List<Object>) messagesClass.getField("messages").get(page)).add(message);
    }

    private static void set(Class<?> type, Object target, String field, int value) throws Exception {
        type.getField(field).set(target, value);
    }

    private static Object read(Class<?> fixture, String field) throws Exception {
        return fixture.getField(field).get(null);
    }

    private static String extractBlock(String source, String start, String end) {
        int from = source.indexOf(start);
        if (from < 0) {
            throw new IllegalStateException("block start not found: " + start);
        }
        int to = source.indexOf(end, from);
        if (to < 0) {
            throw new IllegalStateException("block end not found: " + end);
        }
        return source.substring(from, to + end.length());
    }

    private static String fixtureSource(String block) {
        return """
                package fixture;

                import java.util.ArrayList;
                import java.util.List;

                /** Test fixture: the production block pasted in handle(); TLRPC types are fakes. */
                public class ChatPhotosFixture {

                    public static class Photo { }

                    public static class Action { public Photo photo; }

                    public static class Messages {
                        public final List<TLRPC.Message> messages = new ArrayList<>();
                        public int count;
                    }

                    public static class TLRPC {
                        public static class TL_photo extends Photo { }
                        public static class Message { public Action action; }
                        public static class photos_Photos {
                            public int count;
                            public final List<Photo> photos = new ArrayList<>();
                        }
                        public static class TL_photos_photos extends photos_Photos { }
                    }

                    public static int lastCount = -1;
                    public static int lastPhotos = -1;

                    private void onLoaded(int offset, int count, TLRPC.photos_Photos res) {
                        lastCount = res.count;
                        lastPhotos = res.photos.size();
                    }

                    // ===== production block, extracted verbatim from MessagesController.java =====
                    public void handle(Messages messages, int offset, int count) {
                """ + block + "\n    }\n}\n";
    }

    private static void assertEquals(String label, int expected, int actual) {
        if (expected != actual) {
            throw new AssertionError(label + ": expected " + expected + " but was " + actual);
        }
    }
}
