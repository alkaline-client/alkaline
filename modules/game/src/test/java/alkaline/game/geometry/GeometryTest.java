package alkaline.game.geometry;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeometryTest {
    @Test
    void packedCoordinatesRoundTripAcrossSignedBitRanges() {
        List<BlockPos> positions = List.of(
                new BlockPos(0, 0, 0),
                new BlockPos(-1, -1, -1),
                new BlockPos(-33_554_432, -2_048, -33_554_432),
                new BlockPos(33_554_431, 2_047, 33_554_431),
                new BlockPos(-12_345_678, 1_234, 23_456_789));

        for (BlockPos position : positions) {
            assertEquals(position, BlockPos.fromLong(position.toLong()), position.toString());
        }
    }

    @Test
    void boxEnumerationIsInclusiveWithXThenYThenZChangingFastest() {
        List<BlockPos> actual = new ArrayList<>();
        for (BlockPos position : BlockPos.getAllInBox(new BlockPos(1, 1, 1), new BlockPos(0, 0, 0))) {
            actual.add(position);
        }

        assertEquals(List.of(
                new BlockPos(0, 0, 0), new BlockPos(1, 0, 0),
                new BlockPos(0, 1, 0), new BlockPos(1, 1, 0),
                new BlockPos(0, 0, 1), new BlockPos(1, 0, 1),
                new BlockPos(0, 1, 1), new BlockPos(1, 1, 1)), actual);
    }

    @Test
    void floatingCoordinatesUseFloorRatherThanTruncation() {
        assertEquals(new Vec3i(-2, -1, 0), new Vec3i(-1.2D, -1.0D, -0.0D));
        assertEquals(new Vec3i(2, -2, 3), new BlockPos(2.9D, -1.1D, 3.0D));
        assertEquals(new BlockPos(-2, -1, 0), new BlockPos(-1.2D, -1.0D, -0.0D));
    }

    @Test
    void collisionOffsetsStopAtTheNearestObstacleOnEachAxis() {
        AxisAlignedBB obstacle = new AxisAlignedBB(0.0D, 0.0D, 0.0D, 1.0D, 1.0D, 1.0D);

        assertEquals(-0.2D, obstacle.calculateXOffset(
                new AxisAlignedBB(1.2D, 0.0D, 0.0D, 2.2D, 1.0D, 1.0D), -0.5D), 1.0E-12D);
        assertEquals(-0.2D, obstacle.calculateYOffset(
                new AxisAlignedBB(0.0D, 1.2D, 0.0D, 1.0D, 2.2D, 1.0D), -0.5D), 1.0E-12D);
        assertEquals(-0.2D, obstacle.calculateZOffset(
                new AxisAlignedBB(0.0D, 0.0D, 1.2D, 1.0D, 1.0D, 2.2D), -0.5D), 1.0E-12D);
        assertEquals(-0.5D, obstacle.calculateXOffset(
                new AxisAlignedBB(1.2D, 2.0D, 0.0D, 2.2D, 3.0D, 1.0D), -0.5D), 1.0E-12D);
        assertEquals(0.2D, obstacle.calculateXOffset(
                new AxisAlignedBB(-1.2D, 0.0D, 0.0D, -0.2D, 1.0D, 1.0D), 0.5D), 1.0E-12D);
    }

    @Test
    void intersectionsUseStrictBoxOverlapAndReturnNearestRayFace() {
        AxisAlignedBB box = new AxisAlignedBB(0.0D, 0.0D, 0.0D, 1.0D, 1.0D, 1.0D);
        assertTrue(box.intersectsWith(new AxisAlignedBB(0.5D, 0.5D, 0.5D, 2.0D, 2.0D, 2.0D)));
        assertFalse(box.intersectsWith(new AxisAlignedBB(1.0D, 0.0D, 0.0D, 2.0D, 1.0D, 1.0D)));

        RayIntersection intersection = box.calculateIntercept(
                new Vector3D(-1.0D, 0.5D, 0.5D), new Vector3D(2.0D, 0.5D, 0.5D));
        assertNotNull(intersection);
        assertEquals(EnumFacing.WEST, intersection.getSideHit());
        assertEquals(0.0D, intersection.getHitVec().x, 0.0D);
        assertEquals(0.5D, intersection.getHitVec().y, 0.0D);
        assertEquals(0.5D, intersection.getHitVec().z, 0.0D);

        assertNull(box.calculateIntercept(new Vector3D(-1.0D, 2.0D, 0.5D), new Vector3D(2.0D, 2.0D, 0.5D)));

        RayIntersection roundedBoundary = box.calculateIntercept(
                new Vector3D(-0.1D, 0.5D, 0.5D), new Vector3D(0.09D, 0.5D, 0.5D));
        assertNotNull(roundedBoundary);
        assertEquals(EnumFacing.WEST, roundedBoundary.getSideHit());
        assertEquals(0.0D, roundedBoundary.getHitVec().x, 0.0D);

        RayIntersection nearFace = box.calculateIntercept(
                new Vector3D(-0.0001D, 0.5D, 0.5D), new Vector3D(0.0001D, 0.5D, 0.5D));
        assertNotNull(nearFace);
        assertEquals(EnumFacing.WEST, nearFace.getSideHit());
        assertEquals(0.0D, nearFace.getHitVec().x, 0.0D);
    }

    @Test
    void zeroOffsetsAndEmptyArithmeticPreserveTheOriginalBlockPosition() {
        BlockPos position = new BlockPos(4, -3, 9);
        assertSame(position, position.add(0, 0, 0));
        assertSame(position, position.add(Vec3i.NULL_VECTOR));
        assertSame(position, position.offset(EnumFacing.UP, 0));
    }
}
