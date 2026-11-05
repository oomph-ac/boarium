package ac.oomph.boarium.boar.blocks;

import ac.boar.anticheat.player.BoarPlayer;
import ac.boar.anticheat.util.math.Box;
import org.cloudburstmc.nbt.NbtMap;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntFunction;

/**
 * The collision boxes that Bedrock builds from the neighbouring blocks for states that do not hold their connections, as the 1.26.30 client does: fences, panes and bars, chorus plants and stair corners.
 * A state here is a shared ID of {@link BedrockBlockUnion}, and neighbours are read from layer 0 of Boar's world.
 */
final class BedrockConnections {
    static final List<String> RULES_RESOURCES = List.of("/ac/oomph/boarium/boar/blocks/connection_rules.26_30.txt", "/ac/oomph/boarium/boar/blocks/connection_rules.after_26_30.txt");
    private static final BedrockBlockTable TABLE = BedrockBlockUnion.get().table();
    // An ID outside the shared table, such as a custom block's
    private static final int UNKNOWN = -1;

    // Sides, as indices into DX and DZ; the side opposite s is (s + 2) & 3.
    static final int NORTH = 0;
    static final int EAST = 1;
    static final int SOUTH = 2;
    static final int WEST = 3;
    private static final int[] DX = {0, 1, 0, -1};
    private static final int[] DZ = {-1, 0, 1, 0};

    // The kinds a block joins as, numbered as the client's Block::getConnectionID numbers them
    static final int FENCE = 0;
    static final int NETHER_FENCE = 1;
    static final int WALL = 2;
    static final int THIN = 3;

    // A connection rule is the kinds a block lets join it (bit 1 << kind) and the sides they may join on (bit 0x10 << side).
    // A block with no rule lets every kind join on every side, as the client's BlockTrait::Connection::getConnectedDirections does.
    static final int ALL = 0xFF;
    private static final int ALL_KINDS = 0x0F;
    private static final byte[] RULES = new byte[TABLE.paletteSize()];

    // What each palette entry builds from its neighbours
    private static final byte OTHER = 0;
    private static final byte WOOD_FENCE = 1;
    private static final byte NETHER_BRICK_FENCE = 2;
    private static final byte PANE = 3;
    private static final byte CHORUS = 4;
    private static final byte STAIRS = 5;
    private static final byte[] SHAPES = new byte[TABLE.paletteSize()];

    // The full side of each stair entry, plus 4 when it is upside down; -1 for other entries
    private static final byte[] STAIR_SIDES = new byte[TABLE.paletteSize()];
    private static final boolean[] CHORUS_JOINS = new boolean[TABLE.paletteSize()];
    private static final int END_STONE = TABLE.firstIndex("minecraft:end_stone");

    // Boxes by the joined sides (bit 1 << side)
    private static final List<Box>[] FENCE_BOXES = boxes(BedrockConnections::fenceBoxes);
    private static final List<Box>[] PANE_BOXES = boxes(BedrockConnections::paneBoxes);

    // Stair boxes by corner, at the stair's STAIR_SIDES value times 5 plus the corner. Left and right are as in Java's StairsShape: a left corner turns counter-clockwise from the facing.
    private static final int OUTER_LEFT = 1;
    private static final int OUTER_RIGHT = 2;
    private static final int INNER_LEFT = 3;
    private static final int INNER_RIGHT = 4;
    private static final List<Box>[] STAIR_BOXES = stairCorners();

    // Chorus plant boxes by the sides with a chorus plant or flower: bit 1 west, 2 down (or end stone), 4 north, 8 east, 16 up, 32 south
    private static final List<Box>[] CHORUS_BOXES = chorusBoxes();

    static {
        Map<String, Integer> named = readRules();
        Set<String> singleSlabs = BedrockProperties.doubleSlabs(TABLE).keySet();
        for (int i = 0; i < RULES.length; i++) {
            String name = TABLE.name(i);
            int rule = stateRule(name, TABLE.states(i), singleSlabs);
            RULES[i] = (byte) (rule >= 0 ? rule : named.getOrDefault(name, ALL));
            byte shape = shape(name);
            // From 26.50 a state can hold its connections or its stair corner, and then its table boxes are already the shape the client builds.
            SHAPES[i] = holdsShape(TABLE.states(i)) ? OTHER : shape;
            STAIR_SIDES[i] = (byte) (shape == STAIRS ? stairSide(TABLE.states(i).getInt("weirdo_direction")) + (TABLE.states(i).getByte("upside_down_bit") == 1 ? 4 : 0) : -1);
            CHORUS_JOINS[i] = name.equals("minecraft:chorus_plant") || name.equals("minecraft:chorus_flower");
        }
    }

    private BedrockConnections() {
    }

    private static byte shape(String name) {
        if (name.equals("minecraft:nether_brick_fence")) {
            return NETHER_BRICK_FENCE;
        } else if (name.endsWith("_fence")) {
            return WOOD_FENCE;
        } else if (name.endsWith("_pane") || name.endsWith("_bars")) {
            return PANE;
        } else if (name.equals("minecraft:chorus_plant")) {
            return CHORUS;
        } else if (name.endsWith("_stairs")) {
            return STAIRS;
        }
        return OTHER;
    }

    // Whether a state holds its connections (minecraft:connection_*) or its stair corner (minecraft:corner)
    private static boolean holdsShape(NbtMap states) {
        return states.containsKey("minecraft:corner") || states.keySet().stream().anyMatch(key -> key.startsWith("minecraft:connection_"));
    }

    private static Map<String, Integer> readRules() {
        Map<String, Integer> rules = new HashMap<>();
        for (String resource : RULES_RESOURCES) {
            try (InputStream in = BedrockConnections.class.getResourceAsStream(resource)) {
                if (in == null) {
                    throw new IllegalStateException("Missing " + resource);
                }
                BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
                for (String line = reader.readLine(); line != null; line = reader.readLine()) {
                    if (line.isBlank() || line.startsWith("#")) {
                        continue;
                    }
                    String[] words = line.trim().split(" ");
                    int kinds = 0;
                    for (int w = 1; w < words.length; w++) {
                        kinds |= switch (words[w]) {
                            case "none" -> 0;
                            case "fence" -> 1 << FENCE;
                            case "nether_fence" -> 1 << NETHER_FENCE;
                            case "wall" -> 1 << WALL;
                            case "thin" -> 1 << THIN;
                            default -> throw new IllegalStateException("Unknown kind " + words[w] + " in " + resource);
                        };
                    }
                    if (TABLE.firstIndex(words[0]) < 0) {
                        throw new IllegalStateException("The Bedrock palette has no " + words[0] + " from " + resource);
                    }
                    rules.put(words[0], kinds == 0 ? 0 : kinds | 0xF0);
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return rules;
    }

    // The rules that the client's _addHardCodedBlockComponents gives each state of these blocks; -1 for other blocks.
    private static int stateRule(String name, NbtMap states, Set<String> singleSlabs) {
        if (name.endsWith("fence_gate")) {
            // FenceGateBlock: fences, nether brick fences and walls, on the two sides across the way it faces
            String facing = states.getString("minecraft:cardinal_direction");
            int sides = facing.equals("north") || facing.equals("south") ? side(EAST) | side(WEST) : side(NORTH) | side(SOUTH);
            return 1 << FENCE | 1 << NETHER_FENCE | 1 << WALL | sides;
        } else if (name.endsWith("trapdoor")) {
            // TrapDoorBlock: when open, on the side it lies against (TrapDoorUtils::trapDoorToFacingDirection); when closed, none
            int[] lyingSide = {WEST, EAST, NORTH, SOUTH};
            return states.getByte("open_bit") == 1 ? ALL_KINDS | side(lyingSide[states.getInt("direction")]) : 0;
        } else if (name.endsWith("_stairs")) {
            // StairBlock: on the full back side
            return ALL_KINDS | side(stairSide(states.getInt("weirdo_direction")));
        } else if (singleSlabs.contains(name)) {
            // SlabBlock: none for a single slab; double slabs have no rule
            return 0;
        } else if (name.equals("minecraft:snow_layer")) {
            // TopSnowBlock: only at full height, the last of height's 8 values
            return states.getInt("height") == 7 ? ALL : 0;
        } else if (name.endsWith("_shelf")) {
            // ShelfBlock: on its back
            int back = switch (states.getString("minecraft:cardinal_direction")) {
                case "south" -> NORTH;
                case "west" -> EAST;
                case "north" -> SOUTH;
                default -> WEST;
            };
            return ALL_KINDS | side(back);
        } else if (name.equals("minecraft:piston_arm_collision") || name.equals("minecraft:sticky_piston_arm_collision")) {
            // PistonArmBlock: on the side of the head's plate, opposite its facing_direction; an arm facing up or down has no side
            return switch (states.getInt("facing_direction")) {
                case 2 -> ALL_KINDS | side(SOUTH);
                case 3 -> ALL_KINDS | side(NORTH);
                case 4 -> ALL_KINDS | side(EAST);
                case 5 -> ALL_KINDS | side(WEST);
                default -> ALL_KINDS;
            };
        }
        return -1;
    }

    private static int side(int side) {
        return 0x10 << side;
    }

    // The full back side of a stair from weirdo_direction (east, west, south, north)
    private static int stairSide(int weirdoDirection) {
        return switch (weirdoDirection) {
            case 0 -> EAST;
            case 1 -> WEST;
            case 2 -> SOUTH;
            default -> NORTH;
        };
    }

    // The shared ID at a position, or UNKNOWN
    private static int at(BoarPlayer player, int x, int y, int z) {
        int index = player.compensatedWorld.getBlockAt(x, y, z, 0);
        return index >= 0 && index < RULES.length ? index : UNKNOWN;
    }

    // Whether a block of this kind joins the neighbour on that side. A block outside the shared table has no rule, so every kind joins it, as the client does for a block without a connection rule component.
    private static boolean joins(BoarPlayer player, int kind, int x, int y, int z, int side) {
        int index = at(player, x + DX[side], y, z + DZ[side]);
        int rule = index == UNKNOWN ? ALL : RULES[index];
        return (rule & 1 << kind) != 0 && (rule & side((side + 2) & 3)) != 0;
    }

    static boolean fromNeighbours(int index) {
        return SHAPES[index] != OTHER;
    }

    static int rule(int index) {
        return RULES[index] & 0xFF;
    }

    // The boxes this block has among its neighbours, or null when the palette's boxes are already right
    static List<Box> collisionOverride(BoarPlayer player, int index, int x, int y, int z) {
        return switch (SHAPES[index]) {
            case WOOD_FENCE -> FENCE_BOXES[joined(player, FENCE, x, y, z)];
            case NETHER_BRICK_FENCE -> FENCE_BOXES[joined(player, NETHER_FENCE, x, y, z)];
            case PANE -> PANE_BOXES[joined(player, THIN, x, y, z)];
            case CHORUS -> CHORUS_BOXES[chorusSides(player, x, y, z)];
            case STAIRS -> stairBoxes(player, index, x, y, z);
            default -> null;
        };
    }

    // The joined sides, bit 1 << side
    private static int joined(BoarPlayer player, int kind, int x, int y, int z) {
        int sides = 0;
        for (int side = 0; side < 4; side++) {
            if (joins(player, kind, x, y, z, side)) {
                sides |= 1 << side;
            }
        }
        return sides;
    }

    @SuppressWarnings("unchecked")
    private static List<Box>[] boxes(IntFunction<List<Box>> build) {
        List<Box>[] boxes = new List[16];
        for (int sides = 0; sides < 16; sides++) {
            boxes[sides] = build.apply(sides);
        }
        return boxes;
    }

    // FenceBlock::addCollisionShapes: a north-south bar if north or south joins, then an east-west bar if east or west joins, each 1.5 high and running to the joined sides; a post when nothing joins
    private static List<Box> fenceBoxes(int sides) {
        boolean n = (sides & 1 << NORTH) != 0, e = (sides & 1 << EAST) != 0, s = (sides & 1 << SOUTH) != 0, w = (sides & 1 << WEST) != 0;
        List<Box> boxes = new ArrayList<>(2);
        if (n || s || !e && !w) {
            boxes.add(new Box(0.375F, 0, n ? 0 : 0.375F, 0.625F, 1.5F, s ? 1 : 0.625F));
        }
        if (e || w) {
            boxes.add(new Box(w ? 0 : 0.375F, 0, 0.375F, e ? 1 : 0.625F, 1.5F, 0.625F));
        }
        return List.copyOf(boxes);
    }

    // ThinFenceBlock::addAABBs: an east-west bar, then a north-south bar, each from the middle to a joined side or across when both join; a post when nothing joins
    private static List<Box> paneBoxes(int sides) {
        boolean n = (sides & 1 << NORTH) != 0, e = (sides & 1 << EAST) != 0, s = (sides & 1 << SOUTH) != 0, w = (sides & 1 << WEST) != 0;
        if (sides == 0) {
            return List.of(new Box(0.4375F, 0, 0.4375F, 0.5625F, 1, 0.5625F));
        }
        List<Box> boxes = new ArrayList<>(2);
        if (e || w) {
            boxes.add(new Box(w ? 0 : 0.5F, 0, 0.4375F, e ? 1 : 0.5F, 1, 0.5625F));
        }
        if (n || s) {
            boxes.add(new Box(0.4375F, 0, n ? 0 : 0.5F, 0.5625F, 1, s ? 1 : 0.5F));
        }
        return List.copyOf(boxes);
    }

    private static boolean chorusAt(BoarPlayer player, int x, int y, int z) {
        int index = at(player, x, y, z);
        return index != UNKNOWN && CHORUS_JOINS[index];
    }

    private static int chorusSides(BoarPlayer player, int x, int y, int z) {
        int below = at(player, x, y - 1, z);
        boolean down = below != UNKNOWN && (CHORUS_JOINS[below] || below == END_STONE);
        return (chorusAt(player, x - 1, y, z) ? 1 : 0) | (down ? 2 : 0) | (chorusAt(player, x, y, z - 1) ? 4 : 0)
                | (chorusAt(player, x + 1, y, z) ? 8 : 0) | (chorusAt(player, x, y + 1, z) ? 16 : 0) | (chorusAt(player, x, y, z + 1) ? 32 : 0);
    }

    // ChorusPlantBlock::_getShape: one box, 2/16 in from each side, out to every side with a chorus plant or flower next to it, and down to end stone below
    @SuppressWarnings("unchecked")
    private static List<Box>[] chorusBoxes() {
        List<Box>[] boxes = new List[64];
        for (int sides = 0; sides < 64; sides++) {
            boxes[sides] = List.of(new Box((sides & 1) != 0 ? 0 : 0.125F, (sides & 2) != 0 ? 0 : 0.125F, (sides & 4) != 0 ? 0 : 0.125F,
                    (sides & 8) != 0 ? 1 : 0.875F, (sides & 16) != 0 ? 1 : 0.875F, (sides & 32) != 0 ? 1 : 0.875F));
        }
        return boxes;
    }

    // StairBlock::addAABBs: the slab, the step, and maybe an inner quarter.
    // Stairs ahead of the same half that face across cut the step to the quarter on their side (an outer corner); failing that, stairs behind add the quarter behind on their side (an inner corner).
    // The client skips a corner when one block beside is this very stair state: for an outer corner the block on the far side from that stair's full side, for an inner corner the block on the near side.
    // Null for a straight stair, whose palette boxes are the slab and the step.
    private static List<Box> stairBoxes(BoarPlayer player, int index, int x, int y, int z) {
        int stair = STAIR_SIDES[index];
        int facing = stair & 3;
        int left = (facing + 3) & 3;

        int across = acrossSide(stairAt(player, x + DX[facing], y, z + DZ[facing]), stair);
        if (across >= 0 && at(player, x - DX[across], y, z - DZ[across]) != index) {
            return STAIR_BOXES[stair * 5 + (across == left ? OUTER_LEFT : OUTER_RIGHT)];
        }
        across = acrossSide(stairAt(player, x - DX[facing], y, z - DZ[facing]), stair);
        if (across >= 0 && at(player, x + DX[across], y, z + DZ[across]) != index) {
            return STAIR_BOXES[stair * 5 + (across == left ? INNER_LEFT : INNER_RIGHT)];
        }
        return null;
    }

    private static int stairAt(BoarPlayer player, int x, int y, int z) {
        int index = at(player, x, y, z);
        return index == UNKNOWN ? -1 : STAIR_SIDES[index];
    }

    // The full side of a neighbouring stair (its STAIR_SIDES value) of the same half that faces across this one, or -1
    private static int acrossSide(int neighbour, int stair) {
        return neighbour >= 0 && neighbour >= 4 == stair >= 4 && (neighbour & 1) != (stair & 1) ? neighbour & 3 : -1;
    }

    @SuppressWarnings("unchecked")
    private static List<Box>[] stairCorners() {
        List<Box>[] boxes = new List[8 * 5];
        for (int stair = 0; stair < 8; stair++) {
            int facing = stair & 3;
            boolean top = stair >= 4;
            int left = (facing + 3) & 3, right = (facing + 1) & 3, back = (facing + 2) & 3;
            boxes[stair * 5 + OUTER_LEFT] = List.of(slab(top), quarter(top, facing, left));
            boxes[stair * 5 + OUTER_RIGHT] = List.of(slab(top), quarter(top, facing, right));
            boxes[stair * 5 + INNER_LEFT] = List.of(slab(top), quarter(top, facing, -1), quarter(top, back, left));
            boxes[stair * 5 + INNER_RIGHT] = List.of(slab(top), quarter(top, facing, -1), quarter(top, back, right));
        }
        return boxes;
    }

    private static Box slab(boolean top) {
        return new Box(0, top ? 0.5F : 0, 0, 1, top ? 1 : 0.5F, 1);
    }

    // The half of the step on side a, cut to the quarter on side b too unless b is -1
    private static Box quarter(boolean top, int a, int b) {
        float minX = 0, maxX = 1, minZ = 0, maxZ = 1;
        for (int side : new int[]{a, b}) {
            switch (side) {
                case NORTH -> maxZ = 0.5F;
                case SOUTH -> minZ = 0.5F;
                case WEST -> maxX = 0.5F;
                case EAST -> minX = 0.5F;
                default -> {
                }
            }
        }
        return new Box(minX, top ? 0 : 0.5F, minZ, maxX, top ? 0.5F : 1, maxZ);
    }
}
