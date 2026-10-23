package xyz.nextalone.nnngram.helpers;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Deterministic behaviour test for {@link XraySessionController}.
 *
 * <p>No Android, no JUnit, no sleeps: the worker and the UI run on two real
 * single-thread executors, every ordering point is a {@link CountDownLatch}, and
 * the bridge is a fake that tracks live sessions. Compile and run with javac:
 *
 * <pre>
 * javac -d /tmp/out \
 *   TMessagesProj/src/main/java/xyz/nextalone/nnngram/helpers/XrayEndpoint.java \
 *   TMessagesProj/src/main/java/xyz/nextalone/nnngram/helpers/XraySessionController.java \
 *   TMessagesProj/src/test/java/xyz/nextalone/nnngram/helpers/XraySessionControllerTest.java
 * java -cp /tmp/out xyz.nextalone.nnngram.helpers.XraySessionControllerTest
 * </pre>
 *
 * Covered: a cancelled queued probe never creates a core; a start superseded by
 * stop or by a switch closes its late session and never applies it; A-&gt;B while
 * a probe boots keeps at most two live cores; probe timeout and duplicate
 * callbacks fire exactly once with the session already released; an empty
 * persisted link fails closed and reports once.
 */
public final class XraySessionControllerTest {

    private static int failures;

    public static void main(String[] args) throws Exception {
        cancelledQueuedProbeNeverStarts();
        lateStartAfterStopIsClosedNotApplied();
        switchDuringStartNeverAppliesOldTarget();
        fastSwitchWithProbeKeepsTwoCores();
        probeTimesOutOnceAndReleases();
        probeDoubleCallbackRunsOnce();
        emptyLinkFailsClosedAndReportsOnce();
        cancelHoldsSlotUntilRealClose();
        slotHeldUntilRealClose();
        stopAfterFinishedProbeDoesNotSecondCallback();
        cancelSlotRacingProbeSubmitsBeforeClose();
        cancelDuringProbeStartClosesLateSession();
        failedTargetNeedsExplicitRetry();

        if (failures > 0) {
            System.out.println(failures + " assertion(s) failed");
            System.exit(1);
        }
        System.out.println("XraySessionControllerTest OK");
        // The harness executors are non-daemon; exit explicitly on success.
        System.exit(0);
    }

    // ------------------------------------------------------------- scenarios

    /** A probe queued behind a blocked worker must not create a core after cancel. */
    private static void cancelledQueuedProbeNeverStarts() throws Exception {
        final Harness h = new Harness();
        final CountDownLatch blocker = new CountDownLatch(1);
        final CountDownLatch blocked = new CountDownLatch(1);
        h.worker.execute(() -> {
            blocked.countDown();
            await(blocker);
        });
        check("worker blocked", blocked.await(2, TimeUnit.SECONDS));

        h.probe("p1");
        h.probe("p2");
        h.controller.stop();
        blocker.countDown();
        h.idle();

        check("cancelled probes started no core", h.bridge.starts.get() == 0);
        check("each cancelled probe reported -1 once",
                h.results.equals(java.util.Arrays.asList("p1:-1", "p2:-1")));
        check("no live core after cancel", h.bridge.live() == 0);
    }

    /** stop() while a start is in flight: the late session is closed, not applied. */
    private static void lateStartAfterStopIsClosedNotApplied() throws Exception {
        final Harness h = new Harness();
        h.bridge.gate = new CountDownLatch(1);
        h.bridge.entered = new CountDownLatch(1);

        h.controller.ensureRunning("ss://a", false);
        check("start entered", h.bridge.entered.await(2, TimeUnit.SECONDS));

        h.controller.stop();          // user switches off while the core boots
        h.bridge.gate.countDown();
        h.idle();

        check("late session closed", h.bridge.live() == 0);
        check("late session never applied", h.ready.isEmpty());
        check("endpoint stays fail-closed", h.controller.endpoint().port == XrayEndpoint.FAIL_CLOSED_PORT);
    }

    /** Switching targets while the old one boots must not apply the old endpoint. */
    private static void switchDuringStartNeverAppliesOldTarget() throws Exception {
        final Harness h = new Harness();
        h.bridge.gate = new CountDownLatch(1);
        h.bridge.entered = new CountDownLatch(1);

        h.controller.ensureRunning("ss://a", false);
        check("start entered", h.bridge.entered.await(2, TimeUnit.SECONDS));
        h.controller.ensureRunning("ss://b", false);

        h.bridge.gate.countDown();
        h.idle();

        check("only the new target applied", h.ready.equals(java.util.Arrays.asList("ss://b")));
        check("one live core after switch", h.bridge.live() == 1);
        check("endpoint is the new target", h.controller.endpointIfActive("ss://b").isReady());
        check("old target is not active", !h.controller.endpointIfActive("ss://a").isReady());
    }

    /** A-&gt;B while a probe boots: at most two cores (one active + one probe). */
    private static void fastSwitchWithProbeKeepsTwoCores() throws Exception {
        final Harness h = new Harness();
        h.controller.ensureRunning("ss://a", false);
        h.idle();
        check("A active", h.bridge.live() == 1);

        h.bridge.gate = new CountDownLatch(1);
        h.bridge.entered = new CountDownLatch(1);
        h.autoFinish.add("p");   // answers as soon as the gate opens
        h.probe("p");
        check("probe start entered", h.bridge.entered.await(2, TimeUnit.SECONDS));

        h.controller.ensureRunning("ss://b", false);   // queued behind the probe start
        check("two cores while both boot", h.bridge.live() <= 2);

        h.bridge.gate.countDown();
        h.idle();

        check("never more than two live cores", h.bridge.maxLive <= 2);
        check("B applied", h.ready.contains("ss://b"));
        check("probe reported once", h.results.size() == 1);
    }

    /** Timeout fires once, releases the probe session, then reports. */
    private static void probeTimesOutOnceAndReleases() throws Exception {
        final Harness h = new Harness();
        h.probe("p");
        h.idle();
        check("probe core live before timeout", h.bridge.live() == 1);

        h.scheduler.fireDelayed();   // the 20s check timeout
        h.idle();

        check("timeout reported -1 once", h.results.equals(java.util.Arrays.asList("p:-1")));
        check("probe core released", h.bridge.live() == 0);
        check("released before reporting", h.liveWhenReported == 0);

        h.scheduler.fireDelayed();   // a stale duplicate must be a no-op
        h.idle();
        check("duplicate timeout ignored", h.results.size() == 1);
    }

    /** The native check may answer twice; only the first wins. */
    private static void probeDoubleCallbackRunsOnce() throws Exception {
        final Harness h = new Harness();
        h.probe("p");
        h.idle();

        h.lastSink.run(42);
        h.lastSink.run(-1);
        h.idle();

        check("first callback wins", h.results.equals(java.util.Arrays.asList("p:42")));
        check("probe core released", h.bridge.live() == 0);
    }

    /**
     * The slot hand-off regression, at the exact fault window: a probe finishes
     * on the UI thread and the controller is about to submit the release job to
     * the worker. A probe enqueued in that window (before the release job runs)
     * must not boot a core before the old one is really closed. The controllable
     * Worker lets us submit the finishing callback and the racing probe while
     * the worker is blocked, which is precisely the interleaving the old code
     * lost.
     */
    private static void slotHeldUntilRealClose() throws Exception {
        // The worker wrapper delays every task until we release it, so the
        // finishProbe->releaseProbe window is fully observable.
        final GateWorker worker = new GateWorker();
        final Harness h = new Harness(worker);
        h.autoFinish.add("p3");

        h.probe("p1");
        h.idle(worker);                 // startProbe(p1) ran: core live
        check("p1 core live", h.bridge.live() == 1);

        // Finish p1 on the UI thread: this submits the release job (still
        // gated) and leaves the slot logically held.
        h.lastSink.run(7);
        // Race a new probe into the enqueue path while the close has not run.
        h.probe("p3");
        h.idle(worker);

        check("old core closed before any new core", h.bridge.maxLive <= 1);
        check("racing probe ran", h.bridge.starts.get() >= 2);
        check("p1 reported", h.results.contains("p1:7"));
        check("all cores released at the end", h.bridge.live() == 0);
    }

    /**
     * Same window on the cancel path: stop() while a probe is in flight must
     * close its core, report -1 once, and a probe racing the cancel must not
     * boot before that close completes.
     */
    private static void cancelHoldsSlotUntilRealClose() throws Exception {
        final GateWorker worker = new GateWorker();
        final Harness h = new Harness(worker);
        h.autoFinish.add("p2");

        h.probe("p1");
        h.idle(worker);
        check("p1 core live", h.bridge.live() == 1);

        h.controller.stop();             // closes p1 via gated worker
        h.probe("p2");                   // queued behind close
        h.idle(worker);

        check("cancelled core closed before any new core", h.bridge.maxLive <= 1);
        check("cancel reported p1 -1 once, p2 ran normally",
                h.results.equals(java.util.Arrays.asList("p1:-1", "p2:42")));
        check("all cores released at the end", h.bridge.live() == 0);
    }

    /** A broken persisted link is still fail-closed and reports once. */
    private static void emptyLinkFailsClosedAndReportsOnce() throws Exception {
        final Harness h = new Harness();
        h.controller.ensureRunning("ss://a", false);
        h.idle();
        check("target running", h.bridge.live() == 1);

        h.controller.ensureRunning("", false);
        h.idle();
        check("empty link releases the core", h.bridge.live() == 0);
        check("empty link is fail-closed", h.controller.endpoint().port == XrayEndpoint.FAIL_CLOSED_PORT);
        check("empty link reported once", h.failures == 1);

        h.controller.ensureRunning("", false);
        h.idle();
        check("no report storm", h.failures == 1);
    }

    /**
     * Bug 1: stop() after a probe already finished (its release job is queued
     * but not run) must not collect it again and fire a second -1 callback.
     */
    private static void stopAfterFinishedProbeDoesNotSecondCallback() throws Exception {
        final GateWorker worker = new GateWorker();
        final Harness h = new Harness(worker);

        h.probe("p1");
        h.idle(worker);
        check("p1 core live", h.bridge.live() == 1);

        // p1 answers, finishProbe enqueues the release (still gated in worker).
        h.lastSink.run(42);
        // stop() now collects the already-finished probe again.
        h.controller.stop();
        h.idle(worker);

        check("finished probe reported exactly once",
                h.results.equals(java.util.Arrays.asList("p1:42")));
        check("all cores released", h.bridge.live() == 0);
    }

    /**
     * Bug 2, at the exact fault window: cancelProbes clears the current-probe
     * slot under the lock but enqueues the close outside it. A probe submitted
     * in that window must not boot a core before the old core is really
     * closed. The one-shot before-enqueue hook re-enters the controller from
     * inside the close's worker.execute, which is precisely the window the old
     * code lost (no sleeps, no retries).
     */
    private static void cancelSlotRacingProbeSubmitsBeforeClose() throws Exception {
        final GateWorker worker = new GateWorker();
        final Harness h = new Harness(worker);
        h.autoFinish.add("p2");

        h.probe("p1");
        h.idle(worker);
        check("p1 core live", h.bridge.live() == 1);

        // One-shot: fire while stop() is between "slot cleared" (old code) and
        // "close enqueued", i.e. on the enqueue of the cancel close itself. In
        // that window the racing probe must not boot before the old core closes.
        worker.beforeEnqueue = () -> h.probe("p2");
        h.controller.stop();
        h.idle(worker);

        check("no core overlap across the cancel window", h.bridge.maxLive <= 1);
        check("racing probe actually started a core", h.bridge.starts.get() >= 2);
        check("cancel reported p1 -1 once, p2 finished normally",
                h.results.equals(java.util.Arrays.asList("p1:-1", "p2:42")));
        check("all cores released at the end", h.bridge.live() == 0);
    }

    /** Bug 3 sibling: cancel during an in-flight start closes the late session. */
    private static void cancelDuringProbeStartClosesLateSession() throws Exception {
        final Harness h = new Harness();
        h.bridge.gate = new CountDownLatch(1);
        h.bridge.entered = new CountDownLatch(1);

        h.probe("p1");
        check("probe start entered", h.bridge.entered.await(2, TimeUnit.SECONDS));
        h.controller.stop();   // probe.finished set while bridge.start is blocked
        h.bridge.gate.countDown();
        h.idle();

        check("late probe session closed", h.bridge.live() == 0);
        check("cancelled probe reported -1 once", h.results.equals(java.util.Arrays.asList("p1:-1")));
    }

    /** Bug 4: a failed start needs an explicit retry; the empty-link path does not prove this. */
    private static void failedTargetNeedsExplicitRetry() throws Exception {
        final Harness h = new Harness();
        h.bridge.failLinks.add("ss://dead");

        h.controller.ensureRunning("ss://dead", false);
        h.idle();
        check("failed start reported", h.failures == 1);
        check("failed start is fail-closed", h.controller.endpoint().port == XrayEndpoint.FAIL_CLOSED_PORT);
        check("no core from failed start", h.bridge.live() == 0);

        // Same link, no explicit retry: must stay failed, no second attempt.
        h.controller.ensureRunning("ss://dead", false);
        h.idle();
        check("no retry without explicit user action", h.bridge.starts.get() == 1);
        check("no second failure report", h.failures == 1);

        // Explicit retry (user action): start again; success sticks.
        h.controller.ensureRunning("ss://dead", true);
        h.idle();
        check("explicit retry re-attempted", h.bridge.starts.get() == 2);
        check("no core while the link is still broken", h.bridge.live() == 0);
        check("retry failure reported", h.failures == 2);

        h.bridge.failLinks.remove("ss://dead");
        h.controller.ensureRunning("ss://dead", true);
        h.idle();
        check("retry after fix succeeds", h.controller.endpointIfActive("ss://dead").isReady());
        check("one live core after retry", h.bridge.live() == 1);
        check("success clears failure state", h.controller.endpointIfActive("ss://dead").isReady());
    }

    // --------------------------------------------------------------- harness

    /**
     * Worker whose tasks execute only after {@link #openOnce()} is called. Each
     * open releases one queued task; {@link #pendingTasks()} reports how many
     * are still waiting.
     */
    private static final class GateWorker implements XraySessionController.Worker {
        private final ArrayDeque<Runnable> queue = new ArrayDeque<>();

        /**
         * Test-side one-shot hook, run just before the next task is queued.
         * Lets a test re-enter the controller at the exact "slot cleared, task
         * not yet enqueued" window; production Worker implementations are
         * untouched.
         */
        volatile Runnable beforeEnqueue;

        @Override
        public void execute(Runnable task) {
            final Runnable hook = beforeEnqueue;
            if (hook != null) {
                beforeEnqueue = null;
                hook.run();
            }
            synchronized (queue) {
                queue.addLast(task);
            }
        }

        void openOnce() {
            final Runnable task;
            synchronized (queue) {
                task = queue.pollFirst();
            }
            if (task != null) {
                task.run();
            }
        }

        int pendingTasks() {
            synchronized (queue) {
                return queue.size();
            }
        }
    }

    private static final class Harness {
        final FakeBridge bridge = new FakeBridge();
        final ExecutorService worker = Executors.newSingleThreadExecutor();
        final ExecutorService ui = Executors.newSingleThreadExecutor();
        final ManualScheduler scheduler = new ManualScheduler(ui);
        final List<String> ready = new ArrayList<>();
        final List<String> results = new ArrayList<>();
        final XraySessionController controller;

        XraySessionController.ResultSink lastSink;
        volatile int liveWhenReported = -1;
        volatile int failures;

        Harness() {
            this(null);
        }

        /** {@code gatedWorker} replaces the default executor when provided. */
        Harness(GateWorker gatedWorker) {
            final java.util.function.Consumer<Runnable> submit =
                    gatedWorker != null ? gatedWorker::execute : (java.util.function.Consumer<Runnable>) worker::execute;
            controller = new XraySessionController(bridge, submit::accept, scheduler,
                    new XraySessionController.Listener() {
                        @Override
                        public void onActiveReady(String link, int port, String username, String password) {
                            ready.add(link);
                        }

                        @Override
                        public void onFailure() {
                            failures++;
                        }
                    });
        }

        /** Probe names that answer immediately with a ping; others wait. */
        final Set<String> autoFinish = new HashSet<>();

        void probe(String name) {
            controller.probe("ss://" + name, (port, username, password, sink) -> {
                lastSink = sink;
                if (autoFinish.contains(name)) {
                    sink.run(42);
                }
            }, time -> {
                results.add(name + ":" + time);
                liveWhenReported = bridge.live();
            });
        }

        /**
         * Waits until UI has nothing pending and the gated worker queue has
         * fully drained (gate opens automatically each round).
         */
        void idle() throws Exception {
            idle(null);
        }

        void idle(GateWorker gateWorker) throws Exception {
            if (gateWorker == null) {
                for (int i = 0; i < 5; i++) {
                    barrier(worker);
                    barrier(ui);
                }
                return;
            }
            int lastPending = -1;
            for (int i = 0; i < 100; i++) {
                int pending = gateWorker.pendingTasks();
                gateWorker.openOnce();
                barrier(ui);
                if (pending == lastPending && pending == 0) {
                    return;
                }
                lastPending = pending;
            }
        }

        private static void barrier(ExecutorService executor) throws Exception {
            final Future<Void> f = executor.submit((Callable<Void>) () -> null);
            f.get(2, TimeUnit.SECONDS);
        }
    }

    /** Tracks live sessions and their peak count. */
    private static final class FakeBridge implements XraySessionController.Bridge {
        final Set<FakeSession> sessions = java.util.Collections.synchronizedSet(new HashSet<>());
        final java.util.concurrent.atomic.AtomicInteger starts = new java.util.concurrent.atomic.AtomicInteger();
        final Set<String> failLinks = new HashSet<>();
        volatile int maxLive;
        volatile CountDownLatch gate;
        volatile CountDownLatch entered;
        private final java.util.concurrent.atomic.AtomicInteger portCounter =
                new java.util.concurrent.atomic.AtomicInteger(40000);

        @Override
        public XraySessionController.Session start(String link) {
            starts.incrementAndGet();
            if (failLinks.contains(link)) {
                throw new IllegalStateException("injected start failure");
            }
            final CountDownLatch g = gate;
            final CountDownLatch e = entered;
            if (e != null) {
                e.countDown();
            }
            if (g != null) {
                await(g);
            }
            final FakeSession session = new FakeSession(this, portCounter.incrementAndGet());
            sessions.add(session);
            if (sessions.size() > maxLive) {
                maxLive = sessions.size();
            }
            return session;
        }

        int live() {
            return sessions.size();
        }
    }

    private static final class FakeSession implements XraySessionController.Session {
        private final FakeBridge owner;
        private final int port;
        private volatile boolean closed;

        FakeSession(FakeBridge owner, int port) {
            this.owner = owner;
            this.port = port;
        }

        @Override
        public int port() {
            return closed ? 0 : port;
        }

        @Override
        public String username() {
            return "u" + port;
        }

        @Override
        public String password() {
            return "p" + port;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            owner.sessions.remove(this);
        }
    }

    /** UI scheduler: immediate posts run on the UI executor, delayed ones on demand. */
    private static final class ManualScheduler implements XraySessionController.Scheduler {
        private final ExecutorService ui;
        private final ArrayDeque<Runnable> delayed = new ArrayDeque<>();

        ManualScheduler(ExecutorService ui) {
            this.ui = ui;
        }

        @Override
        public void post(Runnable task) {
            ui.execute(task);
        }

        @Override
        public void postDelayed(Runnable task, long delayMillis) {
            synchronized (delayed) {
                delayed.addLast(task);
            }
        }

        @Override
        public void cancel(Runnable task) {
            synchronized (delayed) {
                delayed.remove(task);
            }
        }

        void fireDelayed() {
            final Runnable task;
            synchronized (delayed) {
                task = delayed.pollFirst();
            }
            if (task != null) {
                ui.execute(task);
            }
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void check(String what, boolean condition) {
        if (!condition) {
            failures++;
            System.out.println("FAIL: " + what);
        }
    }
}
