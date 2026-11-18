package org.telegram.messenger;

public final class ChildgramBreakTimerTest {
    private static final long MINUTE = 60_000;

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        ChildgramBreakTimer timer = new ChildgramBreakTimer(10, 10, 5, 0);
        timer.use(9 * MINUTE);
        timer.recover(30_000);
        timer.use(5 * MINUTE);
        check(!timer.showHint() && timer.remainingMillis() == 54_000, "Short breaks credit every second");
        timer.use(MINUTE);
        check(timer.showHint() && timer.alpha() == 0 && timer.remainingMillis() == MINUTE, "First hint at ten minutes");
        timer.use(5 * MINUTE);
        check(timer.alpha() == .25f && timer.remainingMillis() == 3 * MINUTE, "Midpoint opacity and rest requirement");

        ChildgramBreakTimer uneven = new ChildgramBreakTimer(10, 10, 5, 0);
        for (long t = 0; t < 15 * MINUTE; t += 7) uneven.use(Math.min(7, 15 * MINUTE - t));
        check(uneven.debt == timer.debt, "Tick and threshold rounding cannot discard fractional debt");
        timer.use(5 * MINUTE);
        check(timer.resting && timer.alpha() == 1f && timer.remainingMillis() == 5 * MINUTE, "Full mask starts rest");
        timer.use(30_000);
        check(timer.resting && timer.remainingMillis() == 270_000, "Looking at the break screen earns rest");
        timer.resume();
        timer.use(75_000);
        check(timer.resting && timer.remainingMillis() == 5 * MINUTE, "Early return retains the balance");
        timer.use(5 * MINUTE);
        check(timer.resting && timer.remainingMillis() == 0, "Fully rested card waits for an explicit return");
        timer.resume();
        timer.use(1_000);
        check(!timer.resting && timer.remainingMillis() == 100, "Fresh session after full recovery");
        timer.recover(Long.MAX_VALUE);
        check(timer.debt == 0, "Long time away clamps to zero");
        timer.use(Long.MAX_VALUE);
        check(timer.resting && timer.remainingMillis() == 5 * MINUTE, "Long active gap never exceeds the cap");
        check(ChildgramBreakTimer.timeAway(1000, 10_000, 1, 2000, -200_000, 1) == 1000, "Wall clock changes on the same boot");
        check(ChildgramBreakTimer.timeAway(1000, 10_000, 1, 100, 90_000, 2) == 80_000, "Time powered off is a break");
        check(ChildgramBreakTimer.timeAway(1000, 10_000, 1, 2000, 100, 2) == 2000, "After reboot and clock rollback, count known uptime");
        check(ChildgramBreakTimer.timeAway(1000, 10_000, -1, 2000, 100, -1) == 1000, "Android 6 uses monotonic time");
        check(new ChildgramBreakTimer(10, 10, 5, 1_800_000).alpha() == .25f, "Legacy default checkpoint restores unchanged");
        customProfile(1, 1, 1);
        customProfile(7, 11, 13);
        customProfile(1, 240, 240);
        customProfile(240, 1, 240);
        customProfile(239, 237, 233);
        System.out.println("Childgram break timer checks passed");
    }

    private static void customProfile(int start, int fade, int rest) {
        ChildgramBreakTimer timer = new ChildgramBreakTimer(start, fade, rest, 0);
        timer.use(start * MINUTE - 1);
        check(!timer.showHint(), "No early hint");
        timer.use(1);
        check(timer.showHint() && timer.alpha() == 0 && timer.remainingMillis() == rest * MINUTE / 5,
                "First hint is one fifth of configured recovery");
        timer.use(fade * MINUTE / 2);
        check(timer.alpha() == .25f && timer.remainingMillis() == 3 * rest * MINUTE / 5, "Configured fade midpoint");
        timer.use(fade * MINUTE / 2 - 1);
        check(!timer.resting, "Content remains available before the configured deadline");
        timer.use(1);
        check(timer.resting && timer.remainingMillis() == rest * MINUTE, "All three configured timing boundaries");
        timer.recover(rest * MINUTE - 1);
        check(timer.remainingMillis() == 1, "One millisecond still needed");
        timer.recover(1);
        check(timer.remainingMillis() == 0, "Configured recovery completes exactly");

        ChildgramBreakTimer whole = new ChildgramBreakTimer(start, fade, rest, 0);
        whole.use(start * MINUTE);
        whole.recover(13);
        ChildgramBreakTimer split = new ChildgramBreakTimer(start, fade, rest, whole.debt);
        whole.use(7_891);
        for (int i = 0; i < 7_891; ++i) split.use(1);
        check(whole.debt == split.debt, "Uneven fractional crossing agrees for 1 ms and long ticks");
    }
}
