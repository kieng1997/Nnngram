package xyz.nextalone.nnngram.helpers;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.LauncherIconController.LauncherIcon;

import java.util.ArrayList;

import xyz.nextalone.gen.Config;

public final class LauncherNameController {
    private static LauncherIdentity identity() {
        Context context = ApplicationLoader.applicationContext;
        PackageManager pm = context.getPackageManager();
        ArrayList<String> icons = new ArrayList<>();
        for (LauncherIcon icon : LauncherIcon.values()) icons.add(icon.key);
        return new LauncherIdentity(icons, component -> {
            int state = pm.getComponentEnabledSetting(new ComponentName(context.getPackageName(), component));
            return state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED ||
                    state == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT &&
                            component.equals(LauncherIdentity.componentName(LauncherIcon.DEFAULT.key, 0));
        }, (component, enable) -> pm.setComponentEnabledSetting(
                new ComponentName(context.getPackageName(), component),
                enable ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED : PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP));
    }

    public static void repair() {
        try {
            LauncherIdentity identity = identity();
            identity.repair(Config.getLauncherName());
            Config.setLauncherName(identity.currentName());
        } catch (RuntimeException e) {
            FileLog.e(e);
        }
    }

    public static int getName() {
        return identity().currentName();
    }

    public static ArrayList<String> names() {
        String original = ApplicationLoader.applicationContext.getString(R.string.NnnAppLabel);
        ArrayList<String> names = new ArrayList<>(LauncherIdentity.names(original));
        names.set(0, LocaleController.formatString(R.string.LauncherNameOriginal, original));
        return names;
    }

    public static void setName(int name) {
        identity().selectName(name);
        Config.setLauncherName(name);
    }

    public static boolean isEnabled(LauncherIcon icon) {
        return identity().currentIcon().equals(icon.key);
    }

    public static void setIcon(LauncherIcon icon) {
        LauncherIdentity identity = identity();
        identity.selectIcon(icon.key);
        Config.setLauncherName(identity.currentName());
    }
}
