package xyz.nextalone.nnngram.helpers;

/**
 * Gate for the disguise screen: one long press arms it, then two taps inside the
 * following short window unlock the real app. Plain JDK only, so no Android APIs
 * may be used here.
 */
public final class DisguiseTapGate {

    private final long windowMs;
    private boolean armed;
    private long armedAt;
    private int count;
    private boolean unlocked;

    public DisguiseTapGate(long windowMs) {
        this.windowMs = windowMs;
    }

    /**
     * Arms the gate at {@code nowMs} (monotonic milliseconds) and restarts the tap
     * sequence, so taps collected before this press no longer count.
     */
    public void onLongPress(long nowMs) {
        if (unlocked) {
            return;
        }
        armed = true;
        armedAt = nowMs;
        count = 0;
    }

    /**
     * Records a tap at {@code nowMs} and returns true only for the second tap that
     * lands inside the window armed by the last long press. Taps without a valid
     * arm, or after the window, never unlock. Returns false forever after it has
     * fired once, so a further tap cannot re-trigger it.
     */
    public boolean onTap(long nowMs) {
        if (unlocked || !armed) {
            return false;
        }
        if (nowMs - armedAt > windowMs) {
            armed = false;
            count = 0;
            return false;
        }
        if (++count < 2) {
            return false;
        }
        unlocked = true;
        return true;
    }
}
