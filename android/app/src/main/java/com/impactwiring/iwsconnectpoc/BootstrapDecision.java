package com.impactwiring.iwsconnectpoc;

final class BootstrapDecision {
    enum Action { CONNECT, ENROLL, FAIL, WAIT }

    static Action decide(boolean hasIdentity, boolean hasEmbeddedKey, boolean enrollmentInFlight) {
        if (hasIdentity) return Action.CONNECT;
        if (enrollmentInFlight) return Action.WAIT;
        return hasEmbeddedKey ? Action.ENROLL : Action.FAIL;
    }

    private BootstrapDecision() {}
}
