package xyz.nextalone.nnngram.ui;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Behaviour regression for adaptive dialogs on ROMs whose dialog theme leaves
 * {@code windowEnterAnimation}/{@code windowExitAnimation} unset (upstream fix 3132eec80,
 * {@code Resources$NotFoundException: Resource ID #0xffffffff} when opening Create Link,
 * quick reply, business link rename or fact check).
 *
 * <p>The repo has no Robolectric/instrumentation harness, so this test extracts the
 * <b>actual production body</b> of {@code AlertDialogDecor.showRunnable} (and its
 * {@code loadAnimation} helper when present) verbatim from {@code AlertDialogDecor.java} and
 * compiles it into a fixture whose Android dependencies (Context, View, AnimationUtils,
 * Resources.NotFoundException, FileLog, animators) are fakes. The code under test is never
 * rewritten; only its surroundings are stubbed. The dialog's real view animation is not
 * exercised, because there is no Android runtime here.</p>
 *
 * <pre>
 * javac -d TMessagesProj/build/tmp/dialog-animation/test-classes \
 *     TMessagesProj/src/test/java/xyz/nextalone/nnngram/ui/DialogAnimationFallbackTest.java
 * java -cp TMessagesProj/build/tmp/dialog-animation/test-classes \
 *     xyz.nextalone.nnngram.ui.DialogAnimationFallbackTest .
 * </pre>
 *
 * The optional argument is the repository root (default: current directory).
 */
public final class DialogAnimationFallbackTest {

    public static void main(String[] args) throws Exception {
        Path root = Paths.get(args.length > 0 ? args[0] : ".").toAbsolutePath().normalize();
        Path production = root.resolve("TMessagesProj/src/main/java/org/telegram/ui/ActionBar/AlertDialogDecor.java");
        String source = new String(Files.readAllBytes(production), StandardCharsets.UTF_8);

        String showBody = extractRunnableBody(source, "private final Runnable showRunnable = () -> {");
        String helper = source.contains("private Animation loadAnimation(int res)")
                ? extractMethod(source, "private Animation loadAnimation(int res)")
                : "";

        Path work = root.resolve("TMessagesProj/build/tmp/dialog-animation");
        Path fixtureSrc = work.resolve("fixture-src");
        Path fixtureClasses = work.resolve("fixture-classes");
        Files.createDirectories(fixtureSrc);
        Files.createDirectories(fixtureClasses);
        Path fixtureFile = fixtureSrc.resolve("AlertDialogDecor.java");
        Files.write(fixtureFile, fixtureSource(showBody, helper).getBytes(StandardCharsets.UTF_8));

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
                new URL[]{fixtureClasses.toUri().toURL()}, DialogAnimationFallbackTest.class.getClassLoader());
        try {
            Class<?> decor = loader.loadClass("fixture.AlertDialogDecor");
            Class<?> view = loader.loadClass("fixture.AlertDialogDecor$View");
            Class<?> fileLog = loader.loadClass("fixture.AlertDialogDecor$FileLog");
            Method show = decor.getDeclaredMethod("show");
            show.setAccessible(true);

            // --- unset enter animation (the vendor-theme crash) ---
            Object instance = decor.getDeclaredConstructor(int.class).newInstance(-1);
            fileLog.getField("errorCount").setInt(null, 0);
            try {
                show.invoke(instance);
            } catch (InvocationTargetException e) {
                throw new AssertionError("opening a dialog with an unset enter animation crashed: "
                        + e.getCause(), e.getCause());
            }
            Object content = decor.getField("contentView").get(instance);
            if (((Float) view.getField("alpha").get(content)) != 1f) {
                throw new AssertionError("without an enter animation the content must fade in to alpha 1, was "
                        + view.getField("alpha").get(content));
            }
            Object root_ = decor.getField("rootView").get(instance);
            if (((Integer) view.getField("visibility").get(root_)) != 0) {
                throw new AssertionError("the dialog root must be visible after showing");
            }

            // --- an animation resource the platform cannot resolve ---
            Object unreadable = decor.getDeclaredConstructor(int.class).newInstance(7);
            fileLog.getField("errorCount").setInt(null, 0);
            try {
                show.invoke(unreadable);
            } catch (InvocationTargetException e) {
                throw new AssertionError("opening a dialog with an unresolvable enter animation crashed: "
                        + e.getCause(), e.getCause());
            }
            if (fileLog.getField("errorCount").getInt(null) != 1) {
                throw new AssertionError("an unresolvable enter animation should be logged exactly once");
            }

            // --- a real animation resource still plays ---
            Object animated = decor.getDeclaredConstructor(int.class).newInstance(42);
            Object animatedContent = decor.getField("contentView").get(animated);
            show.invoke(animated);
            if (view.getField("startedAnimation").get(animatedContent) == null) {
                throw new AssertionError("a resolvable enter animation must still be started");
            }
        } finally {
            loader.close();
        }
        System.out.println("OK: adaptive dialogs tolerate a theme without window animations");
    }

    /** Extracts the block body of {@code <signature> ... -> { body };}. */
    private static String extractRunnableBody(String source, String signature) {
        int start = source.indexOf(signature);
        if (start < 0) {
            throw new IllegalStateException("runnable not found: " + signature);
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
                if (depth == 0) return source.substring(open + 1, i);
            }
        }
        throw new IllegalStateException("unbalanced braces for: " + signature);
    }

    private static String extractMethod(String source, String signature) {
        int start = source.indexOf(signature);
        if (start < 0) {
            throw new IllegalStateException("method not found: " + signature);
        }
        String body = extractRunnableBody(source, signature);
        return signature + " {\n" + body + "}\n";
    }

    private static String fixtureSource(String showBody, String helper) {
        return "package fixture;\n"
                + "\n"
                + "/** Test fixture: production bodies pasted at the marker; everything else is a stub. */\n"
                + "public class AlertDialogDecor {\n"
                + "\n"
                + "    public static final int DIM_DURATION = 300;\n"
                + "\n"
                + "    public static class Animation { }\n"
                + "\n"
                + "    public static class Resources {\n"
                + "        public static class NotFoundException extends RuntimeException { }\n"
                + "    }\n"
                + "\n"
                + "    public static class Context { }\n"
                + "    private final Context context = new Context();\n"
                + "    public Context getContext() { return context; }\n"
                + "\n"
                + "    public static class View {\n"
                + "        public int visibility = 4;\n"
                + "        public float alpha = 1f;\n"
                + "        public Animation startedAnimation;\n"
                + "        private float pendingAlpha = 1f;\n"
                + "        public static final int VISIBLE = 0;\n"
                + "        public void setVisibility(int visibility) { this.visibility = visibility; }\n"
                + "        public void setAlpha(float alpha) { this.alpha = alpha; }\n"
                + "        public void startAnimation(Animation animation) { startedAnimation = animation; }\n"
                + "        public AnimatorBuilder animate() { return new AnimatorBuilder(this); }\n"
                + "    }\n"
                + "\n"
                + "    public static class AnimatorBuilder {\n"
                + "        private final View view;\n"
                + "        AnimatorBuilder(View view) { this.view = view; }\n"
                + "        public AnimatorBuilder setDuration(long duration) { return this; }\n"
                + "        public AnimatorBuilder alpha(float alpha) { view.pendingAlpha = alpha; return this; }\n"
                + "        public AnimatorBuilder setListener(AnimatorListenerAdapter listener) {\n"
                + "            if (listener != null) listener.onAnimationEnd(new Animator());\n"
                + "            return this;\n"
                + "        }\n"
                + "        public AnimatorBuilder start() { view.alpha = view.pendingAlpha; return this; }\n"
                + "    }\n"
                + "\n"
                + "    public static class Animator { }\n"
                + "\n"
                + "    public static class AnimatorListenerAdapter {\n"
                + "        public void onAnimationEnd(Animator animation) { }\n"
                + "    }\n"
                + "\n"
                + "    public interface OnShowListener { void onShow(AlertDialogDecor dialog); }\n"
                + "    private OnShowListener onShowListener;\n"
                + "\n"
                + "    public static class AnimationUtils {\n"
                + "        public static Animation loadAnimation(Context context, int res) {\n"
                + "            // like Resources: only 42 is a real animation in this fixture, anything else cannot be resolved\n"
                + "            if (res != 42) throw new Resources.NotFoundException();\n"
                + "            return new Animation();\n"
                + "        }\n"
                + "    }\n"
                + "\n"
                + "    public static class FileLog {\n"
                + "        public static int errorCount;\n"
                + "        public static void e(Throwable e) { errorCount++; }\n"
                + "    }\n"
                + "\n"
                + "    public @interface Nullable { }\n"
                + "\n"
                + "    public View rootView = new View();\n"
                + "    public View contentView = new View();\n"
                + "    public View dimView = new View();\n"
                + "    public int resEnterAnimation;\n"
                + "\n"
                + "    public AlertDialogDecor(int resEnterAnimation) { this.resEnterAnimation = resEnterAnimation; }\n"
                + "\n"
                + "    // ===== production bodies, extracted verbatim from AlertDialogDecor.java =====\n"
                + helper + "\n"
                + "    void show() {" + showBody + "}\n"
                + "}\n";
    }
}
