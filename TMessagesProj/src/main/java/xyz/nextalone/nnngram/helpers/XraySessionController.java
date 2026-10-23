package xyz.nextalone.nnngram.helpers;

import java.util.ArrayDeque;

/**
 * Lifecycle state machine for the local Xray sessions.
 *
 * <p>Deliberately free of Android and Telegram imports so it can be driven by
 * deterministic fakes in a plain JVM test. {@link XrayProxyHelper} is the thin
 * Android adapter (UI dispatch, bulletins, ConnectionsManager wiring).
 *
 * <p>Ownership rules:
 * <ul>
 *   <li>session handles are created and closed <b>only</b> on the worker;
 *       {@code port()/username()/password()} are read on the worker too, and
 *       only the resulting values cross to the UI thread;</li>
 *   <li>{@link #lock} only guards short field mutations, never a Go call;</li>
 *   <li>a start whose target was superseded (or whose probe was cancelled) is
 *       closed inline on the worker before the next queued job runs, so at most
 *       one active and one probe session can be alive;</li>
 *   <li>every probe finishes exactly once, and its session is released before
 *       the result is delivered and before the next probe starts.</li>
 * </ul>
 */
public final class XraySessionController {

    static final long PROBE_START_TIMEOUT = 8_000;
    static final long PROBE_CHECK_TIMEOUT = 20_000;

    /** A running core; implement with the generated gomobile Session. */
    public interface Session {
        int port();

        String username();

        String password();

        void close() throws Exception;
    }

    /** Starts a core for a share link. Called on the worker only. */
    public interface Bridge {
        Session start(String link) throws Exception;
    }

    /** Serial background executor (single thread in production). */
    public interface Worker {
        void execute(Runnable task);
    }

    /** UI-thread scheduling; {@code cancel} must key on the runnable instance. */
    public interface Scheduler {
        void post(Runnable task);

        void postDelayed(Runnable task, long delayMillis);

        void cancel(Runnable task);
    }

    public interface Listener {
        /** A new active endpoint is listening and should be pushed to Telegram. */
        void onActiveReady(String link, int port, String username, String password);

        /** The active start failed; report once per target. */
        void onFailure();
    }

    /** Runs the native proxy check for a prepared endpoint, on the UI thread. */
    public interface ProbeRunner {
        void run(int port, String username, String password, ResultSink sink);
    }

    public interface ResultSink {
        void run(long time);
    }

    private final Object lock = new Object();
    private final Bridge bridge;
    private final Worker worker;
    private final Scheduler scheduler;
    private final Listener listener;

    private long version;
    private String targetLink;
    private XrayEndpoint active = XrayEndpoint.FAIL_CLOSED;
    private Session activeSession;
    private boolean starting;
    private boolean failed;

    private final ArrayDeque<Probe> probeQueue = new ArrayDeque<>();
    private Probe currentProbe;

    public XraySessionController(Bridge bridge, Worker worker, Scheduler scheduler, Listener listener) {
        this.bridge = bridge;
        this.worker = worker;
        this.scheduler = scheduler;
        this.listener = listener;
    }

    /**
     * Makes {@code link} the active target and starts it in the background.
     *
     * @param retryAfterFailure re-attempt a target whose last start failed; only
     *                          an explicit user action should pass true.
     * @return the endpoint to hand to Telegram right now (fail-closed until the
     *         core is actually listening).
     */
    public XrayEndpoint ensureRunning(String link, boolean retryAfterFailure) {
        if (link == null || link.isEmpty()) {
            // A persisted entry with a broken/empty link is still an explicit
            // "use a proxy" intent: release everything, stay fail-closed and
            // report once per target change. Never silently go direct.
            final Session session;
            final boolean report;
            synchronized (lock) {
                version++;
                targetLink = "";
                starting = false;
                active = XrayEndpoint.FAIL_CLOSED;
                session = activeSession;
                activeSession = null;
                report = !failed;
                failed = true;
            }
            if (session != null) {
                worker.execute(() -> closeQuietly(session));
            }
            cancelProbes();
            if (report) {
                listener.onFailure();
            }
            return XrayEndpoint.FAIL_CLOSED;
        }

        final long target;
        synchronized (lock) {
            if (link.equals(targetLink) && (activeSession != null || starting || (failed && !retryAfterFailure))) {
                return active;
            }
            target = ++version;
            targetLink = link;
            starting = true;
            failed = false;
            active = XrayEndpoint.FAIL_CLOSED;
        }
        worker.execute(() -> startActive(link, target));
        return XrayEndpoint.FAIL_CLOSED;
    }

    /** Drops the active target and cancels queued/in-flight probes. */
    public void stop() {
        final Session session;
        synchronized (lock) {
            version++;
            targetLink = null;
            starting = false;
            active = XrayEndpoint.FAIL_CLOSED;
            failed = false;
            session = activeSession;
            activeSession = null;
        }
        if (session != null) {
            worker.execute(() -> closeQuietly(session));
        }
        cancelProbes();
    }

    public XrayEndpoint endpoint() {
        synchronized (lock) {
            return active;
        }
    }

    /** Single-sample snapshot: the endpoint for {@code link} if it is the live target. */
    public XrayEndpoint endpointIfActive(String link) {
        if (link == null) {
            return XrayEndpoint.FAIL_CLOSED;
        }
        synchronized (lock) {
            return link.equals(targetLink) ? active : XrayEndpoint.FAIL_CLOSED;
        }
    }

    public boolean isActive(String link) {
        if (link == null) {
            return false;
        }
        synchronized (lock) {
            return link.equals(targetLink);
        }
    }

    /**
     * Runs a throwaway probe session; never touches the active one.
     * {@code result} is invoked exactly once, on the UI thread.
     */
    public void probe(String link, ProbeRunner runner, ResultSink result) {
        if (result == null) {
            return;
        }
        if (runner == null || link == null || link.isEmpty()) {
            result.run(-1);
            return;
        }
        final Probe probe = new Probe(link, runner, result);
        final boolean start;
        synchronized (lock) {
            probeQueue.addLast(probe);
            start = currentProbe == null;
            if (start) {
                currentProbe = probeQueue.pollFirst();
            }
        }
        if (start) {
            worker.execute(() -> startProbe(probe));
        }
    }

    // ---------------------------------------------------------------- active

    /** Worker thread. */
    private void startActive(String link, long target) {
        final Session previous;
        synchronized (lock) {
            if (target != version) {
                return;
            }
            previous = activeSession;
            activeSession = null;
        }
        // Close inline: re-queueing would let the new core start first and put
        // three sessions in flight while a probe is running.
        if (previous != null) {
            closeQuietly(previous);
        }

        Session session = null;
        try {
            session = bridge.start(link);
        } catch (Throwable ignore) {
            // Categories only; the bridge never echoes the link or credentials.
        }

        XrayEndpoint endpoint = XrayEndpoint.FAIL_CLOSED;
        if (session != null) {
            // Getters stay on the worker; only plain values cross threads.
            endpoint = new XrayEndpoint(session.port(), session.username(), session.password());
            if (!endpoint.isReady()) {
                endpoint = XrayEndpoint.FAIL_CLOSED;
            }
        }

        final boolean stale;
        synchronized (lock) {
            stale = target != version;
            if (!stale) {
                starting = false;
                if (endpoint.isReady()) {
                    activeSession = session;
                    active = endpoint;
                } else {
                    active = XrayEndpoint.FAIL_CLOSED;
                    failed = true;
                }
            }
        }
        if (stale || !endpoint.isReady()) {
            if (session != null) {
                closeQuietly(session);
            }
            if (!stale) {
                listener.onFailure();
            }
            return;
        }
        listener.onActiveReady(link, endpoint.port, endpoint.username, endpoint.password);
    }

    // ----------------------------------------------------------------- probe

    /** Worker thread. */
    private void startProbe(Probe probe) {
        // Capture locally: the scheduler must receive the exact runnable it can
        // later cancel, and the field write is lock-protected.
        final Runnable startTimeout = () -> finishProbe(probe, -1);
        synchronized (lock) {
            // Cancelled while this job sat in the queue: never create a core.
            if (probe.finished) {
                return;
            }
            probe.startTimeout = startTimeout;
        }
        scheduler.postDelayed(startTimeout, PROBE_START_TIMEOUT);

        Session session = null;
        try {
            session = bridge.start(probe.link);
        } catch (Throwable ignore) {
        }

        // Getters stay on the worker, outside the lock; only plain values cross.
        final XrayEndpoint endpoint = session == null ? XrayEndpoint.FAIL_CLOSED
                : new XrayEndpoint(session.port(), session.username(), session.password());
        final boolean cancelled;
        synchronized (lock) {
            cancelled = probe.finished;
            if (!cancelled) {
                probe.session = session;
            }
        }
        if (cancelled) {
            // The stop/cancel already fired while we were starting: release here
            // and do not wait for another UI round-trip.
            if (session != null) {
                closeQuietly(session);
            }
            return;
        }
        if (session == null || !endpoint.isReady()) {
            finishProbe(probe, -1);
            return;
        }
        scheduler.post(() -> onProbeStarted(probe, endpoint));
    }

    /** UI thread. */
    private void onProbeStarted(Probe probe, XrayEndpoint endpoint) {
        final Runnable checkTimeout;
        synchronized (lock) {
            if (probe.finished) {
                return;
            }
            if (probe.startTimeout != null) {
                scheduler.cancel(probe.startTimeout);
                probe.startTimeout = null;
            }
            // Local capture: a racing cancel may clear the field right after
            // the lock; the scheduler must post the exact cancellable instance.
            checkTimeout = () -> finishProbe(probe, -1);
            probe.checkTimeout = checkTimeout;
        }
        scheduler.postDelayed(checkTimeout, PROBE_CHECK_TIMEOUT);
        probe.runner.run(endpoint.port, endpoint.username, endpoint.password,
                time -> finishProbe(probe, time));
    }

    /** Any thread; exactly one winner per probe. */
    private void finishProbe(Probe probe, long time) {
        final Session session;
        synchronized (lock) {
            if (probe.finished) {
                return;
            }
            probe.finished = true;
            session = probe.session;
            probe.session = null;
            if (probe.startTimeout != null) {
                scheduler.cancel(probe.startTimeout);
                probe.startTimeout = null;
            }
            if (probe.checkTimeout != null) {
                scheduler.cancel(probe.checkTimeout);
                probe.checkTimeout = null;
            }
            // Keep the slot held: the next probe must not boot before this
            // probe's core is actually closed, or active+probe can exceed the
            // one-session cap. releaseProbe hands the slot back once the
            // release job has run the close.
            worker.execute(() -> releaseProbe(probe, session, time));
        }
    }

    /** Worker thread. */
    private void releaseProbe(Probe probe, Session session, long time) {
        if (session != null) {
            closeQuietly(session);
        }
        scheduler.post(() -> probe.result.run(time));
        // Now that the core is really closed, hand the slot to the next probe
        // (which starts on this same serial worker, strictly after this close).
        final Probe next;
        synchronized (lock) {
            if (currentProbe == probe) {
                currentProbe = null;
            }
            next = takeNextProbeLocked();
        }
        if (next != null) {
            startProbe(next);
        }
    }

    private void cancelProbes() {
        final ArrayDeque<Probe> cancelled = new ArrayDeque<>();
        synchronized (lock) {
            cancelled.addAll(probeQueue);
            probeQueue.clear();
            // Only take the slot-holding probe if it has not finished yet: a
            // finished probe already has its release enqueued, and closing it
            // again would fire a second result callback.
            if (currentProbe != null && !currentProbe.finished) {
                cancelled.addFirst(currentProbe);
                currentProbe.finished = true;
            }
            for (Probe probe : cancelled) {
                probe.finished = true;
            }
            // currentProbe stays set until the close really runs on the worker,
            // so a probe submitted in the lock->enqueue gap queues behind the
            // close instead of booting a second core.
        }
        for (Probe probe : cancelled) {
            final Session session;
            final ResultSink result;
            final Runnable startTimeout;
            final Runnable checkTimeout;
            synchronized (lock) {
                session = probe.session;
                probe.session = null;
                result = probe.result;
                startTimeout = probe.startTimeout;
                probe.startTimeout = null;
                checkTimeout = probe.checkTimeout;
                probe.checkTimeout = null;
            }
            if (startTimeout != null) {
                scheduler.cancel(startTimeout);
            }
            if (checkTimeout != null) {
                scheduler.cancel(checkTimeout);
            }
            worker.execute(() -> {
                if (session != null) {
                    closeQuietly(session);
                }
                scheduler.post(() -> result.run(-1));
                // Hand the slot back only once the close has actually run; the
                // next probe starts on this same serial worker, after the close.
                final Probe next;
                synchronized (lock) {
                    if (currentProbe == probe) {
                        currentProbe = null;
                    }
                    next = takeNextProbeLocked();
                }
                if (next != null) {
                    startProbe(next);
                }
            });
        }
    }

    /** Must be called while holding {@link #lock}. */
    private Probe takeNextProbeLocked() {
        if (currentProbe != null) {
            return null;
        }
        currentProbe = probeQueue.pollFirst();
        return currentProbe;
    }

    private static void closeQuietly(Session session) {
        try {
            session.close();
        } catch (Throwable ignore) {
        }
    }

    private static final class Probe {
        final String link;
        final ProbeRunner runner;
        final ResultSink result;
        boolean finished;
        Session session;
        Runnable startTimeout;
        Runnable checkTimeout;

        Probe(String link, ProbeRunner runner, ResultSink result) {
            this.link = link;
            this.runner = runner;
            this.result = result;
        }
    }
}
