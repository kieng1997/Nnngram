package xyz.nextalone.nnngram.helpers;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.RequestTimeDelegate;
import org.telegram.ui.Components.Bulletin;
import org.telegram.utils.proxy.ProxySettings;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import xraybridge.Session;
import xraybridge.Xraybridge;

/**
 * Android adapter around {@link XraySessionController}.
 *
 * <p>Owns the UI dispatch, the localized failure bulletin and the bridge to
 * {@link ConnectionsManager}. All lifecycle rules live in the controller, which
 * is Android-free and covered by a deterministic JVM test.
 */
public final class XrayProxyHelper {

    private static final ExecutorService goExecutor = Executors.newSingleThreadExecutor(r -> {
        final Thread thread = new Thread(r, "xray-bridge");
        thread.setPriority(Thread.NORM_PRIORITY);
        return thread;
    });

    private static final XraySessionController.Scheduler scheduler = new XraySessionController.Scheduler() {
        @Override
        public void post(Runnable task) {
            AndroidUtilities.runOnUIThread(task);
        }

        @Override
        public void postDelayed(Runnable task, long delayMillis) {
            AndroidUtilities.runOnUIThread(task, delayMillis);
        }

        @Override
        public void cancel(Runnable task) {
            AndroidUtilities.cancelRunOnUIThread(task);
        }
    };

    private static final XraySessionController.Listener listener = new XraySessionController.Listener() {
        @Override
        public void onActiveReady(String link, int port, String username, String password) {
            ConnectionsManager.onXrayActiveReady(link);
        }

        @Override
        public void onFailure() {
            AndroidUtilities.runOnUIThread(() -> NotificationCenter.getGlobalInstance().postNotificationName(
                    NotificationCenter.showBulletin,
                    Bulletin.TYPE_ERROR,
                    LocaleController.getString(R.string.XrayProxyFailed)));
        }
    };

    private static final XraySessionController controller = new XraySessionController(
            link -> {
                final Session session = Xraybridge.start(link);
                return new SessionAdapter(session);
            },
            goExecutor::execute,
            scheduler,
            listener);

    private XrayProxyHelper() {
    }

    /** Endpoint for the current target, or the fail-closed placeholder. */
    @NonNull
    public static XrayEndpoint ensureRunning(@Nullable ProxySettings settings) {
        return ensureRunning(settings, false);
    }

    /**
     * @param retryAfterFailure re-attempt a target whose last start failed; only
     *                          an explicit user action should pass true.
     */
    @NonNull
    public static XrayEndpoint ensureRunning(@Nullable ProxySettings settings, boolean retryAfterFailure) {
        if (settings == null || settings.getType() != ProxySettings.Type.XRAY) {
            return XrayEndpoint.FAIL_CLOSED;
        }
        return controller.ensureRunning(settings.getSecret(), retryAfterFailure);
    }

    /** Releases the active core and cancels queued/in-flight probes. */
    public static void stop() {
        controller.stop();
    }

    @NonNull
    public static XrayEndpoint getEndpoint() {
        return controller.endpoint();
    }

    /** Endpoint for {@code settings} only if it is still the live target (one sample). */
    @NonNull
    public static XrayEndpoint endpointIfActive(@Nullable ProxySettings settings) {
        if (settings == null || settings.getType() != ProxySettings.Type.XRAY) {
            return XrayEndpoint.FAIL_CLOSED;
        }
        return controller.endpointIfActive(settings.getSecret());
    }

    public static boolean isActive(@Nullable ProxySettings settings) {
        return settings != null
                && settings.getType() == ProxySettings.Type.XRAY
                && controller.isActive(settings.getSecret());
    }

    /**
     * Checks {@code settings} with a throwaway session so the active proxy is not
     * disturbed. Exactly one callback, always after the probe is released.
     */
    public static void probe(ProxySettings settings, ConnectionsManager.NativeCheck nativeCheck, RequestTimeDelegate result) {
        if (result == null) {
            return;
        }
        if (settings == null || settings.getType() != ProxySettings.Type.XRAY || !settings.isValid() || nativeCheck == null) {
            result.run(-1);
            return;
        }
        controller.probe(settings.getSecret(), (port, username, password, sink) ->
                nativeCheck.run(new XrayEndpoint(port, username, password), sink::run), result::run);
    }

    /** Returns the pinned Xray-core version, or null when the bridge is unavailable. */
    @Nullable
    public static String version() {
        try {
            return Xraybridge.version();
        } catch (Throwable t) {
            return null;
        }
    }

    /** Reads the bundled MPL-2.0 license text for the in-app notice. */
    @Nullable
    public static String licenseText() {
        try (java.io.InputStream in = ApplicationLoader.applicationContext.getAssets().open("xray-core-LICENSE.txt")) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
            return out.toString("UTF-8");
        } catch (Throwable t) {
            return null;
        }
    }

    /** Adapts the gomobile session to the Android-free controller contract. */
    private static final class SessionAdapter implements XraySessionController.Session {
        private final Session delegate;

        SessionAdapter(Session delegate) {
            this.delegate = delegate;
        }

        @Override
        public int port() {
            return (int) delegate.port();
        }

        @Override
        public String username() {
            return delegate.username();
        }

        @Override
        public String password() {
            return delegate.password();
        }

        @Override
        public void close() throws Exception {
            delegate.close();
        }
    }
}
