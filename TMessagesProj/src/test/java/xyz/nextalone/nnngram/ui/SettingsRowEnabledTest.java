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
 * Behaviour regression for the nnn settings page greying out its category rows (upstream fix
 * 4ca7d011b).
 *
 * <p>The category rows (General, Chat, Experiment, Debug, Passcode) use {@code TYPE_TEXT}, but
 * {@code MainSettingActivity}'s adapter overrode {@code isEnabled} with a hardcoded list of
 * types 2, 3 and 5. {@code RecyclerListView} disables rows its adapter reports as not enabled,
 * and since 12.7.0 {@code TextCell.setEnabled(false)} draws at half alpha — so these rows
 * looked greyed out and lost their pressed highlight while still opening their pages.</p>
 *
 * <p>The test extracts the <b>actual production bodies</b>: {@code BaseActivity.BaseListAdapter
 * .isEnabled} and, when present, the adapter override in {@code MainSettingActivity}, plus the
 * real {@code TYPE_*} constants. The effective method for the page is executed against faked
 * view holders. No Android runtime is involved.</p>
 *
 * <pre>
 * javac -d TMessagesProj/build/tmp/settings-enabled/test-classes \
 *     TMessagesProj/src/test/java/xyz/nextalone/nnngram/ui/SettingsRowEnabledTest.java
 * java -cp TMessagesProj/build/tmp/settings-enabled/test-classes \
 *     xyz.nextalone.nnngram.ui.SettingsRowEnabledTest .
 * </pre>
 */
public final class SettingsRowEnabledTest {

    private static final int TYPE_SHADOW = 1;
    private static final int TYPE_SETTINGS = 2;
    private static final int TYPE_CHECK = 3;
    private static final int TYPE_HEADER = 4;
    private static final int TYPE_TEXT = 8;

    public static void main(String[] args) throws Exception {
        Path root = Paths.get(args.length > 0 ? args[0] : ".").toAbsolutePath().normalize();
        String base = read(root, "TMessagesProj/src/main/java/xyz/nextalone/nnngram/activity/BaseActivity.java");
        String main = read(root, "TMessagesProj/src/main/java/xyz/nextalone/nnngram/activity/MainSettingActivity.java");

        String baseIsEnabled = extractMethod(base, "public boolean isEnabled(RecyclerView.ViewHolder holder)");
        String constants = typeConstants(base);
        String override = main.contains("public boolean isEnabled(RecyclerView.ViewHolder holder)")
                ? extractMethod(main, "public boolean isEnabled(RecyclerView.ViewHolder holder)")
                : null;

        Path work = root.resolve("TMessagesProj/build/tmp/settings-enabled");
        Path fixtureSrc = work.resolve("fixture-src");
        Path fixtureClasses = work.resolve("fixture-classes");
        Files.createDirectories(fixtureSrc);
        Files.createDirectories(fixtureClasses);
        Path fixtureFile = fixtureSrc.resolve("fixture/SettingsAdapter.java");
        Files.createDirectories(fixtureFile.getParent());
        Files.write(fixtureFile, fixtureSource(constants, baseIsEnabled, override).getBytes(StandardCharsets.UTF_8));

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
                new URL[]{fixtureClasses.toUri().toURL()}, SettingsRowEnabledTest.class.getClassLoader());
        try {
            Class<?> clazz = loader.loadClass("fixture.SettingsAdapter");
            Class<?> holderClass = loader.loadClass("fixture.SettingsAdapter$RecyclerView$ViewHolder");
            Class<?> effective = loader.loadClass("fixture.SettingsAdapter$SettingsAdapterWithOverride");
            Object adapter = effective.getDeclaredConstructor().newInstance();
            Method isEnabled = effective.getMethod("isEnabled", holderClass);

            assertEnabled("category row (TYPE_TEXT) opens its page", true, isEnabled, adapter, holderClass, TYPE_TEXT);
            assertEnabled("settings row (TYPE_SETTINGS)", true, isEnabled, adapter, holderClass, TYPE_SETTINGS);
            assertEnabled("checkbox row (TYPE_CHECK)", true, isEnabled, adapter, holderClass, TYPE_CHECK);
            assertEnabled("section header stays disabled", false, isEnabled, adapter, holderClass, TYPE_HEADER);
            assertEnabled("shadow row stays disabled", false, isEnabled, adapter, holderClass, TYPE_SHADOW);
        } finally {
            loader.close();
        }
        System.out.println("OK: nnn settings category rows are enabled without enabling headers");
    }

    private static void assertEnabled(String label, boolean expected, Method isEnabled, Object adapter,
                                      Class<?> holderClass, int type) throws Exception {
        Class<?> holder = holderClass;
        Object viewHolder = holder.getDeclaredConstructor(int.class).newInstance(type);
        boolean actual = (Boolean) isEnabled.invoke(adapter, viewHolder);
        if (actual != expected) {
            throw new AssertionError(label + ": expected isEnabled=" + expected + " but was " + actual);
        }
    }

    private static String read(Path root, String relative) throws Exception {
        return new String(Files.readAllBytes(root.resolve(relative)), StandardCharsets.UTF_8);
    }

    private static String typeConstants(String source) {
        StringBuilder sb = new StringBuilder();
        int index = 0;
        while ((index = source.indexOf("public static final int TYPE_", index)) >= 0) {
            int end = source.indexOf(';', index);
            sb.append("    ").append(source, index, end + 1).append('\n');
            index = end + 1;
        }
        return sb.toString();
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

    private static String fixtureSource(String constants, String baseIsEnabled, String override) {
        return """
                package fixture;

                /** Test fixture: production TYPE_* constants and isEnabled bodies pasted at the markers. */
                public class SettingsAdapter {

                    public static class RecyclerView {
                        public static class ViewHolder {
                            private final int itemViewType;
                            public ViewHolder(int itemViewType) { this.itemViewType = itemViewType; }
                            public final int getItemViewType() { return itemViewType; }
                        }
                    }

                """ + constants + """

                    // ===== production method, extracted verbatim from BaseActivity.java =====
                    public static class BaseAdapter {
                """ + baseIsEnabled + "\n"
                + "    }\n"
                + "    // ===== MainSettingActivity adapter: production override when present, otherwise inherited =====\n"
                + "    public static class SettingsAdapterWithOverride extends BaseAdapter {\n"
                + (override != null ? override + "\n" : "")
                + "    }\n"
                + "}\n";
    }
}
