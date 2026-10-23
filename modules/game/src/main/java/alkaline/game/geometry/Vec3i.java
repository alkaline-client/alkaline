package alkaline.game.geometry;

public class Vec3i implements Comparable<Vec3i> {
    public static final Vec3i NULL_VECTOR = new Vec3i(0, 0, 0);

    private final int x;
    private final int y;
    private final int z;

    public Vec3i(int xIn, int yIn, int zIn) {
        this.x = xIn;
        this.y = yIn;
        this.z = zIn;
    }

    public Vec3i(double xIn, double yIn, double zIn) {
        this(floorDouble(xIn), floorDouble(yIn), floorDouble(zIn));
    }

    private static int floorDouble(double value) {
        int truncated = (int) value;
        return value < (double) truncated ? truncated - 1 : truncated;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Vec3i vec3i)) {
            return false;
        }
        return this.getX() == vec3i.getX()
                && this.getY() == vec3i.getY()
                && this.getZ() == vec3i.getZ();
    }

    @Override
    public int hashCode() {
        return (this.getY() + this.getZ() * 31) * 31 + this.getX();
    }

    @Override
    public int compareTo(Vec3i other) {
        return this.getY() == other.getY()
                ? (this.getZ() == other.getZ() ? this.getX() - other.getX() : this.getZ() - other.getZ())
                : this.getY() - other.getY();
    }

    public int getX() {
        return this.x;
    }

    public int getY() {
        return this.y;
    }

    public int getZ() {
        return this.z;
    }

    public Vec3i crossProduct(Vec3i vec) {
        return new Vec3i(
                this.getY() * vec.getZ() - this.getZ() * vec.getY(),
                this.getZ() * vec.getX() - this.getX() * vec.getZ(),
                this.getX() * vec.getY() - this.getY() * vec.getX());
    }

    public double distanceSq(double toX, double toY, double toZ) {
        double dx = (double) this.getX() - toX;
        double dy = (double) this.getY() - toY;
        double dz = (double) this.getZ() - toZ;
        return dx * dx + dy * dy + dz * dz;
    }

    public double distanceSqToCenter(double xIn, double yIn, double zIn) {
        double dx = (double) this.getX() + 0.5D - xIn;
        double dy = (double) this.getY() + 0.5D - yIn;
        double dz = (double) this.getZ() + 0.5D - zIn;
        return dx * dx + dy * dy + dz * dz;
    }

    public double distanceSq(Vec3i to) {
        return this.distanceSq((double) to.getX(), (double) to.getY(), (double) to.getZ());
    }

    @Override
    public String toString() {
        return "Vec3i{x=" + this.getX() + ", y=" + this.getY() + ", z=" + this.getZ() + "}";
    }
}
