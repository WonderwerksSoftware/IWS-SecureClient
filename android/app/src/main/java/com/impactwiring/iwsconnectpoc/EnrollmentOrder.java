package com.impactwiring.iwsconnectpoc;

final class EnrollmentOrder {
    interface Step {
        void run() throws Exception;
    }

    private EnrollmentOrder() {}

    static void protectThenAuthenticate(Step initializeProtection, Step authenticate)
            throws Exception {
        initializeProtection.run();
        authenticate.run();
    }
}
