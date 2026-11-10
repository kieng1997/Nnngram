package xyz.nextalone.nnngram.helpers;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** Plain JVM check; compile together with LauncherIdentity.java and run this class. */
public final class LauncherIdentityTest {
    private static final String GOOGLE = "org.telegram.messenger.GoogleIcon";
    private static final String FANGCUN_GOOGLE = "org.telegram.messenger.LauncherName1_GoogleIcon";
    private static final java.util.List<String> ICONS = Arrays.asList(
            "DefaultIcon", "OFFICIAL", "GoogleIcon", "ColorfulIcon", "DarkGreenIcon", "NeonIcon", "PremiumIcon",
            "ApkTestIcon", "OrbitIcon");

    public static void main(String[] args) {
        nameSwitchPreservesIconAndSurvivesRestart();
        iconSwitchPreservesName();
        newIconsSwitchLikeTheExistingOnes();
        failedSwitchKeepsPreviousEntry();
        startupRepairsEntriesWithoutResettingAValidSelection();
        presetNamesKeepTheInstallLabel();
        System.out.println("OK: launcher selection, restart, failure recovery, startup repair and exact presets");
    }

    private static void newIconsSwitchLikeTheExistingOnes() {
        Set<String> enabled = new HashSet<>(Set.of(GOOGLE));
        LauncherIdentity identity = identity(enabled);
        identity.selectName(2);
        identity.selectIcon("ApkTestIcon");
        check(enabled.equals(Set.of("org.telegram.messenger.LauncherName2_ApkTestIcon")),
                "switching to the APK test icon retains the selected name");
        identity.selectIcon("OrbitIcon");
        check(enabled.equals(Set.of("org.telegram.messenger.LauncherName2_OrbitIcon")),
                "switching to the orbit icon retains the selected name");
        check(identity.currentName() == 2 && "OrbitIcon".equals(identity.currentIcon()),
                "the new icon and its name are observable");
    }

    private static void nameSwitchPreservesIconAndSurvivesRestart() {
        Set<String> enabled = new HashSet<>();
        enabled.add(GOOGLE);
        LauncherIdentity identity = identity(enabled);
        identity.selectName(1);
        check(enabled.equals(Set.of(FANGCUN_GOOGLE)), "only the renamed Google icon remains enabled");
        check(identity.currentName() == 1, "selected name is observable");
        LauncherIdentity restarted = identity(enabled);
        check(restarted.currentName() == 1, "component state survives controller recreation");
        check("GoogleIcon".equals(restarted.currentIcon()), "selected icon survives controller recreation");
        restarted.selectName(0);
        check(enabled.equals(Set.of(GOOGLE)), "original name restores the legacy Google entry");
    }

    private static void iconSwitchPreservesName() {
        Set<String> enabled = new HashSet<>(Set.of(GOOGLE));
        LauncherIdentity identity = identity(enabled);
        identity.selectName(7);
        identity.selectIcon("PremiumIcon");
        check(enabled.equals(Set.of("org.telegram.messenger.LauncherName7_PremiumIcon")),
                "changing the icon retains the selected GitHub name");
        check(identity.currentName() == 7, "name remains selected after changing icon");
    }

    private static void failedSwitchKeepsPreviousEntry() {
        Set<String> enabled = new HashSet<>(Set.of(GOOGLE));
        LauncherIdentity identity = new LauncherIdentity(ICONS, enabled::contains, (component, enable) -> {
            if (component.equals(GOOGLE) && !enable) throw new SecurityException("component update refused");
            if (enable) enabled.add(component); else enabled.remove(component);
            check(!enabled.isEmpty(), "failed switch keeps at least one entry");
        });
        try {
            identity.selectName(1);
            throw new AssertionError("switch failure must reach the caller");
        } catch (SecurityException expected) {
            check(enabled.equals(Set.of(GOOGLE)), "a failed switch restores the previous entry without duplicates");
            check(identity.currentName() == 0, "a failed switch does not report the new name");
        }
    }

    private static void startupRepairsEntriesWithoutResettingAValidSelection() {
        String oneDrive = "org.telegram.messenger.LauncherName9_PremiumIcon";
        Set<String> enabled = new HashSet<>(Set.of(oneDrive));
        identity(enabled).repair(0);
        check(enabled.equals(Set.of(oneDrive)), "startup retains an enabled renamed premium icon");
        enabled.clear();
        identity(enabled).repair(9);
        check(enabled.equals(Set.of("org.telegram.messenger.LauncherName9_DefaultIcon")),
                "missing launcher is repaired using the saved name");
        enabled.clear();
        identity(enabled).repair(999);
        check(enabled.equals(Set.of("org.telegram.messenger.DefaultIcon")), "invalid saved name recovers the default");
        enabled.add(oneDrive);
        identity(enabled).repair(9);
        check(enabled.size() == 1, "startup normalizes duplicate enabled entries");
    }

    private static void presetNamesKeepTheInstallLabel() {
        check(LauncherIdentity.names("NnngramX-k").equals(Arrays.asList(
                "NnngramX-k", "方寸", "软件测试", "ApkTest", "QingSao", "RAM", "软件驱动", "GitHub", "软件商店", "OneDrive")),
                "presets preserve exact requested names, order and per-install original label");
        check(LauncherIdentity.names("NnngramX").get(0).equals("NnngramX"), "original name is not hardcoded");
    }

    private static LauncherIdentity identity(Set<String> enabled) {
        return new LauncherIdentity(ICONS, enabled::contains, (component, enable) -> {
            if (enable) enabled.add(component); else enabled.remove(component);
            check(!enabled.isEmpty(), "switching must never remove every launcher entry");
        });
    }

    private static void check(boolean actual, String message) {
        if (!actual) throw new AssertionError(message);
    }
}
