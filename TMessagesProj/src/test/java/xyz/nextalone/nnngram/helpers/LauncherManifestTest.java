package xyz.nextalone.nnngram.helpers;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/** Validates the actual packaged launcher declarations, including icon/name combinations. */
public final class LauncherManifestTest {
    private static final String ANDROID = "http://schemas.android.com/apk/res/android";
    private static final String[] ICONS = {"DefaultIcon", "OFFICIAL", "GoogleIcon", "ColorfulIcon",
            "DarkGreenIcon", "NeonIcon", "PremiumIcon"};
    private static final String[] NAMES = {"", "方寸", "软件测试", "ApkTest", "QingSao", "RAM",
            "软件驱动", "GitHub", "软件商店", "OneDrive"};

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args.length == 0 ? "." : args[0]);
        var factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        var document = factory.newDocumentBuilder().parse(root.resolve("TMessagesProj/src/main/AndroidManifest.xml").toFile());
        Element application = (Element) document.getElementsByTagName("application").item(0);
        check("@string/NnnAppLabel".equals(attribute(application, "label")), "original label remains generated per install identity");
        Map<String, Element> aliases = new HashMap<>();
        NodeList nodes = document.getElementsByTagName("activity-alias");
        int enabled = 0;
        for (int i = 0; i < nodes.getLength(); i++) {
            Element alias = (Element) nodes.item(i);
            if (!hasNamedChild(alias, "category", "android.intent.category.LAUNCHER")) continue;
            check(aliases.put(attribute(alias, "name"), alias) == null, "no duplicate alias names");
            if (!"false".equals(attribute(alias, "enabled"))) enabled++;
        }
        check(enabled == 1, "fresh installation has exactly one enabled entry");
        check(aliases.size() == 70, "all ten names support all seven existing icons");
        for (String icon : ICONS) {
            Element original = aliases.get("org.telegram.messenger." + icon);
            check(original != null, "legacy icon alias preserved: " + icon);
            for (int name = 0; name < NAMES.length; name++) {
                String component = LauncherIdentity.componentName(icon, name);
                Element alias = aliases.get(component);
                check(alias != null, "declared launcher component: " + component);
                check("org.telegram.ui.LaunchActivity".equals(attribute(alias, "targetActivity")), "alias still launches the app");
                check("true".equals(attribute(alias, "exported")), "launcher can start alias");
                check(effectiveIcon(alias, application, "icon").equals(effectiveIcon(original, application, "icon")), "name keeps icon: " + component);
                check(effectiveIcon(alias, application, "roundIcon").equals(effectiveIcon(original, application, "roundIcon")), "name keeps round icon: " + component);
                if (name == 0) {
                    check(attribute(alias, "label").isEmpty() || "@string/NnnAppLabel".equals(attribute(alias, "label")), "original name follows install variant");
                } else {
                    check(NAMES[name].equals(attribute(alias, "label")), "exact preset label: " + component);
                    check("false".equals(attribute(alias, "enabled")), "new entries are initially disabled");
                }
                check(hasNamedChild(alias, "action", "android.intent.action.MAIN"), "main launch intent retained");
                check(hasNamedChild(alias, "category", "android.intent.category.LAUNCHER"), "desktop launch category retained");
                check(hasNamedChild(alias, "category", "android.intent.category.MULTIWINDOW_LAUNCHER"), "multiwindow category retained");
                check(hasNamedChild(alias, "meta-data", "android.app.shortcuts"), "app shortcuts retained");
            }
        }
        System.out.println("OK: 70 launcher aliases, exact presets, original labels, icons and launch metadata");
    }

    private static boolean hasNamedChild(Element parent, String tag, String name) {
        NodeList children = parent.getElementsByTagName(tag);
        for (int i = 0; i < children.getLength(); i++) {
            if (name.equals(attribute((Element) children.item(i), "name"))) return true;
        }
        return false;
    }

    private static String effectiveIcon(Element alias, Element application, String key) {
        String icon = attribute(alias, key);
        return icon.isEmpty() ? attribute(application, key) : icon;
    }

    private static String attribute(Element element, String key) {
        return element.getAttributeNS(ANDROID, key);
    }

    private static void check(boolean actual, String message) {
        if (!actual) throw new AssertionError(message);
    }
}
