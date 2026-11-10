package xyz.nextalone.nnngram.helpers;

/**
 * Gate for the disguise screen: the real app unlocks only when three taps land
 * inside one short window. Plain JDK only, so no Android APIs may be used here.
 */
public final class DisguiseTapGate {

    private final long windowMs;
    private int count;
    private long firstTapAt;
    private boolean unlocked;

    public DisguiseTapGate(long windowMs) {
        this.windowMs = windowMs;
    }

    /**
     * Records a tap at {@code nowMs} (monotonic milliseconds) and returns true
     * only for the third tap inside the window. Returns false forever after it
     * has fired once, so a fourth fast tap cannot re-trigger it.
     */
    public boolean onTap(long nowMs) {
        if (unlocked) {
            return false;
        }
        if (count == 0 || nowMs - firstTapAt > windowMs) {
            firstTapAt = nowMs;
            count = 1;
            return false;
        }
        if (++count < 3) {
            return false;
        }
        unlocked = true;
        return true;
    }
}
