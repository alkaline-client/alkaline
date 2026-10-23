package alkaline.game.geometry;

import java.util.Iterator;
import java.util.Locale;
import java.util.Random;

public enum EnumFacing implements IStringSerializable {
    DOWN(0, 1, -1, "down", AxisDirection.NEGATIVE, Axis.Y, new Vec3i(0, -1, 0)),
    UP(1, 0, -1, "up", AxisDirection.POSITIVE, Axis.Y, new Vec3i(0, 1, 0)),
    NORTH(2, 3, 2, "north", AxisDirection.NEGATIVE, Axis.Z, new Vec3i(0, 0, -1)),
    SOUTH(3, 2, 0, "south", AxisDirection.POSITIVE, Axis.Z, new Vec3i(0, 0, 1)),
    WEST(4, 5, 1, "west", AxisDirection.NEGATIVE, Axis.X, new Vec3i(-1, 0, 0)),
    EAST(5, 4, 3, "east", AxisDirection.POSITIVE, Axis.X, new Vec3i(1, 0, 0));

    private final int index;
    private final int opposite;
    private final int horizontalIndex;
    private final String name;
    private final Axis axis;
    private final AxisDirection axisDirection;
    private final Vec3i directionVec;

    public static final EnumFacing[] VALUES = new EnumFacing[6];
    private static final EnumFacing[] HORIZONTALS = new EnumFacing[4];

    EnumFacing(int indexIn, int oppositeIn, int horizontalIndexIn, String nameIn,
               AxisDirection axisDirectionIn, Axis axisIn, Vec3i directionVecIn) {
        this.index = indexIn;
        this.horizontalIndex = horizontalIndexIn;
        this.opposite = oppositeIn;
        this.name = nameIn;
        this.axis = axisIn;
        this.axisDirection = axisDirectionIn;
        this.directionVec = directionVecIn;
    }

    public int getIndex() {
        return this.index;
    }

    public int getHorizontalIndex() {
        return this.horizontalIndex;
    }

    public AxisDirection getAxisDirection() {
        return this.axisDirection;
    }

    public EnumFacing getOpposite() {
        return VALUES[this.opposite];
    }

    public EnumFacing rotateAround(Axis axis) {
        return switch (axis) {
            case X -> this != WEST && this != EAST ? this.rotateX() : this;
            case Y -> this != UP && this != DOWN ? this.rotateY() : this;
            case Z -> this != NORTH && this != SOUTH ? this.rotateZ() : this;
        };
    }

    public EnumFacing rotateY() {
        return switch (this) {
            case NORTH -> EAST;
            case EAST -> SOUTH;
            case SOUTH -> WEST;
            case WEST -> NORTH;
            default -> throw new IllegalStateException("Unable to get Y-rotated facing of " + this);
        };
    }

    private EnumFacing rotateX() {
        return switch (this) {
            case NORTH -> DOWN;
            case SOUTH -> UP;
            case UP -> NORTH;
            case DOWN -> SOUTH;
            default -> throw new IllegalStateException("Unable to get X-rotated facing of " + this);
        };
    }

    private EnumFacing rotateZ() {
        return switch (this) {
            case EAST -> DOWN;
            case WEST -> UP;
            case UP -> EAST;
            case DOWN -> WEST;
            default -> throw new IllegalStateException("Unable to get Z-rotated facing of " + this);
        };
    }

    public EnumFacing rotateYCCW() {
        return switch (this) {
            case NORTH -> WEST;
            case EAST -> NORTH;
            case SOUTH -> EAST;
            case WEST -> SOUTH;
            default -> throw new IllegalStateException("Unable to get CCW facing of " + this);
        };
    }

    public int getFrontOffsetX() {
        return this.axis == Axis.X ? this.axisDirection.getOffset() : 0;
    }

    public int getFrontOffsetY() {
        return this.axis == Axis.Y ? this.axisDirection.getOffset() : 0;
    }

    public int getFrontOffsetZ() {
        return this.axis == Axis.Z ? this.axisDirection.getOffset() : 0;
    }

    public String getName2() {
        return this.name;
    }

    public Axis getAxis() {
        return this.axis;
    }

    public static EnumFacing byName(String name) {
        if (name == null) {
            return null;
        }
        for (EnumFacing facing : values()) {
            if (facing.name.equals(name.toLowerCase(Locale.ROOT))) {
                return facing;
            }
        }
        return null;
    }

    public static EnumFacing getFront(int index) {
        return VALUES[Math.abs(index % VALUES.length)];
    }

    public static EnumFacing getHorizontal(int index) {
        return HORIZONTALS[Math.abs(index % HORIZONTALS.length)];
    }

    public static EnumFacing fromAngle(double angle) {
        return getHorizontal(floorDouble(angle / 90.0D + 0.5D) & 3);
    }

    private static int floorDouble(double value) {
        int truncated = (int) value;
        return value < (double) truncated ? truncated - 1 : truncated;
    }

    public static EnumFacing random(Random rand) {
        return values()[rand.nextInt(values().length)];
    }

    public static EnumFacing getFacingFromVector(double x, double y, double z) {
        EnumFacing bestMatch = null;
        float maxDotProduct = Float.MIN_VALUE;
        for (EnumFacing currentFacing : values()) {
            float dotProduct = (float) (x * currentFacing.directionVec.getX()
                    + y * currentFacing.directionVec.getY() + z * currentFacing.directionVec.getZ());
            if (dotProduct > maxDotProduct) {
                maxDotProduct = dotProduct;
                bestMatch = currentFacing;
            }
        }
        return bestMatch;
    }

    @Override
    public String toString() {
        return this.name;
    }

    @Override
    public String getName() {
        return this.name;
    }

    public static EnumFacing getFacingFromAxis(AxisDirection direction, Axis axis) {
        for (EnumFacing facing : values()) {
            if (facing.getAxisDirection() == direction && facing.getAxis() == axis) {
                return facing;
            }
        }
        throw new IllegalArgumentException("No such direction: " + direction + " " + axis);
    }

    public Vec3i getDirectionVec() {
        return this.directionVec;
    }

    static {
        for (EnumFacing facing : values()) {
            VALUES[facing.index] = facing;
            if (facing.getAxis().isHorizontal()) {
                HORIZONTALS[facing.horizontalIndex] = facing;
            }
        }
    }

    public enum Axis implements IStringSerializable {
        X("x", Plane.HORIZONTAL),
        Y("y", Plane.VERTICAL),
        Z("z", Plane.HORIZONTAL);

        private final String name;
        private final Plane plane;

        Axis(String name, Plane plane) {
            this.name = name;
            this.plane = plane;
        }

        public static Axis byName(String name) {
            if (name == null) {
                return null;
            }
            for (Axis axis : values()) {
                if (axis.name.equals(name.toLowerCase(Locale.ROOT))) {
                    return axis;
                }
            }
            return null;
        }

        public String getName2() {
            return this.name;
        }

        public boolean isVertical() {
            return this.plane == Plane.VERTICAL;
        }

        public boolean isHorizontal() {
            return this.plane == Plane.HORIZONTAL;
        }

        @Override
        public String toString() {
            return this.name;
        }

        public boolean apply(EnumFacing facing) {
            return facing != null && facing.getAxis() == this;
        }

        public Plane getPlane() {
            return this.plane;
        }

        @Override
        public String getName() {
            return this.name;
        }
    }

    public enum AxisDirection {
        POSITIVE(1, "Towards positive"),
        NEGATIVE(-1, "Towards negative");

        private final int offset;
        private final String description;

        AxisDirection(int offset, String description) {
            this.offset = offset;
            this.description = description;
        }

        public int getOffset() {
            return this.offset;
        }

        @Override
        public String toString() {
            return this.description;
        }
    }

    public enum Plane implements Iterable<EnumFacing> {
        HORIZONTAL,
        VERTICAL;

        public EnumFacing[] facings() {
            return switch (this) {
                case HORIZONTAL -> new EnumFacing[]{NORTH, EAST, SOUTH, WEST};
                case VERTICAL -> new EnumFacing[]{UP, DOWN};
            };
        }

        public EnumFacing random(Random rand) {
            EnumFacing[] facings = this.facings();
            return facings[rand.nextInt(facings.length)];
        }

        public boolean apply(EnumFacing facing) {
            return facing != null && facing.getAxis().getPlane() == this;
        }

        @Override
        public Iterator<EnumFacing> iterator() {
            return java.util.Arrays.asList(this.facings()).iterator();
        }
    }
}
