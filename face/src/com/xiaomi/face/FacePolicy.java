// SPDX-License-Identifier: Apache-2.0
package com.xiaomi.face;

import java.util.HashMap;
import java.util.Map;

/** State decisions kept independent of Binder/camera so failure paths can be tested. */
final class FacePolicy {
    static final long CHALLENGE_LIFETIME_MS = 300_000;
    static final long LOCKOUT_MS = 30_000;
    static final int PERMANENT_THRESHOLD = 20;
    final Map<Long, Long> challenges = new HashMap<>();
    int failedAttempts;
    long lockedUntilMillis;

    void addChallenge(long challenge, long nowMillis) {
        challenges.entrySet().removeIf(entry -> entry.getValue() <= nowMillis);
        if (challenge == 0 || challenges.size() >= 16) {
            throw new IllegalStateException("Invalid or excessive face challenges");
        }
        challenges.put(challenge, nowMillis + CHALLENGE_LIFETIME_MS);
    }

    boolean hasChallenge(long challenge, long nowMillis) {
        Long expires = challenges.get(challenge);
        return challenge != 0 && expires != null && nowMillis < expires;
    }

    void consumeChallenge(long challenge) { challenges.remove(challenge); }

    void fail(long nowMillis) {
        failedAttempts = Math.min(PERMANENT_THRESHOLD, failedAttempts + 1);
        if (failedAttempts % 5 == 0) lockedUntilMillis = nowMillis + LOCKOUT_MS;
    }

    boolean permanentlyLocked() { return failedAttempts >= PERMANENT_THRESHOLD; }

    long timedLockout(long nowMillis) {
        return Math.min(LOCKOUT_MS, Math.max(0, lockedUntilMillis - nowMillis));
    }

    void reset() {
        failedAttempts = 0;
        lockedUntilMillis = 0;
    }

    boolean expireLockout(long expectedDeadline) {
        if (permanentlyLocked() || lockedUntilMillis != expectedDeadline) return false;
        lockedUntilMillis = 0;
        return true;
    }
}
