package alkaline.game.geometry;

public class AxisAlignedBB {
    public final double minX;
    public final double minY;
    public final double minZ;
    public final double maxX;
    public final double maxY;
    public final double maxZ;

    public AxisAlignedBB(double x1, double y1, double z1, double x2, double y2, double z2) {
        this.minX = Math.min(x1, x2);
        this.minY = Math.min(y1, y2);
        this.minZ = Math.min(z1, z2);
        this.maxX = Math.max(x1, x2);
        this.maxY = Math.max(y1, y2);
        this.maxZ = Math.max(z1, z2);
    }

    public AxisAlignedBB(BlockPos pos1, BlockPos pos2) {
        this.minX = pos1.getX();
        this.minY = pos1.getY();
        this.minZ = pos1.getZ();
        this.maxX = pos2.getX();
        this.maxY = pos2.getY();
        this.maxZ = pos2.getZ();
    }

    public AxisAlignedBB addCoord(double x, double y, double z) {
        return new AxisAlignedBB(this.minX + Math.min(x, 0.0D), this.minY + Math.min(y, 0.0D),
                this.minZ + Math.min(z, 0.0D), this.maxX + Math.max(x, 0.0D),
                this.maxY + Math.max(y, 0.0D), this.maxZ + Math.max(z, 0.0D));
    }

    public AxisAlignedBB expand(double x, double y, double z) {
        return new AxisAlignedBB(this.minX - x, this.minY - y, this.minZ - z,
                this.maxX + x, this.maxY + y, this.maxZ + z);
    }

    public AxisAlignedBB union(AxisAlignedBB other) {
        return new AxisAlignedBB(Math.min(this.minX, other.minX), Math.min(this.minY, other.minY),
                Math.min(this.minZ, other.minZ), Math.max(this.maxX, other.maxX),
                Math.max(this.maxY, other.maxY), Math.max(this.maxZ, other.maxZ));
    }

    public static AxisAlignedBB fromBounds(double x1, double y1, double z1, double x2, double y2, double z2) {
        return new AxisAlignedBB(x1, y1, z1, x2, y2, z2);
    }

    public AxisAlignedBB offset(double x, double y, double z) {
        return new AxisAlignedBB(this.minX + x, this.minY + y, this.minZ + z,
                this.maxX + x, this.maxY + y, this.maxZ + z);
    }

    public double calculateXOffset(AxisAlignedBB other, double proposedOffset) {
        if (other.maxY <= this.minY || other.minY >= this.maxY
                || other.maxZ <= this.minZ || other.minZ >= this.maxZ) {
            return proposedOffset;
        }
        double distanceToLeft = this.minX - other.maxX;
        double distanceToRight = this.maxX - other.minX;
        return proposedOffset > 0.0D && other.maxX <= this.minX
                ? Math.min(proposedOffset, distanceToLeft)
                : proposedOffset < 0.0D && other.minX >= this.maxX
                        ? Math.max(proposedOffset, distanceToRight) : proposedOffset;
    }

    public double calculateYOffset(AxisAlignedBB other, double proposedOffset) {
        if (other.maxX <= this.minX || other.minX >= this.maxX
                || other.maxZ <= this.minZ || other.minZ >= this.maxZ) {
            return proposedOffset;
        }
        if (proposedOffset > 0.0D && other.maxY <= this.minY) {
            return Math.min(proposedOffset, this.minY - other.maxY);
        }
        if (proposedOffset < 0.0D && other.minY >= this.maxY) {
            return Math.max(proposedOffset, this.maxY - other.minY);
        }
        return proposedOffset;
    }

    public double calculateZOffset(AxisAlignedBB other, double proposedOffset) {
        if (other.maxX <= this.minX || other.minX >= this.maxX
                || other.maxY <= this.minY || other.minY >= this.maxY) {
            return proposedOffset;
        }
        if (proposedOffset > 0.0D && other.maxZ <= this.minZ) {
            return Math.min(proposedOffset, this.minZ - other.maxZ);
        }
        if (proposedOffset < 0.0D && other.minZ >= this.maxZ) {
            return Math.max(proposedOffset, this.maxZ - other.minZ);
        }
        return proposedOffset;
    }

    public boolean intersectsWith(AxisAlignedBB other) {
        return other.maxX > this.minX && other.minX < this.maxX
                && other.maxY > this.minY && other.minY < this.maxY
                && other.maxZ > this.minZ && other.minZ < this.maxZ;
    }

    public boolean contains(Vector3D vec) {
        return vec.x > this.minX && vec.x < this.maxX
                && vec.y > this.minY && vec.y < this.maxY
                && vec.z > this.minZ && vec.z < this.maxZ;
    }

    public double getAverageEdgeLength() {
        double width = this.maxX - this.minX;
        double height = this.maxY - this.minY;
        double depth = this.maxZ - this.minZ;
        return (width + height + depth) / 3.0D;
    }

    public AxisAlignedBB contract(double x, double y, double z) {
        return new AxisAlignedBB(this.minX + x, this.minY + y, this.minZ + z,
                this.maxX - x, this.maxY - y, this.maxZ - z);
    }

    public RayIntersection calculateIntercept(Vector3D vecA, Vector3D vecB) {
        Vector3D[] intersections = new Vector3D[]{
                vecA.getIntermediateWithXValue(vecB, this.minX),
                vecA.getIntermediateWithXValue(vecB, this.maxX),
                vecA.getIntermediateWithYValue(vecB, this.minY),
                vecA.getIntermediateWithYValue(vecB, this.maxY),
                vecA.getIntermediateWithZValue(vecB, this.minZ),
                vecA.getIntermediateWithZValue(vecB, this.maxZ)
        };
        double[] distances = new double[intersections.length];
        for (int i = 0; i < intersections.length; i++) {
            if (this.isVecInBox(intersections[i])) {
                distances[i] = vecA.squareDistanceTo(intersections[i]);
            } else {
                intersections[i] = null;
                distances[i] = Double.MAX_VALUE;
            }
        }
        int minIndex = getMinIndex(distances);
        if (minIndex == -1) {
            return null;
        }
        return new RayIntersection(intersections[minIndex], getEnumFacing(minIndex));
    }

    private boolean isVecInBox(Vector3D vec) {
        return vec != null && vec.x >= this.minX && vec.x <= this.maxX
                && vec.y >= this.minY && vec.y <= this.maxY
                && vec.z >= this.minZ && vec.z <= this.maxZ;
    }

    private static int getMinIndex(double[] values) {
        int minIndex = -1;
        double minValue = Double.MAX_VALUE;
        for (int i = 0; i < values.length; i++) {
            if (values[i] < minValue) {
                minValue = values[i];
                minIndex = i;
            }
        }
        return minIndex;
    }

    private static EnumFacing getEnumFacing(int index) {
        return switch (index) {
            case 0 -> EnumFacing.WEST;
            case 1 -> EnumFacing.EAST;
            case 2 -> EnumFacing.DOWN;
            case 3 -> EnumFacing.UP;
            case 4 -> EnumFacing.NORTH;
            default -> EnumFacing.SOUTH;
        };
    }

    public boolean isVecInside(Vector3D vec) {
        return this.contains(vec);
    }

    @Override
    public String toString() {
        return "BoundingBox[" + this.minX + ", " + this.minY + ", " + this.minZ
                + " -> " + this.maxX + ", " + this.maxY + ", " + this.maxZ + "]";
    }

    public boolean hasNaN() {
        return Double.isNaN(this.minX) || Double.isNaN(this.minY) || Double.isNaN(this.minZ)
                || Double.isNaN(this.maxX) || Double.isNaN(this.maxY) || Double.isNaN(this.maxZ);
    }
}
