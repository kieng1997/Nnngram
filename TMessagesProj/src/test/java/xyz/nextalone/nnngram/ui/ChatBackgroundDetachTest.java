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
 * Behaviour regression for detached views staying referenced by {@code ChatBackgroundDrawable}
 * (upstream fix 4e2871450).
 *
 * <p>{@code onDetachedFromWindow()} only removed the view when it was <i>not</i> in the list, so
 * a view was never removed. Every view that showed a chat background therefore kept its whole
 * chat view tree alive, which shows up when a chat with a custom background is rebuilt (e.g.
 * folding a foldable).</p>
 *
 * <p>The test extracts the <b>actual production body</b> of {@code onDetachedFromWindow} and of
 * its {@code isAttached()} helper from {@code ChatBackgroundDrawable.java} and compiles them
 * into a fixture whose Android surroundings (View, ImageReceiver, MotionBackgroundDrawable) are
 * fakes. The production code is not rewritten. This does not exercise a real view hierarchy.</p>
 *
 * <pre>
 * javac -d TMessagesProj/build/tmp/chat-background/test-classes \
 *     TMessagesProj/src/test/java/xyz/nextalone/nnngram/ui/ChatBackgroundDetachTest.java
 * java -cp TMessagesProj/build/tmp/chat-background/test-classes \
 *     xyz.nextalone.nnngram.ui.ChatBackgroundDetachTest .
 * </pre>
 */
public final class ChatBackgroundDetachTest {

    public static void main(String[] args) throws Exception {
        Path root = Paths.get(args.length > 0 ? args[0] : ".").toAbsolutePath().normalize();
        Path production = root.resolve("TMessagesProj/src/main/java/org/telegram/ui/ChatBackgroundDrawable.java");
        String source = new String(Files.readAllBytes(production), StandardCharsets.UTF_8);

        String detach = extractMethod(source, "public void onDetachedFromWindow(View view)");
        String attach = extractMethod(source, "public void onAttachedToWindow(View view)");
        String isAttached = extractMethod(source, "private boolean isAttached()");

        Path work = root.resolve("TMessagesProj/build/tmp/chat-background");
        Path fixtureSrc = work.resolve("fixture-src");
        Path fixtureClasses = work.resolve("fixture-classes");
        Files.createDirectories(fixtureSrc);
        Files.createDirectories(fixtureClasses);
        Path fixtureFile = fixtureSrc.resolve("fixture/ChatBackgroundDrawable.java");
        Files.createDirectories(fixtureFile.getParent());
        Files.write(fixtureFile, fixtureSource(attach, detach, isAttached).getBytes(StandardCharsets.UTF_8));

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
                new URL[]{fixtureClasses.toUri().toURL()}, ChatBackgroundDetachTest.class.getClassLoader());
        try {
            Class<?> background = loader.loadClass("fixture.ChatBackgroundDrawable");
            Class<?> viewClass = loader.loadClass("fixture.ChatBackgroundDrawable$View");
            Object drawable = background.getDeclaredConstructor().newInstance();
            Object firstView = viewClass.getDeclaredConstructor(String.class).newInstance("first");
            Object secondView = viewClass.getDeclaredConstructor(String.class).newInstance("second");

            Method attachView = background.getMethod("onAttachedToWindow", viewClass);
            Method detachView = background.getMethod("onDetachedFromWindow", viewClass);

            attachView.invoke(drawable, firstView);
            attachView.invoke(drawable, secondView);

            // a chat is rebuilt: the old view is detached while the new one stays attached.
            // The receiver is the observable side effect: it must survive while a view remains.
            detachView.invoke(drawable, firstView);
            if (loadedViews(background, drawable) != 1) {
                throw new AssertionError("the image receiver must stay attached while a view is still attached");
            }

            detachView.invoke(drawable, secondView);
            if (loadedViews(background, drawable) != 0) {
                throw new AssertionError("the image receiver must be detached once the last view is gone");
            }
        } finally {
            loader.close();
        }
        System.out.println("OK: detached views no longer stay referenced by the chat background");
    }

    private static int loadedViews(Class<?> background, Object drawable) throws Exception {
        Object receiver = background.getField("imageReceiver").get(drawable);
        return receiver.getClass().getField("loadedViews").getInt(receiver);
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

    private static String fixtureSource(String attach, String detach, String isAttached) {
        return """
                package fixture;

                import java.util.ArrayList;

                /** Test fixture: production bodies pasted at the markers; everything else is a stub. */
                public class ChatBackgroundDrawable {

                    public static class View {
                        public final String name;
                        public View(String name) { this.name = name; }
                        @Override public String toString() { return name; }
                    }

                    public static class ImageReceiver {
                        public int loadedViews;
                        public void onAttachedToWindow() { loadedViews++; }
                        public void onDetachedFromWindow() { loadedViews--; }
                    }

                    public static class MotionBackgroundDrawable {
                        public void onAttachedToWindow() { }
                        public void onDetachedFromWindow() { }
                    }

                    public boolean attached;
                    private final ArrayList<View> attachedViews = new ArrayList<>();
                    public final ImageReceiver imageReceiver = new ImageReceiver();
                    private final MotionBackgroundDrawable motionBackgroundDrawable = new MotionBackgroundDrawable();

                    // ===== production methods, extracted verbatim from ChatBackgroundDrawable.java =====
                """ + attach + "\n" + isAttached + "\n" + detach + "\n}\n";
    }

}
