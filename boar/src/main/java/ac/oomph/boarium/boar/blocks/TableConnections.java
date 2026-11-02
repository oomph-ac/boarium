package ac.oomph.boarium.boar.blocks;

import ac.boar.anticheat.collision.BedrockCollision;
import ac.boar.anticheat.player.BoarPlayer;
import ac.boar.anticheat.util.geyser.BlockEntityInfo;
import ac.boar.anticheat.util.math.Box;
import ac.boar.anticheat.util.math.Direction;
import org.cloudburstmc.math.vector.Vector3i;

import java.util.List;

/**
 * Block shapes that depend on the neighbouring blocks (fences, panes and bars, walls, stairs and chests), ported from GeyserBlockUtil in Boar's Geyser adapter.
 * A state here is a Java state ID, and neighbours are read from layer 0 of Boar's world.
 * Boar asks for these for each block near a moving player, so neighbours are read as state IDs, without making block states.
 */
final class TableConnections {
    private static final BlockTable TABLE = BlockTable.get();

    // Property values, by their index in the table
    private static final int TRUE = 0;
    private static final int FALSE = 1;
    private static final Direction[] FACINGS = {Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST};
    private static final int STRAIGHT = 0;
    private static final int INNER_LEFT = 1;
    private static final int INNER_RIGHT = 2;
    private static final int OUTER_LEFT = 3;
    private static final int OUTER_RIGHT = 4;
    private static final int SINGLE = 0;
    private static final int LEFT = 1;
    private static final int RIGHT = 2;

    // Property IDs
    private final int north = property("north", "true", "false");
    private final int east = property("east", "true", "false");
    private final int south = property("south", "true", "false");
    private final int west = property("west", "true", "false");
    private final int waterlogged = property("waterlogged", "true", "false");
    private final int up = property("up", "true", "false");
    private final int facing = property("facing", "north", "south", "west", "east");
    private final int half = property("half", "top", "bottom");
    private final int stairsShape = property("shape", "straight", "inner_left", "inner_right", "outer_left", "outer_right");
    private final int chestType = property("type", "single", "left", "right");

    // Block indices
    private final int chest = block("minecraft:chest");
    private final int trappedChest = block("minecraft:trapped_chest");
    private final int netherBrickFence = block("minecraft:nether_brick_fence");
    private final int scaffolding = block("minecraft:scaffolding");
    private final int barrier = block("minecraft:barrier");
    private final int carvedPumpkin = block("minecraft:carved_pumpkin");
    private final int jackOLantern = block("minecraft:jack_o_lantern");
    private final int melon = block("minecraft:melon");
    private final int pumpkin = block("minecraft:pumpkin");

    private static int property(String name, String... values) {
        int id = TABLE.property(name, values);
        if (id < 0) {
            throw new IllegalStateException("The block table has no property " + name + List.of(values));
        }
        return id;
    }

    private static int block(String name) {
        int block = TABLE.block(name);
        if (block < 0) {
            throw new IllegalStateException("The block table has no block " + name);
        }
        return block;
    }

    private static boolean in(int state, int category) {
        return (TABLE.categories(TABLE.blockOf(state)) & category) != 0;
    }

    // The state at a position, as Boar's getBlockState reads it
    private static int at(BoarPlayer player, int x, int y, int z) {
        return TableBlockState.orAir(player.compensatedWorld.getBlockAt(x, y, z, 0));
    }

    // As AbstractBoarBlockState.isFaceSturdy, which never counts scaffolding
    private boolean sturdy(int state) {
        return TABLE.isFaceSturdy(state) && TABLE.blockOf(state) != this.scaffolding;
    }

    private Direction facing(int state) {
        return FACINGS[TABLE.valueIndex(state, this.facing)];
    }

    // The state with its connections from its neighbours, as Geyser's adapter does in applyConnectionShape
    int reshape(BoarPlayer player, int state, int x, int y, int z) {
        int block = TABLE.blockOf(state);
        if (in(state, BlockTable.FENCE)) {
            return this.fence(player, state, x, y, z);
        } else if (in(state, BlockTable.BARS)) {
            return this.connected(state, this.attaches(player, x, y, z - 1), this.attaches(player, x + 1, y, z), this.attaches(player, x, y, z + 1), this.attaches(player, x - 1, y, z));
        } else if (block == this.chest || block == this.trappedChest) {
            return this.chestHalf(player, state, x, y, z);
        } else if (in(state, BlockTable.STAIRS)) {
            return TABLE.withIndex(state, this.stairsShape, this.stairShape(player, state, x, y, z));
        }
        return state;
    }

    // Shapes built straight from the neighbours, as Geyser's adapter does in connectionCollisionOverride: Bedrock's thin bars for panes and bars, and one box 1.5 blocks high for walls. Null for other blocks.
    List<Box> collisionOverride(BoarPlayer player, int state, int x, int y, int z) {
        if (in(state, BlockTable.BARS)) {
            return BedrockCollision.buildThinBarsShape(this.attaches(player, x, y, z - 1), this.attaches(player, x + 1, y, z), this.attaches(player, x, y, z + 1), this.attaches(player, x - 1, y, z));
        } else if (in(state, BlockTable.WALL)) {
            return this.wallShape(player, x, y, z);
        }
        return null;
    }

    private List<Box> wallShape(BoarPlayer player, int x, int y, int z) {
        boolean n = this.wallConnects(at(player, x, y, z - 1), Direction.SOUTH);
        boolean e = this.wallConnects(at(player, x + 1, y, z), Direction.WEST);
        boolean s = this.wallConnects(at(player, x, y, z + 1), Direction.NORTH);
        boolean w = this.wallConnects(at(player, x - 1, y, z), Direction.EAST);

        int above = at(player, x, y + 1, z);
        boolean aboveForcesPost = this.sturdy(above) || in(above, BlockTable.WALL) && TABLE.valueIndex(above, this.up) == TRUE;
        boolean straight = n && s && !e && !w || e && w && !n && !s;
        boolean post = aboveForcesPost || !straight;

        float minX = 1, minZ = 1, maxX = 0, maxZ = 0;
        if (post) {
            minX = Math.min(minX, 0.25F);
            maxX = Math.max(maxX, 0.75F);
            minZ = Math.min(minZ, 0.25F);
            maxZ = Math.max(maxZ, 0.75F);
        }
        if (n) {
            minX = Math.min(minX, 0.3125F);
            maxX = Math.max(maxX, 0.6875F);
            minZ = 0;
        }
        if (s) {
            minX = Math.min(minX, 0.3125F);
            maxX = Math.max(maxX, 0.6875F);
            maxZ = 1;
        }
        if (w) {
            minZ = Math.min(minZ, 0.3125F);
            maxZ = Math.max(maxZ, 0.6875F);
            minX = 0;
        }
        if (e) {
            minZ = Math.min(minZ, 0.3125F);
            maxZ = Math.max(maxZ, 0.6875F);
            maxX = 1;
        }
        return List.of(new Box(minX, 0, minZ, maxX, 1.5F, maxZ));
    }

    private int fence(BoarPlayer player, int fence, int x, int y, int z) {
        boolean n = this.fenceConnects(fence, at(player, x, y, z - 1), Direction.SOUTH);
        boolean e = this.fenceConnects(fence, at(player, x + 1, y, z), Direction.WEST);
        boolean s = this.fenceConnects(fence, at(player, x, y, z + 1), Direction.NORTH);
        boolean w = this.fenceConnects(fence, at(player, x - 1, y, z), Direction.EAST);
        return this.connected(fence, n, e, s, w);
    }

    // As in Geyser's adapter, the result is never waterlogged.
    private int connected(int state, boolean n, boolean e, boolean s, boolean w) {
        state = TABLE.withIndex(state, this.north, n ? TRUE : FALSE);
        state = TABLE.withIndex(state, this.east, e ? TRUE : FALSE);
        state = TABLE.withIndex(state, this.south, s ? TRUE : FALSE);
        state = TABLE.withIndex(state, this.west, w ? TRUE : FALSE);
        return TABLE.withIndex(state, this.waterlogged, FALSE);
    }

    // side is the neighbour's side that faces the fence.
    private boolean fenceConnects(int fence, int neighbour, Direction side) {
        boolean sameFence = in(neighbour, BlockTable.FENCE) && (TABLE.blockOf(neighbour) == this.netherBrickFence) == (TABLE.blockOf(fence) == this.netherBrickFence);
        return !this.isException(neighbour) && this.sturdy(neighbour) || sameFence || this.gateFaces(neighbour, side);
    }

    private boolean attaches(BoarPlayer player, int x, int y, int z) {
        int neighbour = at(player, x, y, z);
        return !this.isException(neighbour) && this.sturdy(neighbour) || in(neighbour, BlockTable.BARS) || in(neighbour, BlockTable.WALL);
    }

    private boolean wallConnects(int neighbour, Direction side) {
        return in(neighbour, BlockTable.WALL) || in(neighbour, BlockTable.BARS) || this.gateFaces(neighbour, side) || !this.isException(neighbour) && this.sturdy(neighbour);
    }

    // A fence gate joins on its two sides at right angles to the way it faces.
    private boolean gateFaces(int neighbour, Direction side) {
        return in(neighbour, BlockTable.FENCE_GATE) && this.facing(neighbour).getAxis() != side.getAxis();
    }

    // Blocks that nothing joins, even with a sturdy face
    private boolean isException(int state) {
        int block = TABLE.blockOf(state);
        return in(state, BlockTable.LEAVES) || in(state, BlockTable.SHULKER)
            || block == this.barrier
            || block == this.carvedPumpkin
            || block == this.jackOLantern
            || block == this.melon
            || block == this.pumpkin;
    }

    // A chest is half of a double chest when its block entity pairs it with the same block at most one block away; pairlead 1 makes it the right half.
    private int chestHalf(BoarPlayer player, int state, int x, int y, int z) {
        BlockEntityInfo entity = player.compensatedWorld.getBlockEntity(x, y, z);
        if (entity == null || entity.nbt() == null) {
            return TABLE.withIndex(state, this.chestType, SINGLE);
        }
        int pairX = entity.nbt().getInt("pairx", 0);
        int pairZ = entity.nbt().getInt("pairz", 0);
        if (Math.abs(x - pairX) > 1 || Math.abs(z - pairZ) > 1) {
            return TABLE.withIndex(state, this.chestType, SINGLE);
        }
        if (TABLE.blockOf(at(player, pairX, y, pairZ)) != TABLE.blockOf(state)) {
            return TABLE.withIndex(state, this.chestType, SINGLE);
        }
        return TABLE.withIndex(state, this.chestType, entity.nbt().getInt("pairlead", 0) == 1 ? RIGHT : LEFT);
    }

    // As Java's StairBlock: a corner from stairs ahead (outer) or behind (inner) of the same half that face across this one.
    private int stairShape(BoarPlayer player, int stairs, int x, int y, int z) {
        Direction facing = this.facing(stairs);
        int half = TABLE.valueIndex(stairs, this.half);

        Vector3i forward = facing.getUnitVector();
        int ahead = at(player, x + forward.getX(), y, z + forward.getZ());
        if (in(ahead, BlockTable.STAIRS) && TABLE.valueIndex(ahead, this.half) == half) {
            Direction aheadFacing = this.facing(ahead);
            if (aheadFacing.getAxis() != facing.getAxis() && this.differentOrientation(player, facing, half, x, y, z, opposite(aheadFacing))) {
                return aheadFacing == counterClockwise(facing) ? OUTER_LEFT : OUTER_RIGHT;
            }
        }

        int behind = at(player, x - forward.getX(), y, z - forward.getZ());
        if (in(behind, BlockTable.STAIRS) && TABLE.valueIndex(behind, this.half) == half) {
            Direction behindFacing = this.facing(behind);
            if (behindFacing.getAxis() != facing.getAxis() && this.differentOrientation(player, facing, half, x, y, z, behindFacing)) {
                return behindFacing == counterClockwise(facing) ? INNER_LEFT : INNER_RIGHT;
            }
        }
        return STRAIGHT;
    }

    private boolean differentOrientation(BoarPlayer player, Direction facing, int half, int x, int y, int z, Direction side) {
        Vector3i step = side.getUnitVector();
        int neighbour = at(player, x + step.getX(), y, z + step.getZ());
        return !in(neighbour, BlockTable.STAIRS) || this.facing(neighbour) != facing || TABLE.valueIndex(neighbour, this.half) != half;
    }

    private static Direction opposite(Direction direction) {
        return switch (direction) {
            case NORTH -> Direction.SOUTH;
            case SOUTH -> Direction.NORTH;
            case WEST -> Direction.EAST;
            case EAST -> Direction.WEST;
            case UP -> Direction.DOWN;
            case DOWN -> Direction.UP;
        };
    }

    private static Direction counterClockwise(Direction direction) {
        return switch (direction) {
            case NORTH -> Direction.WEST;
            case SOUTH -> Direction.EAST;
            case WEST -> Direction.SOUTH;
            case EAST -> Direction.NORTH;
            default -> throw new IllegalArgumentException("No horizontal turn of " + direction);
        };
    }
}
