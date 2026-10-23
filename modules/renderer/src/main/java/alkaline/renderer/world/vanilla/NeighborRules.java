package alkaline.renderer.world.vanilla;

import alkaline.renderer.world.BlockSource;

/**
 * Vanilla 1.8.9's neighbor-dependent block properties ({@code getActualState}), computed from a section's
 * padded state volume ({@link BlockSource} layout) as the {@code extra} code {@link BlockStates189} expects. All
 * reads stay within two cells of the block, inside the halo.
 */
public final class NeighborRules {
    private static final int SX = 1;
    private static final int SY = BlockSource.PADDED;
    private static final int SZ = BlockSource.PADDED * BlockSource.PADDED;
    /** Horizontal directions as index offsets: north (-z), east, south, west. */
    private static final int NORTH = -SZ;
    private static final int SOUTH = SZ;
    private static final int WEST = -SX;
    private static final int EAST = SX;

    private NeighborRules() {
    }

    /**
     * The extra code for the state at {@code index}; {@code x, y, z} are its world coordinates (fire's pattern
     * depends on them).
     */
    public static int extra(char[] states, int index, int x, int y, int z) {
        int state = states[index];
        int id = state >> 4;
        int meta = state & 15;
        switch (id) {
            case 2, 3, 110 -> {
                int above = states[index + SY] >> 4;
                return above == 78 || above == 80 ? 1 : 0;
            }
            case 51 -> {
                return fire(states, index, x, y, z);
            }
            case 55 -> {
                return attach(states, index, EAST) * 27 + attach(states, index, NORTH) * 9
                        + attach(states, index, SOUTH) * 3 + attach(states, index, WEST);
            }
            case 93, 94 -> {
                int facing = meta & 3;
                return poweredDiodeFacing(states[index + offset(clockwise(facing))], clockwise(facing))
                        || poweredDiodeFacing(states[index + offset(counterClockwise(facing))], counterClockwise(facing))
                        ? 1 : 0;
            }
            case 104, 105 -> {
                int fruit = id == 104 ? 86 : 103;
                // EnumFacing.Plane.HORIZONTAL order: north, east, south, west; codes 1 north, 2 south, 3 west, 4 east.
                if (states[index + NORTH] >> 4 == fruit) {
                    return 1;
                }
                if (states[index + EAST] >> 4 == fruit) {
                    return 4;
                }
                if (states[index + SOUTH] >> 4 == fruit) {
                    return 2;
                }
                return states[index + WEST] >> 4 == fruit ? 3 : 0;
            }
            case 106 -> {
                return Blocks189.normalCube(states[index + SY] >> 4) ? 1 : 0;
            }
            case 131 -> {
                return solidTop(states[index - SY]) ? 0 : 1;
            }
            case 132 -> {
                // A hook connects when it faces back toward the wire (getHorizontal: south 0, west 1, north 2, east 3).
                return (tripwire(states, index, NORTH, 0) ? 1 : 0) | (tripwire(states, index, WEST, 3) ? 2 : 0)
                        | (tripwire(states, index, SOUTH, 2) ? 4 : 0) | (tripwire(states, index, EAST, 1) ? 8 : 0);
            }
            case 139 -> {
                return (wallConnects(states[index + NORTH]) ? 1 : 0) | (wallConnects(states[index + WEST]) ? 2 : 0)
                        | (wallConnects(states[index + SOUTH]) ? 4 : 0) | (wallConnects(states[index + EAST]) ? 8 : 0)
                        | (states[index + SY] >> 4 != 0 ? 16 : 0);
            }
            case 175 -> {
                if ((meta & 8) == 0) {
                    return 0;
                }
                int below = states[index - SY];
                if (below >> 4 != 175) {
                    return 0;
                }
                int variant = (below & 8) == 0 ? below & 7 : 0;
                return variant < 6 ? variant : 0;
            }
            default -> {
                if (Blocks189.fence(id)) {
                    return (fenceConnects(id, states[index + NORTH]) ? 1 : 0)
                            | (fenceConnects(id, states[index + WEST]) ? 2 : 0)
                            | (fenceConnects(id, states[index + SOUTH]) ? 4 : 0)
                            | (fenceConnects(id, states[index + EAST]) ? 8 : 0);
                }
                if (Blocks189.pane(id)) {
                    return (paneConnects(id, states[index + NORTH]) ? 1 : 0)
                            | (paneConnects(id, states[index + WEST]) ? 2 : 0)
                            | (paneConnects(id, states[index + SOUTH]) ? 4 : 0)
                            | (paneConnects(id, states[index + EAST]) ? 8 : 0);
                }
                if (Blocks189.fenceGate(id)) {
                    // Facing south/north (meta 0, 2) runs along x: walls to the west or east.
                    boolean alongX = (meta & 1) == 0;
                    int a = states[index + (alongX ? WEST : NORTH)] >> 4;
                    int b = states[index + (alongX ? EAST : SOUTH)] >> 4;
                    return a == 139 || b == 139 ? 1 : 0;
                }
                if (Blocks189.stairs(id)) {
                    return stairsShape(states, index, state);
                }
                if (Blocks189.door(id)) {
                    int other = states[index + ((meta & 8) > 0 ? -SY : SY)];
                    return other >> 4 == id ? other & 15 : 16;
                }
                return 0;
            }
        }
    }

    // ---- shared predicates -------------------------------------------------------------------------------

    /** World.doesBlockHaveSolidTopSurface. */
    public static boolean solidTop(int state) {
        int id = state >> 4;
        int meta = state & 15;
        if (Blocks189.material(id).opaque() && Blocks189.fullCube(id)) {
            return true;
        }
        if (Blocks189.stairs(id)) {
            return (meta & 4) != 0;
        }
        if (Blocks189.halfSlab(id)) {
            return (meta & 8) != 0;
        }
        return id == 154 || (id == 78 && (meta & 7) == 6);
    }

    private static boolean fenceConnects(int self, int state) {
        int id = state >> 4;
        if (id == 166) {
            return false;
        }
        if ((Blocks189.fence(id) && Blocks189.material(id) == Blocks189.material(self)) || Blocks189.fenceGate(id)) {
            return true;
        }
        return opaqueFull(id);
    }

    private static boolean wallConnects(int state) {
        int id = state >> 4;
        if (id == 166) {
            return false;
        }
        return id == 139 || Blocks189.fenceGate(id) || opaqueFull(id);
    }

    private static boolean opaqueFull(int id) {
        Blocks189.Material material = Blocks189.material(id);
        return material.opaque() && Blocks189.fullCube(id) && material != Blocks189.Material.GOURD;
    }

    private static boolean paneConnects(int self, int state) {
        int id = state >> 4;
        return Blocks189.fullBlock(id) || id == self || id == 20 || id == 95 || id == 160 || Blocks189.pane(id);
    }

    /** Horizontal index (getHorizontal order: south 0, west 1, north 2, east 3) to a volume offset. */
    private static int offset(int horizontal) {
        return switch (horizontal) {
            case 0 -> SOUTH;
            case 1 -> WEST;
            case 2 -> NORTH;
            default -> EAST;
        };
    }

    /** rotateY: north -> east -> south -> west -> north, in getHorizontal indices. */
    private static int clockwise(int horizontal) {
        return (horizontal + 1) & 3;
    }

    private static int counterClockwise(int horizontal) {
        return (horizontal + 3) & 3;
    }

    /** A powered repeater or comparator facing {@code side} (the direction from the locked repeater to it). */
    private static boolean poweredDiodeFacing(int state, int side) {
        int id = state >> 4;
        return (id == 94 || id == 150) && (state & 3) == side;
    }

    /** Tripwire: a hook facing back at the wire, or wire with the same suspended flag. */
    private static boolean tripwire(char[] states, int index, int offset, int towardWire) {
        int neighbor = states[index + offset];
        int id = neighbor >> 4;
        if (id == 131) {
            return (neighbor & 3) == towardWire;
        }
        return id == 132 && ((neighbor & 2) != 0) == ((states[index] & 2) != 0);
    }

    // ---- redstone wire ---------------------------------------------------------------------------------------

    /** getAttachPosition: 0 none, 1 side, 2 up. */
    private static int attach(char[] states, int index, int direction) {
        int neighborIndex = index + direction;
        int neighbor = states[neighborIndex];
        int neighborId = neighbor >> 4;
        if (!wireConnects(neighbor, direction)
                && (Blocks189.normalCube(neighborId) || states[neighborIndex - SY] >> 4 != 55)) {
            boolean coveredAbove = Blocks189.normalCube(states[index + SY] >> 4);
            return !coveredAbove && Blocks189.normalCube(neighborId) && states[neighborIndex + SY] >> 4 == 55 ? 2 : 0;
        }
        return 1;
    }

    /** canConnectTo(state, side) for a horizontal side. */
    private static boolean wireConnects(int state, int direction) {
        int id = state >> 4;
        if (id == 55) {
            return true;
        }
        if (id == 93 || id == 94) {
            // Repeaters connect along their axis: facing south/north (0, 2) lies along z.
            boolean alongZ = (state & 1) == 0;
            return alongZ == (direction == NORTH || direction == SOUTH);
        }
        return Blocks189.powerSource(id);
    }

    // ---- fire ------------------------------------------------------------------------------------------------

    private static int fire(char[] states, int index, int x, int y, int z) {
        int below = states[index - SY];
        if (solidTop(below) || Blocks189.flammable(below >> 4)) {
            return 0;
        }
        boolean alt = (x + y + z & 1) == 1;
        boolean flip = (x / 2 + y / 2 + z / 2 & 1) == 1;
        int upper = Blocks189.flammable(states[index + SY] >> 4) ? (alt ? 1 : 2) : 0;
        int sides = (Blocks189.flammable(states[index + NORTH] >> 4) ? 1 : 0)
                | (Blocks189.flammable(states[index + EAST] >> 4) ? 2 : 0)
                | (Blocks189.flammable(states[index + SOUTH] >> 4) ? 4 : 0)
                | (Blocks189.flammable(states[index + WEST] >> 4) ? 8 : 0);
        return 1 + ((alt ? 1 : 0) | (flip ? 2 : 0) | upper << 2 | sides << 4);
    }

    // ---- stairs ----------------------------------------------------------------------------------------------

    /** BlockStairs.getActualState: 0 straight, 1 inner_left, 2 inner_right, 3 outer_left, 4 outer_right. */
    private static int stairsShape(char[] states, int index, int state) {
        int facing = 5 - (state & 3);   // FACING index: 2 north, 3 south, 4 west, 5 east
        boolean top = (state & 4) != 0;
        // Outer corners: the stair in front of the facing turns across it (the same test vanilla's bounds
        // computation uses, so a non-straight result there always wins).
        int ahead = states[index + facingOffset(facing)];
        if (sameHalfStairs(ahead, top)) {
            int other = 5 - (ahead & 3);
            int result = cornerTurn(states, index, state, facing, other, true);
            if (result != 0) {
                return result == 1 ? 4 : 3;
            }
        }
        int behind = states[index - facingOffset(facing)];
        if (sameHalfStairs(behind, top)) {
            int other = 5 - (behind & 3);
            int result = cornerTurn(states, index, state, facing, other, false);
            if (result != 0) {
                return result == 1 ? 2 : 1;
            }
        }
        return 0;
    }

    /**
     * Vanilla's func_176307_f (ahead, outer) and func_176305_g (behind, inner): 1 or 2 when the neighbor stair
     * faces across, 0 otherwise.
     */
    private static int cornerTurn(char[] states, int index, int state, int facing, int other, boolean outer) {
        boolean top = (state & 4) != 0;
        int value;
        int check;
        switch (facing) {
            case 5 -> { // east
                if (other == 2) {
                    check = outer ? SOUTH : NORTH;
                    value = outer ? (top ? 1 : 2) : (top ? 1 : 2);
                } else if (other == 3) {
                    check = outer ? NORTH : SOUTH;
                    value = outer ? (top ? 2 : 1) : (top ? 2 : 1);
                } else {
                    return 0;
                }
            }
            case 4 -> { // west
                if (other == 2) {
                    check = outer ? SOUTH : NORTH;
                    value = top ? 2 : 1;
                } else if (other == 3) {
                    check = outer ? NORTH : SOUTH;
                    value = top ? 1 : 2;
                } else {
                    return 0;
                }
            }
            case 3 -> { // south
                if (other == 4) {
                    check = outer ? EAST : WEST;
                    value = top ? 2 : 1;
                } else if (other == 5) {
                    check = outer ? WEST : EAST;
                    value = top ? 1 : 2;
                } else {
                    return 0;
                }
            }
            default -> { // north
                if (other == 4) {
                    check = outer ? EAST : WEST;
                    value = top ? 1 : 2;
                } else if (other == 5) {
                    check = outer ? WEST : EAST;
                    value = top ? 2 : 1;
                } else {
                    return 0;
                }
            }
        }
        return sameStair(states[index + check], state) ? 0 : value;
    }

    private static int facingOffset(int facing) {
        return switch (facing) {
            case 2 -> NORTH;
            case 3 -> SOUTH;
            case 4 -> WEST;
            default -> EAST;
        };
    }

    private static boolean sameHalfStairs(int state, boolean top) {
        return Blocks189.stairs(state >> 4) && ((state & 4) != 0) == top;
    }

    /** isSameStair: stairs with the same half and facing. */
    private static boolean sameStair(int neighbor, int state) {
        return Blocks189.stairs(neighbor >> 4) && (neighbor & 7) == (state & 7);
    }
}
