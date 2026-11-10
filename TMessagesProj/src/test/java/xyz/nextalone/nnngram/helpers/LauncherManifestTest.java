package xyz.nextalone.nnngram.helpers;

import java.nio.file.Files;
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
            "DarkGreenIcon", "NeonIcon", "PremiumIcon", "ApkTestIcon", "OrbitIcon"};
    private static final String[] NEW_ICONS = {"ApkTestIcon", "OrbitIcon"};
    private static final String[] NEW_TITLES = {"AppIconApkTest", "AppIconOrbit"};
    private static final String[] LOCALES = {"values", "values-zh", "values-zh-rTW"};
    private static final String[] DENSITIES = {"mdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi"};
    private static final String[] NAMES = {"", "方寸", "软件测试", "ApkTest", "QingSao", "RAM",
            "软件驱动", "GitHub", "软件商店", "OneDrive"};

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args.length == 0 ? "." : args[0]);
        var factory = namespaceAwareFactory();
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
        check(aliases.size() == 90, "all ten names support all nine icons");
        Map<String, String> launchIcons = new HashMap<>();
        for (String icon : ICONS) {
            Element original = aliases.get("org.telegram.messenger." + icon);
            check(original != null, "legacy icon alias preserved: " + icon);
            String launchIcon = effectiveIcon(original, application, "icon");
            check(launchIcons.put(launchIcon, icon) == null, "each icon launches with its own artwork: " + icon);
            boolean newIcon = java.util.Arrays.asList(NEW_ICONS).contains(icon);
            checkLaunchResources(root, launchIcon, newIcon);
            checkLaunchResources(root, effectiveIcon(original, application, "roundIcon"), newIcon);
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
        checkSelectableIcons(root);
        System.out.println("OK: 90 launcher aliases, exact presets, original labels, icons and launch metadata");
    }

    /** New icons must ship real launch artwork: adaptive layers plus a pre-v26 bitmap fallback. */
    private static void checkLaunchResources(Path root, String reference, boolean newIcon) throws Exception {
        String name = reference.substring(reference.lastIndexOf('/') + 1);
        Path res = root.resolve("TMessagesProj/src/main/res");
        Path adaptive = res.resolve("mipmap-anydpi-v26/" + name + ".xml");
        check(Files.isRegularFile(adaptive), "adaptive icon declared: " + name);
        if (!newIcon) return;
        for (String density : DENSITIES) {
            check(Files.isRegularFile(res.resolve("mipmap-" + density + "/" + name + ".png")),
                    "pre-v26 launcher bitmap exists: " + name + "/" + density);
        }
        Element foreground = (Element) namespaceAwareFactory().newDocumentBuilder()
                .parse(adaptive.toFile()).getElementsByTagName("foreground").item(0);
        String layer = attribute(foreground, "drawable");
        check(layer.startsWith("@drawable/"), "foreground is a real drawable: " + name);
        String file = layer.substring("@drawable/".length());
        check(Files.isRegularFile(res.resolve("drawable/" + file + ".xml")) || Files.isRegularFile(res.resolve("drawable/" + file + ".png")),
                "foreground artwork exists: " + layer);
    }

    /** The appearance selector builds its list and preview from the enum, so each new key needs both. */
    private static void checkSelectableIcons(Path root) throws Exception {
        String source = Files.readString(root.resolve("TMessagesProj/src/main/java/org/telegram/ui/LauncherIconController.java"));
        for (String icon : NEW_ICONS) {
            int start = source.indexOf("(\"" + icon + "\",");
            check(start >= 0, "selectable in appearance settings: " + icon);
            String entry = source.substring(start, source.indexOf(')', start));
            String prefix = "ic_launcher_nagram_" + icon.replace("Icon", "").toLowerCase();
            for (String layer : new String[]{"_background", "_foreground"}) {
                check(entry.contains("R.drawable." + prefix + layer + ","), "preview layer wired: " + prefix + layer);
                check(Files.isRegularFile(root.resolve("TMessagesProj/src/main/res/drawable/" + prefix + layer + ".xml")),
                        "preview artwork exists: " + prefix + layer);
            }
        }
        for (String locale : LOCALES) {
            var document = namespaceAwareFactory().newDocumentBuilder()
                    .parse(root.resolve("TMessagesProj/src/main/res/" + locale + "/strings_nullgram.xml").toFile());
            NodeList strings = document.getElementsByTagName("string");
            for (String title : NEW_TITLES) {
                String value = null;
                for (int i = 0; i < strings.getLength(); i++) {
                    Element string = (Element) strings.item(i);
                    if (title.equals(string.getAttribute("name"))) value = string.getTextContent().trim();
                }
                check(value != null && !value.isEmpty(), "localized icon title: " + locale + "/" + title);
            }
        }
    }

    private static DocumentBuilderFactory namespaceAwareFactory() {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        return factory;
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
