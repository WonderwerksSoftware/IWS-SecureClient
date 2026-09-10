package com.impactwiring.iwsconnectpoc;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Enforces one readiness probe across all Activity owners in the app process. */
final class PortalProbeRunner implements AutoCloseable {
    interface Callback {
        void onCompleted(long probeIdentity, PortalReadinessResult result);
    }

    private static final Object PROCESS_LOCK = new Object();
    private static boolean processProbeRunning;
    private static PortalProbeRunner availabilityOwner;

    private final PortalHealthProbe probe;
    private final ExecutorService executor;
    private boolean closed;
    private boolean attached;
    private Callback activeCallback;
    private Runnable availabilityCallback;

    PortalProbeRunner(PortalHealthProbe probe) {
        this.probe = probe;
        this.executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "iws-portal-readiness");
            thread.setDaemon(true);
            return thread;
        });
    }

    void attach(Runnable onAvailable) {
        boolean available;
        synchronized (PROCESS_LOCK) {
            if (closed) {
                return;
            }
            attached = true;
            availabilityCallback = onAvailable;
            availabilityOwner = this;
            available = !processProbeRunning;
        }
        if (available) {
            onAvailable.run();
        }
    }

    void detach() {
        synchronized (PROCESS_LOCK) {
            attached = false;
            activeCallback = null;
            availabilityCallback = null;
            if (availabilityOwner == this) {
                availabilityOwner = null;
            }
        }
    }

    boolean start(long probeIdentity, long remainingMillis, Callback callback) {
        synchronized (PROCESS_LOCK) {
            if (closed || processProbeRunning) {
                return false;
            }
            processProbeRunning = true;
            activeCallback = callback;
        }
        executor.execute(() -> completeProbe(probeIdentity, remainingMillis));
        return true;
    }

    private void completeProbe(long probeIdentity, long remainingMillis) {
        PortalReadinessResult result = probe.execute(remainingMillis);
        Callback completion;
        Runnable available;
        synchronized (PROCESS_LOCK) {
            processProbeRunning = false;
            completion = attached && !closed ? activeCallback : null;
            activeCallback = null;
            PortalProbeRunner owner = availabilityOwner;
            available = owner != null && owner.attached && !owner.closed
                    ? owner.availabilityCallback
                    : null;
        }
        if (completion != null) {
            completion.onCompleted(probeIdentity, result);
        }
        if (available != null) {
            available.run();
        }
    }

    @Override
    public void close() {
        synchronized (PROCESS_LOCK) {
            closed = true;
        }
        detach();
        executor.shutdownNow();
    }
}
