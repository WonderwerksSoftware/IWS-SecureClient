package com.impactwiring.iwsconnectpoc;

/** Guarded by the service monitor. A stop request is not proof of engine exit. */
final class EngineRecovery {
    private boolean running;
    private boolean replacement;
    boolean isRunning() { return running; }

    boolean start() {
        if (running) return false;
        running = true;
        return true;
    }
    boolean retry() {
        if (!running || replacement) return false;
        replacement = true;
        return true;
    }
    boolean finished() {
        running = false;
        boolean restart = replacement;
        replacement = false;
        return restart;
    }
    boolean timeout() {
        boolean expired = replacement;
        replacement = false;
        return expired;
    }
    void cancel() { replacement = false; }
}
