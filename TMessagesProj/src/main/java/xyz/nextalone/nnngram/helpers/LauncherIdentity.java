package xyz.nextalone.nnngram.helpers;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Predicate;

public final class LauncherIdentity {
    public static final int NAME_COUNT = 10;

    private final List<String> icons;
    private final Predicate<String> enabled;
    private final BiConsumer<String, Boolean> setEnabled;

    public LauncherIdentity(List<String> icons, Predicate<String> enabled,
                            BiConsumer<String, Boolean> setEnabled) {
        this.icons = new ArrayList<>(icons);
        this.enabled = enabled;
        this.setEnabled = setEnabled;
    }

    // Android persists alias names across upgrades, so preset indices must remain stable.
    public static List<String> names(String originalName) {
        return java.util.Arrays.asList(originalName, "方寸", "软件测试", "ApkTest", "QingSao",
                "RAM", "软件驱动", "GitHub", "软件商店", "OneDrive");
    }

    public static String label(String originalName, int name) {
        return name > 0 && name < NAME_COUNT ? names(originalName).get(name) : originalName;
    }

    public static String componentName(String icon, int name) {
        if (name < 0 || name >= NAME_COUNT) throw new IllegalArgumentException("Unknown launcher name: " + name);
        return "org.telegram.messenger." + (name == 0 ? icon : "LauncherName" + name + "_" + icon);
    }

    public int currentName() {
        for (int name = 0; name < NAME_COUNT; name++) {
            for (String icon : icons) {
                if (enabled.test(componentName(icon, name))) return name;
            }
        }
        return 0;
    }

    public String currentIcon() {
        for (int name = 0; name < NAME_COUNT; name++) {
            for (String icon : icons) {
                if (enabled.test(componentName(icon, name))) return icon;
            }
        }
        return icons.get(0);
    }

    public void selectName(int name) {
        select(currentIcon(), name);
    }

    public void selectIcon(String icon) {
        if (!icons.contains(icon)) throw new IllegalArgumentException("Unknown launcher icon: " + icon);
        select(icon, currentName());
    }

    public void repair(int savedName) {
        int name = currentName();
        String icon = currentIcon();
        if (!enabled.test(componentName(icon, name))) {
            name = savedName >= 0 && savedName < NAME_COUNT ? savedName : 0;
        }
        select(icon, name);
    }

    private void select(String icon, int name) {
        String target = componentName(icon, name);
        List<String> previous = new ArrayList<>();
        for (int otherName = 0; otherName < NAME_COUNT; otherName++) {
            for (String otherIcon : icons) {
                String other = componentName(otherIcon, otherName);
                if (enabled.test(other)) previous.add(other);
            }
        }
        try {
            if (!previous.contains(target)) setEnabled.accept(target, true);
            for (String other : previous) {
                if (!other.equals(target)) setEnabled.accept(other, false);
            }
        } catch (RuntimeException failure) {
            for (String other : previous) {
                try {
                    if (!enabled.test(other)) setEnabled.accept(other, true);
                } catch (RuntimeException rollbackFailure) {
                    failure.addSuppressed(rollbackFailure);
                }
            }
            try {
                if (!previous.contains(target) && previous.stream().anyMatch(enabled)) {
                    setEnabled.accept(target, false);
                }
            } catch (RuntimeException rollbackFailure) {
                failure.addSuppressed(rollbackFailure);
            }
            throw failure;
        }
    }
}
