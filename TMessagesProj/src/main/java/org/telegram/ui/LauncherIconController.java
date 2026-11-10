package org.telegram.ui;

import android.content.ComponentName;
import android.content.Context;
import org.telegram.messenger.R;

import xyz.nextalone.nnngram.helpers.LauncherNameController;

public class LauncherIconController {
    public static void tryFixLauncherIconIfNeeded() {
        LauncherNameController.repair();
    }

    public static boolean isEnabled(LauncherIcon icon) {
        return LauncherNameController.isEnabled(icon);
    }

    public static void setIcon(LauncherIcon icon) {
        LauncherNameController.setIcon(icon);
    }

    public enum LauncherIcon {
        DEFAULT("DefaultIcon", R.mipmap.icon_background_nagram, R.mipmap.icon_foreground_nagram, R.string.AppIconDefault),
        OFFICIAL("OFFICIAL", R.drawable.icon_background_sa, R.mipmap.icon_foreground_sa, R.string.Official),
        GOOGLE("GoogleIcon", R.mipmap.icon_background_google, R.mipmap.icon_foreground_google, R.string.AppIconGoogle),
        COLORFUL("ColorfulIcon", R.mipmap.icon_background_colorful, R.mipmap.icon_foreground_colorful, R.string.AppIconColorful),
        DARKGREEN("DarkGreenIcon", R.mipmap.icon_background_darkgreen, R.mipmap.icon_foreground_darkgreen, R.string.AppIconDarkGreen),
        NEON("NeonIcon", R.mipmap.icon_background_neon, R.mipmap.icon_foreground_neon, R.string.AppIconNeon),
        NOX("PremiumIcon", R.drawable.icon_3_background_sa, R.mipmap.icon_3_foreground_sa, R.string.AppIconPremium),
        APK_TEST("ApkTestIcon", R.drawable.ic_launcher_nagram_apktest_background, R.drawable.ic_launcher_nagram_apktest_foreground, R.string.AppIconApkTest),
        ORBIT("OrbitIcon", R.drawable.ic_launcher_nagram_orbit_background, R.drawable.ic_launcher_nagram_orbit_foreground, R.string.AppIconOrbit);


        public final String key;
        public final int background;
        public final int foreground;
        public final int title;
        public final boolean premium;

        private ComponentName componentName;

        public ComponentName getComponentName(Context ctx) {
            if (componentName == null) {
                componentName = new ComponentName(ctx.getPackageName(), "org.telegram.messenger." + key);
            }
            return componentName;
        }

        LauncherIcon(String key, int background, int foreground, int title) {
            this(key, background, foreground, title, false);
        }

        LauncherIcon(String key, int background, int foreground, int title, boolean premium) {
            this.key = key;
            this.background = background;
            this.foreground = foreground;
            this.title = title;
            this.premium = premium;
        }
    }
}
