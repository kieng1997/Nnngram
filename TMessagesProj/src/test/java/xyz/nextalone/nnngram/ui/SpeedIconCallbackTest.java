package xyz.nextalone.nnngram.ui;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Behaviour regression for the playback-speed icon lagging one step behind in release builds
 * (upstream fix fe8d38266).
 *
 * <p>{@code Drawable.setCallback()} keeps the callback through a {@code WeakReference}, and R8
 * removes a private callback field that is only assigned, never read. After a GC the text
 * drawable has no callback, cannot request a redraw, and the icon keeps showing the previous
 * speed while the toast shows the new one. The fix makes the drawable its own callback, which
 * the view already references strongly.</p>
 *
 * <p>The test extracts the <b>actual production bodies</b> from
 * {@code SpeedIconDrawable.java}: the constructor (which registers the callback) and the three
 * {@code Drawable.Callback} methods. Those bodies are compiled verbatim into a fixture that
 * mirrors Android's weak callback reference and the release-build field removal (R8). The
 * production code is not rewritten; only its surroundings are stubbed. This is a modelled
 * lifetime test: no real R8, no real draw pass.</p>
 *
 * <pre>
 * javac -d TMessagesProj/build/tmp/speed-icon/test-classes \
 *     TMessagesProj/src/test/java/xyz/nextalone/nnngram/ui/SpeedIconCallbackTest.java
 * java -cp TMessagesProj/build/tmp/speed-icon/test-classes \
 *     xyz.nextalone.nnngram.ui.SpeedIconCallbackTest .
 * </pre>
 *
 * The optional argument is the repository root (default: current directory).
 */
public final class SpeedIconCallbackTest {

    public static void main(String[] args) throws Exception {
        Path root = Paths.get(args.length > 0 ? args[0] : ".").toAbsolutePath().normalize();
        Path production = root.resolve("TMessagesProj/src/main/java/org/telegram/ui/Components/SpeedIconDrawable.java");
        String source = new String(Files.readAllBytes(production), StandardCharsets.UTF_8);

        boolean makesItselfCallback = source.contains("class SpeedIconDrawable extends Drawable implements Drawable.Callback");
        String constructor = extractMethod(source, "public SpeedIconDrawable(boolean outline)");
        String invalidate = extractMethod(source, "public void invalidateDrawable(@NonNull Drawable who)");
        String schedule = extractMethod(source, "public void scheduleDrawable(@NonNull Drawable who, @NonNull Runnable what, long when)");
        String unschedule = extractMethod(source, "public void unscheduleDrawable(@NonNull Drawable who, @NonNull Runnable what)");

        Path work = root.resolve("TMessagesProj/build/tmp/speed-icon");
        Path fixtureSrc = work.resolve("fixture-src");
        Path fixtureClasses = work.resolve("fixture-classes");
        Files.createDirectories(fixtureSrc);
        Files.createDirectories(fixtureClasses);
        Path fixtureFile = fixtureSrc.resolve("fixture/SpeedIconFixture.java");
        Files.createDirectories(fixtureFile.getParent());
        Files.write(fixtureFile, fixtureSource(makesItselfCallback, constructor, invalidate, schedule, unschedule)
                .getBytes(StandardCharsets.UTF_8));

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
                new URL[]{fixtureClasses.toUri().toURL()}, SpeedIconCallbackTest.class.getClassLoader());
        try {
            Class<?> fixture = loader.loadClass("fixture.SpeedIconFixture");
            Class<?> drawableClass = loader.loadClass("fixture.SpeedIconFixture$SpeedIconDrawable");

            // The view keeps the drawable alive; nothing else references the callback object.
            Object speedIcon = drawableClass.getDeclaredConstructor(boolean.class).newInstance(true);

            // Release build: R8 drops the never-read private callback field.
            fixture.getDeclaredMethod("dropCallbackField", Object.class).invoke(null, speedIcon);

            // Let the GC clear a callback that only the weak reference still points to.
            WeakReference<?> callbackRef = (WeakReference<?>) fixture.getDeclaredMethod("lastCallback").invoke(null);
            Method collect = fixture.getDeclaredMethod("collectGarbage");
            for (int i = 0; i < 10 && callbackRef.get() != null; i++) {
                collect.invoke(null);
            }
            boolean callbackSurvived = callbackRef.get() != null;

            // The speed changed: the text drawable must still reach a callback to request a redraw.
            fixture.getDeclaredMethod("setText", Object.class, int.class).invoke(null, speedIcon, 2);
            int invalidations = (Integer) fixture.getDeclaredMethod("invalidations").invoke(null);

            if (!callbackSurvived) {
                throw new AssertionError("after the release build drops the callback field, the GC cleared the"
                        + " text drawable's callback, so the speed icon cannot redraw and keeps showing the"
                        + " previous speed (redraw requests: " + invalidations + ")");
            }
            if (invalidations == 0) {
                throw new AssertionError("the speed text drawable no longer requests a redraw on a speed change");
            }
        } finally {
            loader.close();
        }
        System.out.println("OK: the speed icon redraws on every speed change");
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

    private static String fixtureSource(boolean makesItselfCallback, String constructor,
                                        String invalidate, String schedule, String unschedule) {
        String callbackMethods = makesItselfCallback ? invalidate + "\n" + schedule + "\n" + unschedule : "";
        String callbackField = constructor.contains("textDrawable.setCallback(callback)")
                ? """
                        private Callback callback = new Callback() {
                            public void invalidateDrawable(Drawable who) { SpeedIconDrawable.this.invalidateSelf(); }
                            public void scheduleDrawable(Drawable who, Runnable what, long when) { SpeedIconDrawable.this.scheduleSelf(what, when); }
                            public void unscheduleDrawable(Drawable who, Runnable what) { SpeedIconDrawable.this.unscheduleSelf(what); }
                        };
                    """ : "";

        return """
                package fixture;

                import java.lang.ref.WeakReference;

                /** Test fixture: production constructor and callback bodies pasted at the markers. */
                public final class SpeedIconFixture {

                    private static WeakReference<Callback> lastCallback;
                    private static int invalidations;

                    public static int invalidations() { return invalidations; }
                    public static WeakReference<Callback> lastCallback() { return lastCallback; }

                    /** R8 drops the field because it is only assigned in the constructor, never read. */
                    public static void dropCallbackField(Object speedIcon) throws Exception {
                        for (java.lang.reflect.Field field : SpeedIconDrawable.class.getDeclaredFields()) {
                            if (field.getName().equals("callback")) {
                                field.setAccessible(true);
                                field.set(speedIcon, null);
                                return;
                            }
                        }
                    }

                    public static void collectGarbage() { System.gc(); }

                    public static void setText(Object speedIcon, int speed) throws Exception {
                        SpeedIconDrawable.class.getDeclaredMethod("setSpeedText", int.class).invoke(speedIcon, speed);
                    }

                    public static class Drawable {
                        public void invalidateSelf() { invalidations++; }
                        public void scheduleSelf(Runnable what, long when) { }
                        public void unscheduleSelf(Runnable what) { }
                    }

                    public interface Callback {
                        void invalidateDrawable(Drawable who);
                        void scheduleDrawable(Drawable who, Runnable what, long when);
                        void unscheduleDrawable(Drawable who, Runnable what);
                    }

                    /** Android keeps the callback weakly: a collected callback silently stops updates. */
                    public static class TextDrawable {
                        private WeakReference<Callback> callback;
                        void setCallback(Callback callback) {
                            this.callback = new WeakReference<>(callback);
                            lastCallback = this.callback;
                        }
                        void setText(int speed) {
                            Callback cb = callback == null ? null : callback.get();
                            if (cb != null) cb.invalidateDrawable(null);
                        }
                    }

                    public static class AnimatedTextView {
                        public static class AnimatedTextDrawable extends TextDrawable {
                            private int textSize;
                            private final Paint paint = new Paint(0);
                            private String text = "";
                            public AnimatedTextDrawable(boolean one, boolean two, boolean three) { }
                            public void setAnimationProperties(float a, long b, long c, Object interpolator) { }
                            public void setGravity(int gravity) { }
                            public void setTypeface(Object typeface) { }
                            public void setTextSize(int size) { textSize = size; }
                            public Paint getPaint() { return paint; }
                            public void cancelAnimation() { }
                            public String getText() { return text; }
                            public void setText(String text, boolean animated) { this.text = text; }
                        }
                    }

                    public static class Paint {
                        public static final int ANTI_ALIAS_FLAG = 1;
                        public static class Style {
                            public static final int FILL_AND_STROKE = 0;
                            public static final int STROKE = 1;
                        }
                        public Paint(int flags) { }
                        public void setStyle(int style) { }
                        public void setStrokeWidth(float width) { }
                    }

                    public static class AndroidUtilities {
                        public static int dp(float value) { return (int) value; }
                        public static float dpf2(float value) { return value; }
                        public static Object bold() { return null; }
                    }

                    public static class Gravity {
                        public static final int CENTER_HORIZONTAL = 1;
                    }

                    public static class TextUtils {
                        public static boolean isEmpty(CharSequence s) { return s == null || s.length() == 0; }
                    }

                    public static class CubicBezierInterpolator {
                        public static final Object EASE_OUT_QUINT = new Object();
                    }

                    public @interface NonNull { }

                    public @interface Nullable { }

                """ + "    public static class SpeedIconDrawable extends Drawable"
                + (makesItselfCallback ? " implements Callback" : "") + " {\n"
                + """
                        private final AnimatedTextView.AnimatedTextDrawable textDrawable;
                    """ + callbackField + """
                        private final Paint outlinePaint;

                    """
                + constructor + "\n" + callbackMethods + """

                        public void setSpeedText(int speed) { textDrawable.setText(speed); }
                    }
                }
                """;
    }
}
