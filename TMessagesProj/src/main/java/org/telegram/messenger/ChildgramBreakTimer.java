package org.telegram.messenger;

/** Installation-wide session balance. All durations are monotonic and supplied by the caller. */
final class ChildgramBreakTimer {
    private final long restRate, earlyRate, lateRate, hint, limit, activeMillis, restMillis;
    long debt;
    boolean resting;

    ChildgramBreakTimer(int startMinutes, int fadeMinutes, int restMinutes, long savedDebt) {
        // Spend 1/5 of the full break before the first hint, then the other 4/5 during fading.
        // Use the smallest common integer units so splitting a tick never discards fractional time.
        long rest = 5L * startMinutes * fadeMinutes;
        long early = (long) restMinutes * fadeMinutes;
        long late = 4L * restMinutes * startMinutes;
        long divisor = gcd(rest, gcd(early, late));
        restRate = rest / divisor;
        earlyRate = early / divisor;
        lateRate = late / divisor;
        activeMillis = 60_000L * (startMinutes + fadeMinutes);
        restMillis = 60_000L * restMinutes;
        limit = restMillis * restRate;
        hint = limit / 5;
        debt = Math.max(0, Math.min(limit, savedDebt));
        resting = debt == limit;
    }

    private static long gcd(long a, long b) {
        return b == 0 ? a : gcd(b, a % b);
    }

    void use(long millis) {
        if (resting) {
            recover(millis);
            return;
        }
        millis = Math.max(0, Math.min(activeMillis, millis));
        if (debt >= hint) {
            debt += millis * lateRate;
        } else {
            debt += millis * earlyRate;
            if (debt > hint) debt = hint + (debt - hint) * lateRate / earlyRate;
        }
        debt = Math.min(limit, debt);
        if (debt == limit) resting = true;
    }

    void recover(long millis) {
        debt = Math.max(0, debt - restRate * Math.max(0, Math.min(restMillis, millis)));
    }

    void resume() {
        resting = debt == limit;
    }

    long remainingMillis() {
        return (debt + restRate - 1) / restRate;
    }

    boolean showHint() {
        return debt >= hint;
    }

    float alpha() {
        float progress = (float) Math.max(0, debt - hint) / (limit - hint);
        return progress * progress;
    }

    static long timeAway(long savedElapsed, long savedWall, int savedBoot, long elapsed, long wall, int boot) {
        // On devices without a boot counter prefer elapsed time whenever the checkpoint is reachable.
        if (elapsed >= savedElapsed && (boot < 0 || savedBoot < 0 || boot == savedBoot)) {
            return elapsed - savedElapsed;
        }
        // Uptime since the new boot is always time spent outside the previous process.
        return Math.max(elapsed, wall - savedWall);
    }
}
