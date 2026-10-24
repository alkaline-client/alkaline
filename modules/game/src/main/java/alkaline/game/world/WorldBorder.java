package alkaline.game.world;

import alkaline.game.net.ServerSession;
import java.util.function.LongSupplier;

/**
 * The world border as the client keeps it (net.minecraft.world.border.WorldBorder): a square about a center whose
 * diameter is steady or moves linearly to a target over the time the server gave, plus the warning distance and
 * time the vignette uses. Game-thread state.
 */
public final class WorldBorder {
    /** EnumBorderStatus: the wall's tint is {@link #color} of it. */
    public enum Status {
        GROWING(4259712), SHRINKING(16724016), STATIONARY(2138367);

        private final int color;

        Status(int color) {
            this.color = color;
        }

        /** The wall's 0xRRGGBB tint. */
        public int color() {
            return color;
        }
    }

    private final LongSupplier clock;
    private double centerX;
    private double centerZ;
    private double startDiameter = 6.0E7;
    private double endDiameter = startDiameter;
    private long startTime;
    private long endTime;
    private int worldSize = 29999984;
    private int warningTime = 15;
    private int warningDistance = 5;

    public WorldBorder() {
        this(System::currentTimeMillis);
    }

    /** @param clock milliseconds, as System.currentTimeMillis (replaceable for tests) */
    public WorldBorder(LongSupplier clock) {
        this.clock = clock;
    }

    /** Applies a World Border packet (S44PacketWorldBorder.func_179788_a). */
    public void apply(ServerSession.WorldBorder packet) {
        switch (packet.action()) {
            case 0 -> transition(packet.newDiameter());
            case 1 -> transition(packet.oldDiameter(), packet.newDiameter(), packet.speedMillis());
            case 2 -> center(packet.centerX(), packet.centerZ());
            case 3 -> {
                center(packet.centerX(), packet.centerZ());
                if (packet.speedMillis() > 0L) {
                    transition(packet.oldDiameter(), packet.newDiameter(), packet.speedMillis());
                } else {
                    transition(packet.newDiameter());
                }
                worldSize = packet.portalBoundary();
                warningDistance = packet.warningBlocks();
                warningTime = packet.warningTime();
            }
            case 4 -> warningTime = packet.warningTime();
            case 5 -> warningDistance = packet.warningBlocks();
            default -> {
            }
        }
    }

    public void center(double x, double z) {
        centerX = x;
        centerZ = z;
    }

    /** setTransition(size): a steady border. */
    public void transition(double size) {
        startDiameter = size;
        endDiameter = size;
        startTime = endTime = clock.getAsLong();
    }

    /** setTransition(old, new, time): a diameter moving linearly over {@code millis}. */
    public void transition(double from, double to, long millis) {
        startDiameter = from;
        endDiameter = to;
        startTime = clock.getAsLong();
        endTime = startTime + millis;
    }

    public Status status() {
        return endDiameter < startDiameter ? Status.SHRINKING
                : endDiameter > startDiameter ? Status.GROWING : Status.STATIONARY;
    }

    /** getDiameter: the float-precision fraction of the move is vanilla's. */
    public double diameter() {
        if (status() != Status.STATIONARY) {
            double fraction = (float) (clock.getAsLong() - startTime) / (float) (endTime - startTime);
            if (fraction < 1.0) {
                return startDiameter + (endDiameter - startDiameter) * fraction;
            }
            transition(endDiameter);
        }
        return startDiameter;
    }

    public double targetSize() {
        return endDiameter;
    }

    /** Blocks per millisecond the diameter is changing by. */
    public double resizeSpeed() {
        return endTime == startTime ? 0.0 : Math.abs(startDiameter - endDiameter) / (endTime - startTime);
    }

    public int warningTime() {
        return warningTime;
    }

    public int warningDistance() {
        return warningDistance;
    }

    public double minX() {
        return Math.max(centerX - diameter() / 2.0, -worldSize);
    }

    public double minZ() {
        return Math.max(centerZ - diameter() / 2.0, -worldSize);
    }

    public double maxX() {
        return Math.min(centerX + diameter() / 2.0, worldSize);
    }

    public double maxZ() {
        return Math.min(centerZ + diameter() / 2.0, worldSize);
    }

    /** contains(BlockPos): the block overlaps the border's square. */
    public boolean contains(int x, int z) {
        return x + 1 > minX() && x < maxX() && z + 1 > minZ() && z < maxZ();
    }

    /** The distance from (x, z) to the nearest edge (negative outside). */
    public double closestDistance(double x, double z) {
        double distance = Math.min(x - minX(), maxX() - x);
        distance = Math.min(distance, z - minZ());
        return Math.min(distance, maxZ() - z);
    }

    /**
     * The warning the vignette shows (GuiIngame.renderVignette): 0 beyond the warning distance, up to 1 at the edge.
     * The distance counts as at least the warning blocks, and more while a shrinking border will arrive within the
     * warning time.
     */
    public float warning(double x, double z) {
        float distance = (float) closestDistance(x, z);
        double reach = Math.max(warningDistance,
                Math.min(resizeSpeed() * warningTime * 1000.0, Math.abs(targetSize() - diameter())));
        return distance < reach ? 1.0F - (float) (distance / reach) : 0.0F;
    }
}
