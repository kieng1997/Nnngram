package xyz.nextalone.nnngram.helpers;

/**
 * Decides whether a selected message may be saved to the device.
 *
 * <p>Telegram restricts saving in two independent ways: a chat-wide noforwards flag and a
 * per-message {@code noforwards} flag (what a bot's protect_content sets). The selection action
 * bar used to look only at the chat-wide one, so a protected document or audio from a bot could
 * still be saved. View-once media is not saveable regardless.</p>
 *
 * <p>The fork setting {@code ignoreChatStrict} deliberately lifts both noforwards restrictions,
 * but never the view-once restriction.</p>
 */
public final class MessageSaveEligibility {

    private MessageSaveEligibility() {
    }

    public static boolean canSave(boolean ignoreChatStrict, boolean peerNoForwards, boolean messageNoForwards,
                                  boolean roundOnce, boolean voiceOnce) {
        return (ignoreChatStrict || !peerNoForwards && !messageNoForwards) && !roundOnce && !voiceOnce;
    }
}
