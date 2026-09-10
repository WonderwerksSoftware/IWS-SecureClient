package com.impactwiring.iwsconnectpoc;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Owns the single worker allowed to execute app-process readiness probes. */
final class PortalProbeRunner implements AutoCloseable {
    interface Callback {
        void onCompleted(long episode, PortalReadinessResult result);
    }

    private final PortalHealthProbe probe;
    private final ExecutorService executor;
    private boolean running;
    private boolean closed;

    PortalProbeRunner(PortalHealthProbe probe) {
        this.probe = probe;
        this.executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "iws-portal-readiness");
            thread.setDaemon(true);
            return thread;
        });
    }

    synchronized boolean start(long episode, long remainingMillis, Callback callback) {
        if (closed || running) {
            return false;
        }
        running = true;
        executor.execute(() -> {
            PortalReadinessResult result = probe.execute(remainingMillis);
            synchronized (PortalProbeRunner.this) {
                running = false;
                if (closed) {
                    return;
                }
            }
            callback.onCompleted(episode, result);
        });
        return true;
    }

    @Override
    public synchronized void close() {
        closed = true;
        executor.shutdownNow();
    }
}
