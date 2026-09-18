package com.impactwiring.iwsconnectpoc;

import java.util.List;

/** Selects an Internet underlay, retaining a working choice until a better one appears. */
final class PhysicalNetworkChoice {
    static final class Candidate {
        final int id;
        final boolean physical;
        final boolean internet;
        final boolean dns;
        final boolean validated;

        Candidate(int id, boolean physical, boolean internet, boolean dns, boolean validated) {
            this.id = id;
            this.physical = physical;
            this.internet = internet;
            this.dns = dns;
            this.validated = validated;
        }
    }

    static Integer choose(List<Candidate> candidates, Integer current) {
        Candidate best = null;
        for (Candidate candidate : candidates) {
            if (!candidate.physical || !candidate.internet || !candidate.dns) continue;
            if (best == null || (candidate.validated && !best.validated)
                    || (candidate.validated == best.validated
                        && current != null && candidate.id == current)) {
                best = candidate;
            }
        }
        return best == null ? null : best.id;
    }
}
