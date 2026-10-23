package alkaline.game.geometry;

public final class RayIntersection {
    private final Vector3D hitVec;
    private final EnumFacing sideHit;

    public RayIntersection(Vector3D hitVec, EnumFacing sideHit) {
        this.hitVec = new Vector3D(hitVec.x, hitVec.y, hitVec.z);
        this.sideHit = sideHit;
    }

    public Vector3D getHitVec() {
        return this.hitVec;
    }

    public EnumFacing getSideHit() {
        return this.sideHit;
    }

    @Override
    public String toString() {
        return "RayIntersection{side=" + this.sideHit + ", hit=" + this.hitVec + "}";
    }
}
