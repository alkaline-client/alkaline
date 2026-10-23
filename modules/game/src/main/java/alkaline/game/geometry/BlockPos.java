package alkaline.game.geometry;

import java.util.Iterator;
import java.util.NoSuchElementException;

public class BlockPos extends Vec3i {
    public static final BlockPos ORIGIN = new BlockPos(0, 0, 0);

    private static final int NUM_X_BITS = 26;
    private static final int NUM_Z_BITS = NUM_X_BITS;
    private static final int NUM_Y_BITS = 64 - NUM_X_BITS - NUM_Z_BITS;
    private static final int Y_SHIFT = NUM_Z_BITS;
    private static final int X_SHIFT = Y_SHIFT + NUM_Y_BITS;
    private static final long X_MASK = (1L << NUM_X_BITS) - 1L;
    private static final long Y_MASK = (1L << NUM_Y_BITS) - 1L;
    private static final long Z_MASK = (1L << NUM_Z_BITS) - 1L;

    public BlockPos(int x, int y, int z) {
        super(x, y, z);
    }

    public BlockPos(double x, double y, double z) {
        super(x, y, z);
    }

    public BlockPos(Vector3D source) {
        this(source.x, source.y, source.z);
    }

    public BlockPos(Vec3i source) {
        this(source.getX(), source.getY(), source.getZ());
    }

    public BlockPos add(double x, double y, double z) {
        return x == 0.0D && y == 0.0D && z == 0.0D
                ? this : new BlockPos((double) this.getX() + x, (double) this.getY() + y, (double) this.getZ() + z);
    }

    public BlockPos add(int x, int y, int z) {
        return x == 0 && y == 0 && z == 0
                ? this : new BlockPos(this.getX() + x, this.getY() + y, this.getZ() + z);
    }

    public BlockPos add(Vec3i vec) {
        return vec.getX() == 0 && vec.getY() == 0 && vec.getZ() == 0
                ? this : new BlockPos(this.getX() + vec.getX(), this.getY() + vec.getY(), this.getZ() + vec.getZ());
    }

    public BlockPos subtract(Vec3i vec) {
        return vec.getX() == 0 && vec.getY() == 0 && vec.getZ() == 0
                ? this : new BlockPos(this.getX() - vec.getX(), this.getY() - vec.getY(), this.getZ() - vec.getZ());
    }

    public BlockPos up() {
        return this.up(1);
    }

    public BlockPos up(int n) {
        return this.offset(EnumFacing.UP, n);
    }

    public BlockPos down() {
        return this.down(1);
    }

    public BlockPos down(int n) {
        return this.offset(EnumFacing.DOWN, n);
    }

    public BlockPos north() {
        return this.north(1);
    }

    public BlockPos north(int n) {
        return this.offset(EnumFacing.NORTH, n);
    }

    public BlockPos south() {
        return this.south(1);
    }

    public BlockPos south(int n) {
        return this.offset(EnumFacing.SOUTH, n);
    }

    public BlockPos west() {
        return this.west(1);
    }

    public BlockPos west(int n) {
        return this.offset(EnumFacing.WEST, n);
    }

    public BlockPos east() {
        return this.east(1);
    }

    public BlockPos east(int n) {
        return this.offset(EnumFacing.EAST, n);
    }

    public BlockPos offset(EnumFacing facing) {
        return this.offset(facing, 1);
    }

    public BlockPos offset(EnumFacing facing, int n) {
        return n == 0 ? this : new BlockPos(this.getX() + facing.getFrontOffsetX() * n,
                this.getY() + facing.getFrontOffsetY() * n, this.getZ() + facing.getFrontOffsetZ() * n);
    }

    @Override
    public BlockPos crossProduct(Vec3i vec) {
        return new BlockPos(this.getY() * vec.getZ() - this.getZ() * vec.getY(),
                this.getZ() * vec.getX() - this.getX() * vec.getZ(),
                this.getX() * vec.getY() - this.getY() * vec.getX());
    }

    public long toLong() {
        return ((long) this.getX() & X_MASK) << X_SHIFT
                | ((long) this.getY() & Y_MASK) << Y_SHIFT
                | ((long) this.getZ() & Z_MASK);
    }

    public static BlockPos fromLong(long serialized) {
        int x = (int) (serialized << 64 - X_SHIFT - NUM_X_BITS >> 64 - NUM_X_BITS);
        int y = (int) (serialized << 64 - Y_SHIFT - NUM_Y_BITS >> 64 - NUM_Y_BITS);
        int z = (int) (serialized << 64 - NUM_Z_BITS >> 64 - NUM_Z_BITS);
        return new BlockPos(x, y, z);
    }

    public static Iterable<BlockPos> getAllInBox(BlockPos from, BlockPos to) {
        BlockPos min = minimum(from, to);
        BlockPos max = maximum(from, to);
        return () -> new Iterator<>() {
            private BlockPos lastReturned;

            @Override
            public boolean hasNext() {
                return this.lastReturned == null || !this.lastReturned.equals(max);
            }

            @Override
            public BlockPos next() {
                if (!this.hasNext()) {
                    throw new NoSuchElementException();
                }
                if (this.lastReturned == null) {
                    this.lastReturned = min;
                } else {
                    this.lastReturned = nextPosition(this.lastReturned, min, max);
                }
                return this.lastReturned;
            }
        };
    }

    public static Iterable<MutableBlockPos> getAllInBoxMutable(BlockPos from, BlockPos to) {
        BlockPos min = minimum(from, to);
        BlockPos max = maximum(from, to);
        return () -> new Iterator<>() {
            private MutableBlockPos lastReturned;

            @Override
            public boolean hasNext() {
                return this.lastReturned == null || !this.lastReturned.equals(max);
            }

            @Override
            public MutableBlockPos next() {
                if (!this.hasNext()) {
                    throw new NoSuchElementException();
                }
                if (this.lastReturned == null) {
                    this.lastReturned = new MutableBlockPos(min.getX(), min.getY(), min.getZ());
                } else {
                    BlockPos next = nextPosition(this.lastReturned, min, max);
                    this.lastReturned.set(next.getX(), next.getY(), next.getZ());
                }
                return this.lastReturned;
            }
        };
    }

    private static BlockPos minimum(BlockPos first, BlockPos second) {
        return new BlockPos(Math.min(first.getX(), second.getX()), Math.min(first.getY(), second.getY()),
                Math.min(first.getZ(), second.getZ()));
    }

    private static BlockPos maximum(BlockPos first, BlockPos second) {
        return new BlockPos(Math.max(first.getX(), second.getX()), Math.max(first.getY(), second.getY()),
                Math.max(first.getZ(), second.getZ()));
    }

    private static BlockPos nextPosition(BlockPos last, BlockPos min, BlockPos max) {
        int x = last.getX();
        int y = last.getY();
        int z = last.getZ();
        if (x < max.getX()) {
            ++x;
        } else if (y < max.getY()) {
            x = min.getX();
            ++y;
        } else if (z < max.getZ()) {
            x = min.getX();
            y = min.getY();
            ++z;
        }
        return new BlockPos(x, y, z);
    }

    public static final class MutableBlockPos extends BlockPos {
        private int x;
        private int y;
        private int z;

        public MutableBlockPos() {
            this(0, 0, 0);
        }

        public MutableBlockPos(int x, int y, int z) {
            super(0, 0, 0);
            this.x = x;
            this.y = y;
            this.z = z;
        }

        @Override
        public int getX() {
            return this.x;
        }

        @Override
        public int getY() {
            return this.y;
        }

        @Override
        public int getZ() {
            return this.z;
        }

        public MutableBlockPos set(int x, int y, int z) {
            this.x = x;
            this.y = y;
            this.z = z;
            return this;
        }
    }
}
