package alkaline.game.geometry;

public class Vector3D {
    public final double x;
    public final double y;
    public final double z;

    public Vector3D(double x, double y, double z) {
        this.x = x == -0.0D ? 0.0D : x;
        this.y = y == -0.0D ? 0.0D : y;
        this.z = z == -0.0D ? 0.0D : z;
    }

    public Vector3D(Vec3i vec3i) {
        this(vec3i.getX(), vec3i.getY(), vec3i.getZ());
    }

    public Vector3D subtractReverse(Vector3D other) {
        return new Vector3D(other.x - this.x, other.y - this.y, other.z - this.z);
    }

    public Vector3D normalize() {
        double length = Math.sqrt(this.x * this.x + this.y * this.y + this.z * this.z);
        return length < 1.0E-4D
                ? new Vector3D(0.0D, 0.0D, 0.0D)
                : new Vector3D(this.x / length, this.y / length, this.z / length);
    }

    public double dotProduct(Vector3D other) {
        return this.x * other.x + this.y * other.y + this.z * other.z;
    }

    public Vector3D crossProduct(Vector3D other) {
        return new Vector3D(this.y * other.z - this.z * other.y,
                this.z * other.x - this.x * other.z, this.x * other.y - this.y * other.x);
    }

    public Vector3D subtract(Vector3D other) {
        return this.subtract(other.x, other.y, other.z);
    }

    public Vector3D subtract(double x, double y, double z) {
        return this.addVector(-x, -y, -z);
    }

    public Vector3D add(Vector3D other) {
        return this.addVector(other.x, other.y, other.z);
    }

    public Vector3D addVector(double x, double y, double z) {
        return new Vector3D(this.x + x, this.y + y, this.z + z);
    }

    public double distanceTo(Vector3D other) {
        double dx = other.x - this.x;
        double dy = other.y - this.y;
        double dz = other.z - this.z;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    public double squareDistanceTo(Vector3D other) {
        double dx = other.x - this.x;
        double dy = other.y - this.y;
        double dz = other.z - this.z;
        return dx * dx + dy * dy + dz * dz;
    }

    public double length() {
        return Math.sqrt(this.x * this.x + this.y * this.y + this.z * this.z);
    }

    public Vector3D getIntermediateWithXValue(Vector3D other, double x) {
        double dx = other.x - this.x;
        double dy = other.y - this.y;
        double dz = other.z - this.z;
        if (dx == 0.0D) {
            return null;
        }
        double fraction = (x - this.x) / dx;
        return fraction >= 0.0D && fraction <= 1.0D
                ? new Vector3D(x, this.y + dy * fraction, this.z + dz * fraction)
                : null;
    }

    public Vector3D getIntermediateWithYValue(Vector3D other, double y) {
        double dx = other.x - this.x;
        double dy = other.y - this.y;
        double dz = other.z - this.z;
        if (dy == 0.0D) {
            return null;
        }
        double fraction = (y - this.y) / dy;
        return fraction >= 0.0D && fraction <= 1.0D
                ? new Vector3D(this.x + dx * fraction, y, this.z + dz * fraction)
                : null;
    }

    public Vector3D getIntermediateWithZValue(Vector3D other, double z) {
        double dx = other.x - this.x;
        double dy = other.y - this.y;
        double dz = other.z - this.z;
        if (dz == 0.0D) {
            return null;
        }
        double fraction = (z - this.z) / dz;
        return fraction >= 0.0D && fraction <= 1.0D
                ? new Vector3D(this.x + dx * fraction, this.y + dy * fraction, z)
                : null;
    }

    @Override
    public String toString() {
        return "(" + this.x + ", " + this.y + ", " + this.z + ")";
    }

    public Vector3D rotatePitch(float pitch) {
        float cos = (float) Math.cos(pitch);
        float sin = (float) Math.sin(pitch);
        double newY = this.y * cos + this.z * sin;
        double newZ = this.z * cos - this.y * sin;
        return new Vector3D(this.x, newY, newZ);
    }

    public Vector3D rotateYaw(float yaw) {
        float cos = (float) Math.cos(yaw);
        float sin = (float) Math.sin(yaw);
        double newX = this.x * cos + this.z * sin;
        double newZ = this.z * cos - this.x * sin;
        return new Vector3D(newX, this.y, newZ);
    }
}
