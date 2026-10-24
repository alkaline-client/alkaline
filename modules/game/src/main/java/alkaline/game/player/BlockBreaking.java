package alkaline.game.player;

import alkaline.game.inventory.ItemStack;
import alkaline.game.inventory.Items;
import java.util.Objects;

/**
 * The client's side of digging, as PlayerControllerMP does it: {@link #click} starts a dig (Player Digging status
 * 0, or finishes at once when one tick is enough), {@link #damage} adds a tick of progress while the attack key
 * is held (and sends status 2 and removes the block at 100%), and {@link #reset} aborts. The crack stage
 * {@code (int) (progress * 10) - 1} is reported for the renderer after every change. Creative mode destroys on
 * every click and repeats every five ticks while held.
 */
public final class BlockBreaking {
    /** Player Digging statuses (ServerSession.DIG_START, DIG_ABORT, DIG_FINISH). */
    public static final int START = 0;
    public static final int ABORT = 1;
    public static final int FINISH = 2;

    /** What digging needs from the game around it. */
    public interface Host {
        /** The block state ({@code id << 4 | metadata}) at a position. */
        int state(int x, int y, int z);

        /** The stack in the selected hotbar slot, or null. */
        ItemStack held();

        /** Sends a Player Digging packet. */
        void send(int status, int x, int y, int z, int face);

        /** Block.getPlayerRelativeBlockHardness for a state: the share of the block one tick removes. */
        float progress(int state);

        /** WorldClient.sendBlockBreakProgress for the local player: stage 0..9 shows cracks, anything else clears. */
        void stage(int x, int y, int z, int stage);

        /** PlayerControllerMP.onPlayerDestroyBlock's effects: the block's debris and its removal from the world. */
        void destroyed(int x, int y, int z, int state);

        /** The sound of a block being hit, every fourth tick of digging. */
        void hit(int x, int y, int z, int state);
    }

    private final Host host;
    private int x = -1;
    private int y = -1;
    private int z = -1;
    private ItemStack heldWhenStarted;
    private float damage;
    private int soundTicks;
    private int hitDelay;
    private boolean hitting;

    public BlockBreaking(Host host) {
        this.host = Objects.requireNonNull(host, "host");
    }

    /** PlayerControllerMP.getIsHittingBlock: the use key does nothing while a dig is under way. */
    public boolean hitting() {
        return hitting;
    }

    /** The progress so far, 0..1. */
    public float damage() {
        return damage;
    }

    /**
     * PlayerControllerMP.clickBlock: the attack key went down on a block. {@code face} is the EnumFacing index
     * (0 down ... 5 east). Returns true; digging a block that is already being dug does nothing.
     */
    public boolean click(int bx, int by, int bz, int face, boolean creative) {
        if (creative) {
            host.send(START, bx, by, bz, face);
            destroy(bx, by, bz, true);
            hitDelay = 5;
        } else if (!hitting || !isHitting(bx, by, bz)) {
            if (hitting) {
                host.send(ABORT, x, y, z, face);
            }
            host.send(START, bx, by, bz, face);
            int state = host.state(bx, by, bz);
            boolean solid = state >> 4 != 0;
            if (solid && host.progress(state) >= 1f) {
                destroy(bx, by, bz, false);
            } else {
                hitting = true;
                x = bx;
                y = by;
                z = bz;
                heldWhenStarted = host.held();
                damage = 0f;
                soundTicks = 0;
                host.stage(x, y, z, (int) (damage * 10f) - 1);
            }
        }
        return true;
    }

    /** PlayerControllerMP.resetBlockRemoving: the attack key was released or the crosshair left the block. */
    public void reset() {
        if (hitting) {
            host.send(ABORT, x, y, z, 0);
            hitting = false;
            damage = 0f;
            host.stage(x, y, z, -1);
        }
    }

    /**
     * PlayerControllerMP.onPlayerDamageBlock, once per tick with the attack key held on a block: another tick of
     * progress, or a new dig when the crosshair moved. Returns whether the block took the hit (hit particles and
     * the arm swing follow).
     */
    public boolean damage(int bx, int by, int bz, int face, boolean creative) {
        if (hitDelay > 0) {
            hitDelay--;
            return true;
        }
        if (creative) {
            hitDelay = 5;
            host.send(START, bx, by, bz, face);
            destroy(bx, by, bz, true);
            return true;
        }
        if (!isHitting(bx, by, bz)) {
            return click(bx, by, bz, face, false);
        }
        int state = host.state(bx, by, bz);
        if (state >> 4 == 0) {
            hitting = false;
            return false;
        }
        damage += host.progress(state);
        if (soundTicks % 4 == 0) {
            host.hit(bx, by, bz, state);
        }
        soundTicks++;
        if (damage >= 1f) {
            hitting = false;
            host.send(FINISH, bx, by, bz, face);
            destroy(bx, by, bz, false);
            damage = 0f;
            soundTicks = 0;
            hitDelay = 5;
        }
        host.stage(x, y, z, (int) (damage * 10f) - 1);
        return true;
    }

    /**
     * onPlayerDestroyBlock: no effect on air, and none in creative mode with a sword in hand; the dig's block is
     * forgotten (y -1) whatever was destroyed.
     */
    private void destroy(int bx, int by, int bz, boolean creative) {
        int state = host.state(bx, by, bz);
        ItemStack held = host.held();
        if (state >> 4 == 0 || creative && held != null && sword(held.id())) {
            return;
        }
        y = -1;
        host.destroyed(bx, by, bz, state);
    }

    private static boolean sword(int item) {
        return item == 267 || item == 268 || item == 272 || item == 276 || item == 283;
    }

    /** PlayerControllerMP.isHittingPosition: the same block, with the same item still in hand. */
    private boolean isHitting(int bx, int by, int bz) {
        ItemStack held = host.held();
        boolean sameItem;
        if (heldWhenStarted == null || held == null) {
            sameItem = heldWhenStarted == null && held == null;
        } else {
            sameItem = held.id() == heldWhenStarted.id() && Objects.equals(held.tag(), heldWhenStarted.tag())
                    && (Items.damageable(held.id()) || held.damage() == heldWhenStarted.damage());
        }
        return bx == x && by == y && bz == z && sameItem;
    }
}
