package alkaline.game.player;

/**
 * Minecraft's {@code leftClickCounter}: after a left click that finds nothing to hit in survival, attacking and
 * digging are blocked for 10 ticks. It is pinned high while a screen is open and zeroed on every tick the attack key
 * is not held, so only holding the key (or tapping faster than a tick) feels it. Game-thread state.
 */
public final class AttackCooldown {
    /** The value the counter takes while a screen has the input (Minecraft.runTick, setIngameFocus). */
    public static final int SCREEN = 10000;
    /** Ticks a miss blocks clicks for. */
    public static final int MISS = 10;

    private int counter;

    /** Whether Minecraft.clickMouse would act now (it does nothing while the counter runs). */
    public boolean ready() {
        return counter <= 0;
    }

    /** The counter, for tests and diagnostics. */
    public int counter() {
        return counter;
    }

    /**
     * The start of Minecraft.runTick's input step: an open screen pins the counter, then it counts down one.
     *
     * @param screenOpen whether a screen, menu or the pause card has the input this tick
     */
    public void tick(boolean screenOpen) {
        if (screenOpen) {
            counter = SCREEN;
        }
        if (counter > 0) {
            counter--;
        }
    }

    /**
     * A click that hit neither an entity nor a block (clickMouse's miss branch): survival and adventure players
     * wait {@link #MISS} ticks, creative players do not.
     */
    public void missed(boolean creative) {
        if (!creative) {
            counter = MISS;
        }
    }

    /**
     * The end of the tick, sendClickBlockToController: with the attack key up the counter is cleared. Returns whether
     * holding the key may dig this tick.
     */
    public boolean sustain(boolean attackHeld) {
        if (!attackHeld) {
            counter = 0;
        }
        return counter <= 0;
    }
}
