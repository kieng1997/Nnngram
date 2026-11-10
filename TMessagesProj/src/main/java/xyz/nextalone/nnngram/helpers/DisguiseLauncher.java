package xyz.nextalone.nnngram.helpers;

import android.app.Activity;
import android.content.Intent;

import xyz.nextalone.gen.Config;
import xyz.nextalone.nnngram.activity.FakeApkTestActivity;

/**
 * Startup gate for the disguise screen. Called from {@code LaunchActivity}
 * before the real UI is built or an incoming Intent is handled.
 */
public final class DisguiseLauncher {

    /** Original launch Intent parked while the fake page is displayed. */
    public static final String EXTRA_REAL_LAUNCH_INTENT = "disguise_real_launch_intent";

    /**
     * One-shot in-process authorization for the next real launch. Only the fake
     * page's tap gate sets it, and the next gate check consumes it, so it
     * cannot be forged from outside and does not survive process death.
     */
    private static boolean launchPermitted;

    private DisguiseLauncher() {
    }

    /** Authorizes exactly one following LaunchActivity creation. */
    public static void permitNextLaunch() {
        launchPermitted = true;
    }

    /** Cold launch: any Intent is gated and kept for the unlock. */
    public static boolean showIfNeeded(Activity activity) {
        return showIfNeeded(activity, activity.getIntent(), true);
    }

    /** Warm reopen: only a desktop-icon Intent is gated. */
    public static boolean showIfNeeded(Activity activity, Intent intent) {
        return showIfNeeded(activity, intent, false);
    }

    /**
     * @return true when the caller must stop; the original launch Intent has
     *         been handed to the fake page.
     */
    private static boolean showIfNeeded(Activity activity, Intent intent, boolean coldLaunch) {
        if (!Config.disguiseMode) {
            return false;
        }
        if (!coldLaunch && !isLauncherIntent(intent)) {
            return false;
        }
        if (launchPermitted) {
            launchPermitted = false;
            return false;
        }
        Intent fake = new Intent(activity, FakeApkTestActivity.class);
        fake.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        // Keep the original action/data/extras/component so the unlocked app
        // opens exactly what the launcher, a notification or a link asked for.
        if (intent != null) {
            fake.putExtra(EXTRA_REAL_LAUNCH_INTENT, new Intent(intent));
            if ((intent.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0) {
                fake.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            }
        }
        activity.startActivity(fake);
        activity.finish();
        return true;
    }

    private static boolean isLauncherIntent(Intent intent) {
        return intent != null
                && Intent.ACTION_MAIN.equals(intent.getAction())
                && (intent.hasCategory(Intent.CATEGORY_LAUNCHER)
                || intent.hasCategory("android.intent.category.MULTIWINDOW_LAUNCHER"));
    }
}
