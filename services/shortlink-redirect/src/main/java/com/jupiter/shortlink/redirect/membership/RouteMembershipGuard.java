package com.jupiter.shortlink.redirect.membership;

/** An existence hint is never a route value or a renewable negative cache entry. */
public interface RouteMembershipGuard {
    enum Outcome {
        MAY_EXIST,
        DEFINITELY_ABSENT,
        UNKNOWN
    }

    interface AbsentProof {}

    record Check(Outcome outcome, AbsentProof proof) {
        public Check {
            if ((outcome == Outcome.DEFINITELY_ABSENT) != (proof != null))
                throw new IllegalArgumentException("Only a proven negative carries a proof");
        }

        public static Check unknown() {
            return new Check(Outcome.UNKNOWN, null);
        }

        public static Check maybe() {
            return new Check(Outcome.MAY_EXIST, null);
        }

        public static Check absent(AbsentProof proof) {
            return new Check(Outcome.DEFINITELY_ABSENT, java.util.Objects.requireNonNull(proof));
        }
    }

    Check check(String domain, String shortUri);

    boolean valid(AbsentProof proof);

    static RouteMembershipGuard disabled() {
        return new RouteMembershipGuard() {
            public Check check(String domain, String shortUri) {
                return Check.unknown();
            }

            public boolean valid(AbsentProof proof) {
                return false;
            }
        };
    }
}
