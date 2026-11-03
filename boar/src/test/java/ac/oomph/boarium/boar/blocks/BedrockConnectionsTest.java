package ac.oomph.boarium.boar.blocks;

import ac.boar.anticheat.data.block.AbstractBoarBlockState;
import ac.boar.anticheat.player.BoarPlayer;
import ac.boar.anticheat.player.data.BlockMappingInfo;
import ac.boar.anticheat.util.Dimension;
import ac.boar.anticheat.util.geyser.BoarChunkSection;
import ac.boar.anticheat.util.math.Box;
import ac.boar.api.anticheat.model.NetworkSession;
import ac.oomph.boarium.boar.TestBoar;
import it.unimi.dsi.fastutil.ints.IntList;
import org.cloudburstmc.math.vector.Vector3i;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.protocol.bedrock.data.definitions.SimpleBlockDefinition;
import org.cloudburstmc.protocol.bedrock.packet.StartGamePacket;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Expected boxes and rules come from the 1.26.30 client (Minecraft Education 1.26.30.1 in Ghidra): FenceBlock::addCollisionShapes, ThinFenceBlock::addAABBs, ChorusPlantBlock::_getShape,
// StairBlock::setStepShape and setInnerPieceShape, WallBlock::getVisualShapeInWorld, the connection rules in VanillaBlockTypes::registerBlocks, and the _addHardCodedBlockComponents of
// fence gates, trapdoors, stairs, slabs, top snow, shelves and PistonArmBlock. Wall boxes are also Oomph's wall.go, and the BDS data in BedrockBlockTable.
// Boar's Java model (TableConnections through Geyser's Java to Bedrock mapping) is compared where the two should agree, and every difference is listed.
class BedrockConnectionsTest {
    private static final Vector3i POS = Vector3i.from(8, 64, 8);
    private static final String[] SIDES = {"north", "east", "south", "west"};
    private static final float[] POST = {0.375F, 0, 0.375F, 0.625F, 1.5F, 0.625F};
    private static final float[] PANE_POST = {0.4375F, 0, 0.4375F, 0.5625F, 1, 0.5625F};

    private static BedrockBlockTable table;
    private static BlockTable java;
    private static BlockMappingInfo javaMapping;
    private BoarPlayer player;
    private BoarPlayer javaPlayer;

    @BeforeAll
    static void load() throws Exception {
        TestBoar.start();
        table = BedrockBlockTable.get();
        java = BlockTable.get();
        javaMapping = TablePalette.mappingInfo(new StartGamePacket());
    }

    // A player whose runtime IDs are palette indices, and a player of Boar's Java model; each with one empty overworld chunk at chunk 0, 0
    @BeforeEach
    void world() {
        int airIndex = table.firstIndex("minecraft:air");
        SimpleBlockDefinition air = new SimpleBlockDefinition("minecraft:air", airIndex, table.states(airIndex));
        this.player = player(new BlockMappingInfo(air, air, air, air, IntList.of(airIndex), definition -> false, id -> id, id -> id));
        this.javaPlayer = player(javaMapping);
    }

    private static BoarPlayer player(BlockMappingInfo mapping) {
        BoarPlayer player = new BoarPlayer(new Offline(), null, null, mapping, null, null, null, Map.of(), true);
        player.compensatedWorld.setDimension(Dimension.OVERWORLD);
        player.compensatedWorld.put(0, 0, new BoarChunkSection[24], new boolean[24]);
        return player;
    }

    // The first palette entry with this name and these state values
    private static int find(String name, Object... states) {
        int first = table.firstIndex("minecraft:" + name);
        assertTrue(first >= 0, name);
        for (int i = first; i < first + table.stateCount("minecraft:" + name); i++) {
            boolean match = true;
            for (int s = 0; s < states.length; s += 2) {
                match &= states[s + 1].equals(table.states(i).get((String) states[s]));
            }
            if (match) {
                return i;
            }
        }
        throw new AssertionError(name + " has no state " + List.of(states));
    }

    private static int stairs(String name, int weirdoDirection, int upsideDown) {
        return find(name, "weirdo_direction", weirdoDirection, "upside_down_bit", (byte) upsideDown);
    }

    private void set(Vector3i pos, int index) {
        this.player.compensatedWorld.updateBlock(pos, 0, index);
    }

    private List<Box> override(int index) {
        return new BedrockState(index, POS, 0).connectionCollisionOverride(this.player, POS);
    }

    @Test
    void fenceJoinsFullBlocksButNotAirOrLeaves() {
        int fence = find("oak_fence");
        assertBoxes(this.override(fence), POST);
        this.set(POS.east(), find("stone"));
        assertBoxes(this.override(fence), 0.375F, 0, 0.375F, 1, 1.5F, 0.625F);
        // Leaves have a rule that lets nothing join.
        this.set(POS.north(), find("oak_leaves"));
        this.set(POS.west(), find("air"));
        assertBoxes(this.override(fence), 0.375F, 0, 0.375F, 1, 1.5F, 0.625F);
    }

    // A fence gate lets fences join only on the two sides across the way it faces.
    @Test
    void fenceJoinsAGateTurnedAcross() {
        int fence = find("oak_fence");
        this.set(POS.north(), find("fence_gate", "minecraft:cardinal_direction", "north"));
        assertBoxes(this.override(fence), POST);
        this.set(POS.north(), find("fence_gate", "minecraft:cardinal_direction", "east"));
        assertBoxes(this.override(fence), 0.375F, 0, 0, 0.625F, 1.5F, 0.625F);
        // Open or not, and for nether brick fences too
        this.set(POS.north(), find("spruce_fence_gate", "minecraft:cardinal_direction", "west", "open_bit", (byte) 1));
        assertBoxes(this.override(find("nether_brick_fence")), 0.375F, 0, 0, 0.625F, 1.5F, 0.625F);
    }

    // Wooden fences join wooden fences, and nether brick fences join nether brick fences. The north-south bar comes first.
    @Test
    void fenceJoinsItsOwnKindOfFence() {
        this.set(POS.north(), find("oak_fence"));
        this.set(POS.south(), find("birch_fence"));
        this.set(POS.east(), find("nether_brick_fence"));
        this.set(POS.west(), find("nether_brick_fence"));
        assertBoxes(this.override(find("oak_fence")), 0.375F, 0, 0, 0.625F, 1.5F, 1);
        assertBoxes(this.override(find("nether_brick_fence")), 0, 0, 0.375F, 1, 1.5F, 0.625F);
        this.set(POS.west(), find("stone"));
        assertBoxes(this.override(find("oak_fence")), 0.375F, 0, 0, 0.625F, 1.5F, 1, 0, 0, 0.375F, 0.625F, 1.5F, 0.625F);
    }

    @Test
    void paneBetweenPanesIsOneBar() {
        int pane = find("glass_pane");
        assertBoxes(this.override(pane), PANE_POST);
        this.set(POS.north(), find("glass_pane"));
        this.set(POS.south(), find("hard_red_stained_glass_pane"));
        assertBoxes(this.override(pane), 0.4375F, 0, 0, 0.5625F, 1, 1);
    }

    // Walls let panes join; fences and fence gates do not.
    @Test
    void paneJoinsAWallButNotAFenceOrGate() {
        int pane = find("glass_pane");
        this.set(POS.north(), find("cobblestone_wall"));
        this.set(POS.east(), find("oak_fence"));
        this.set(POS.west(), find("fence_gate", "minecraft:cardinal_direction", "north"));
        assertBoxes(this.override(pane), 0.4375F, 0, 0, 0.5625F, 1, 0.5F);
    }

    // A bar reaches from the middle to one joined side, or across when both join; the east-west bar comes first.
    @Test
    void barsJoinBarsAndFullBlocks() {
        int bars = find("iron_bars");
        this.set(POS.east(), find("stone"));
        assertBoxes(this.override(bars), 0.5F, 0, 0.4375F, 1, 1, 0.5625F);
        this.set(POS.west(), find("copper_bars"));
        this.set(POS.south(), find("iron_bars"));
        assertBoxes(this.override(bars), 0, 0, 0.4375F, 1, 1, 0.5625F, 0.4375F, 0, 0.5F, 0.5625F, 1, 1);
        assertBoxes(this.override(find("waxed_oxidized_copper_bars")), 0, 0, 0.4375F, 1, 1, 0.5625F, 0.4375F, 0, 0.5F, 0.5625F, 1, 1);
    }

    // One box, out to each side with a chorus plant or flower, and down to end stone; end stone elsewhere does not count.
    @Test
    void chorusPlantIsOneBox() {
        int plant = find("chorus_plant");
        assertBoxes(this.override(plant), 0.125F, 0.125F, 0.125F, 0.875F, 0.875F, 0.875F);
        this.set(POS.down(), find("end_stone"));
        this.set(POS.up(), find("chorus_plant"));
        this.set(POS.east(), find("chorus_flower"));
        this.set(POS.north(), find("end_stone"));
        assertBoxes(this.override(plant), 0.125F, 0, 0.125F, 1, 1, 0.875F);
    }

    // Weirdo directions 0, 1, 2, 3 face east, west, south and north; a bottom stair's step is its upper half on that side.
    @Test
    void stairsAheadMakeOuterCorners() {
        int east = stairs("oak_stairs", 0, 0);
        assertNull(this.override(east));
        // A stair facing north ahead: the step is cut to its north-east quarter (Java's outer left).
        this.set(POS.east(), stairs("oak_stairs", 3, 0));
        assertBoxes(this.override(east), 0, 0, 0, 1, 0.5F, 1, 0.5F, 0.5F, 0, 1, 1, 0.5F);
        // Facing south: the south-east quarter (outer right)
        this.set(POS.east(), stairs("oak_stairs", 2, 0));
        assertBoxes(this.override(east), 0, 0, 0, 1, 0.5F, 1, 0.5F, 0.5F, 0.5F, 1, 1, 1);
        // Upside down stairs cut their lower half.
        int topEast = stairs("oak_stairs", 0, 1);
        this.set(POS.east(), stairs("stone_stairs", 2, 1));
        assertBoxes(this.override(topEast), 0, 0.5F, 0, 1, 1, 1, 0.5F, 0, 0.5F, 1, 0.5F, 1);
    }

    @Test
    void stairsBehindMakeInnerCorners() {
        int east = stairs("oak_stairs", 0, 0);
        // A stair facing north behind adds the north-west quarter (Java's inner left).
        this.set(POS.west(), stairs("oak_stairs", 3, 0));
        assertBoxes(this.override(east), 0, 0, 0, 1, 0.5F, 1, 0.5F, 0.5F, 0, 1, 1, 1, 0, 0.5F, 0, 0.5F, 1, 0.5F);
        // Facing south: the south-west quarter (inner right)
        this.set(POS.west(), stairs("oak_stairs", 2, 0));
        assertBoxes(this.override(east), 0, 0, 0, 1, 0.5F, 1, 0.5F, 0.5F, 0, 1, 1, 1, 0, 0.5F, 0.5F, 0.5F, 1, 1);
    }

    @Test
    void stairsOfTheOtherHalfStayStraight() {
        this.set(POS.east(), stairs("oak_stairs", 3, 1));
        this.set(POS.west(), stairs("oak_stairs", 2, 1));
        assertNull(this.override(stairs("oak_stairs", 0, 0)));
    }

    // The client keeps the stair straight only when the block on the far side of the corner is the very same stair state; Java looks at any stairs facing the same way on the same half.
    @Test
    void onlyTheSameStairBesideKeepsItStraight() {
        int east = stairs("oak_stairs", 0, 0);
        this.set(POS.east(), stairs("oak_stairs", 3, 0));
        this.set(POS.south(), east);
        assertNull(this.override(east));
        this.set(POS.south(), stairs("stone_stairs", 0, 0));
        assertBoxes(this.override(east), 0, 0, 0, 1, 0.5F, 1, 0.5F, 0.5F, 0, 1, 1, 0.5F);
    }

    // Every arrangement of oak stairs around an oak stair covers the same space as Boar's Java model, whose corners follow Java's StairBlock.
    @Test
    void stairCornersCoverWhatJavasDo() {
        int first = table.firstIndex("minecraft:oak_stairs");
        int[] around = new int[9];
        for (int i = 0; i < 8; i++) {
            around[i + 1] = first + i;
        }
        around[0] = table.firstIndex("minecraft:air");
        Vector3i[] beside = {POS.north(), POS.east(), POS.south(), POS.west()};
        int corners = 0;
        for (int stair = first; stair < first + 8; stair++) {
            for (int arrangement = 0; arrangement < 9 * 9 * 9 * 9; arrangement++) {
                for (int side = 0, rest = arrangement; side < 4; side++, rest /= 9) {
                    this.set(beside[side], around[rest % 9]);
                    this.javaPlayer.compensatedWorld.updateBlock(beside[side], 0, javaMapping.fromIntermediary().applyAsInt(java.paletteToJava(around[rest % 9])));
                }
                List<Box> boxes = this.override(stair);
                corners += boxes == null ? 0 : 1;
                float[] bedrock = boxes == null ? table.collision(stair) : flatten(boxes);
                float[] javaBoxes = java.collision(this.reshaped(java.paletteToJava(stair)));
                assertEquals(cells(javaBoxes), cells(bedrock), table.states(stair) + " among " + arrangement);
            }
        }
        assertTrue(corners > 1000, "corners " + corners);
    }

    // The 8 cells of half a block that the boxes fill, one bit each
    private static int cells(float[] boxes) {
        int cells = 0;
        for (int cell = 0; cell < 8; cell++) {
            float x = (cell & 1) * 0.5F + 0.25F, y = (cell >> 1 & 1) * 0.5F + 0.25F, z = (cell >> 2) * 0.5F + 0.25F;
            for (int i = 0; i < boxes.length; i += 6) {
                if (boxes[i] < x && x < boxes[i + 3] && boxes[i + 1] < y && y < boxes[i + 4] && boxes[i + 2] < z && z < boxes[i + 5]) {
                    cells |= 1 << cell;
                }
            }
        }
        return cells;
    }

    // Walls hold their connections in their states, so their palette boxes are already the client's: one box 1.5 high, out to each joined side, 4/16 in from the others, or 5/16 when the wall is straight with no post.
    @Test
    void wallsFollowTheirStates() {
        int walls = 0;
        for (int i = 0; i < table.paletteSize(); i++) {
            NbtMap states = table.states(i);
            // The border block has the wall states but its own boxes.
            if (!table.name(i).endsWith("_wall")) {
                continue;
            }
            boolean n = !states.getString("wall_connection_type_north").equals("none");
            boolean e = !states.getString("wall_connection_type_east").equals("none");
            boolean s = !states.getString("wall_connection_type_south").equals("none");
            boolean w = !states.getString("wall_connection_type_west").equals("none");
            boolean straight = n && s && !e && !w || e && w && !n && !s;
            float inset = states.getByte("wall_post_bit") == 0 && straight ? 0.3125F : 0.25F;
            float[] expected = {w ? 0 : inset, 0, n ? 0 : inset, e ? 1 : 1 - inset, 1.5F, s ? 1 : 1 - inset};
            assertArrayEquals(expected, table.collision(i), table.name(i) + " " + states);
            assertNull(this.override(i));
            walls++;
        }
        assertEquals(32 * 162, walls);
    }

    // The rules some blocks get from their state: open trapdoors on the side they lie against, stairs and shelves on their backs, top snow at full height, never single slabs.
    @Test
    void someNeighboursRuleByState() {
        int fence = find("oak_fence");
        float[] east = {0.375F, 0, 0.375F, 1, 1.5F, 0.625F};
        Map<Integer, Boolean> eastNeighbours = Map.ofEntries(
                Map.entry(find("trapdoor", "direction", 0, "open_bit", (byte) 1), true),
                Map.entry(find("trapdoor", "direction", 1, "open_bit", (byte) 1), false),
                Map.entry(find("trapdoor", "direction", 0, "open_bit", (byte) 0), false),
                Map.entry(stairs("oak_stairs", 1, 0), true),
                Map.entry(stairs("oak_stairs", 0, 0), false),
                Map.entry(find("oak_shelf", "minecraft:cardinal_direction", "east"), true),
                Map.entry(find("oak_shelf", "minecraft:cardinal_direction", "west"), false),
                Map.entry(find("snow_layer", "height", 7), true),
                Map.entry(find("snow_layer", "height", 6), false),
                Map.entry(find("oak_slab", "minecraft:vertical_half", "top"), false),
                Map.entry(find("oak_double_slab"), true));
        for (Map.Entry<Integer, Boolean> neighbour : eastNeighbours.entrySet()) {
            this.set(POS.east(), neighbour.getKey());
            assertArrayEquals(neighbour.getValue() ? east : POST, flatten(this.override(fence)), table.name(neighbour.getKey()) + " " + table.states(neighbour.getKey()));
        }
    }

    // A block outside the vanilla palette, such as a custom block, has no rule, so fences and panes join it; it is neither a stair nor a chorus plant.
    @Test
    void unknownBlocksHaveNoRule() {
        int unknown = table.paletteSize() + 5;
        this.set(POS.north(), unknown);
        this.set(POS.east(), unknown);
        assertBoxes(this.override(find("oak_fence")), 0.375F, 0, 0, 0.625F, 1.5F, 0.625F, 0.375F, 0, 0.375F, 1, 1.5F, 0.625F);
        assertBoxes(this.override(find("glass_pane")), 0.5F, 0, 0.4375F, 1, 1, 0.5625F, 0.4375F, 0, 0, 0.5625F, 1, 0.5F);
        assertBoxes(this.override(find("chorus_plant")), 0.125F, 0.125F, 0.125F, 0.875F, 0.875F, 0.875F);
        assertNull(this.override(stairs("oak_stairs", 0, 0)));
    }

    // The client's rules by block type: each fence lets its own kind join, panes, bars and walls let panes, bars and walls join, air and plants let nothing join,
    // and full blocks such as stone have no rule.
    @Test
    void rulesFromTheClient() {
        int fences = 1 << BedrockConnections.FENCE | 0xF0;
        int thin = 1 << BedrockConnections.WALL | 1 << BedrockConnections.THIN | 0xF0;
        Map<String, Integer> rules = Map.of(
                "oak_fence", fences,
                "pale_oak_fence", fences,
                "nether_brick_fence", 1 << BedrockConnections.NETHER_FENCE | 0xF0,
                "glass_pane", thin,
                "copper_bars", thin,
                "cobblestone_wall", thin,
                "air", 0,
                "dandelion", 0,
                "stone", BedrockConnections.ALL,
                "glass", BedrockConnections.ALL);
        rules.forEach((name, rule) -> assertEquals(rule, BedrockConnections.rule(find(name)), name));
    }

    // Every Java state beside a fence and a pane, on each side, against its Bedrock state through Geyser's mapping. Boar's Java model calls a face sturdy only for a block of one
    // full-cube box, while the client joins on the rules of each block. Bedrock joins the full back of stairs and shelves, open trapdoors, top snow at full height, the head of a
    // piston, the slightly smaller boxes of honey, mud and soul sand, the composter, and the structure void, which has no rule. It never joins chorus flowers, jigsaw and structure blocks.
    @Test
    void differencesFromJava() {
        Map<String, String> fences = new TreeMap<>();
        Map<String, String> panes = new TreeMap<>();
        int oakFence = java.defaultState(java.block("minecraft:oak_fence"));
        int glassPane = java.defaultState(java.block("minecraft:glass_pane"));
        Vector3i[] beside = {POS.north(), POS.east(), POS.south(), POS.west()};
        for (int state = 0; state < java.stateCount(); state++) {
            int index = java.toRuntime(state, false);
            for (int side = 0; side < 4; side++) {
                this.javaPlayer.compensatedWorld.updateBlock(beside[side], 0, javaMapping.fromIntermediary().applyAsInt(state));
                this.set(beside[side], index);
                boolean fenceJoins = this.reaches(find("oak_fence"), side);
                boolean paneJoins = this.reaches(find("glass_pane"), side);
                if (fenceJoins != "true".equals(java.value(this.reshaped(oakFence), SIDES[side]))) {
                    fences.put(table.name(index), fenceJoins ? "joins" : "apart");
                }
                if (paneJoins != "true".equals(java.value(this.reshaped(glassPane), SIDES[side]))) {
                    panes.put(table.name(index), paneJoins ? "joins" : "apart");
                }
                this.javaPlayer.compensatedWorld.updateBlock(beside[side], 0, javaMapping.fromIntermediary().applyAsInt(0));
                this.set(beside[side], table.firstIndex("minecraft:air"));
            }
        }
        Map<String, String> expected = new TreeMap<>();
        for (int i = 0; i < table.paletteSize(); i += table.stateCount(table.name(i))) {
            String name = table.name(i);
            if ((name.endsWith("_stairs") || name.endsWith("trapdoor") || name.endsWith("_shelf")) && java.paletteToJava(i) != 0) {
                expected.put(name, "joins");
            }
        }
        for (String name : Set.of("snow_layer", "piston_arm_collision", "sticky_piston_arm_collision", "honey_block", "mud", "soul_sand", "composter", "structure_void")) {
            expected.put("minecraft:" + name, "joins");
        }
        for (String name : Set.of("chorus_flower", "jigsaw", "structure_block")) {
            expected.put("minecraft:" + name, "apart");
        }
        assertEquals(expected, fences);
        assertEquals(expected, panes);
    }

    private int reshaped(int javaState) {
        AbstractBoarBlockState self = (AbstractBoarBlockState) TableBlockState.FACTORY.create(javaState, POS, 0);
        return self.delegate().applyConnectionShape(this.javaPlayer, self, POS).intermediaryId();
    }

    // Whether this fence or pane at POS reaches the side
    private boolean reaches(int index, int side) {
        for (Box box : this.override(index)) {
            boolean reaches = switch (side) {
                case 0 -> box.minZ == 0;
                case 1 -> box.maxX == 1;
                case 2 -> box.maxZ == 1;
                default -> box.minX == 0;
            };
            if (reaches) {
                return true;
            }
        }
        return false;
    }

    private static int find(String name) {
        return table.defaultIndex("minecraft:" + name);
    }

    private static float[] flatten(List<Box> boxes) {
        List<Float> values = new ArrayList<>();
        for (Box box : boxes) {
            values.addAll(List.of(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ));
        }
        float[] flat = new float[values.size()];
        for (int i = 0; i < flat.length; i++) {
            flat[i] = values.get(i);
        }
        return flat;
    }

    private static void assertBoxes(List<Box> boxes, float... expected) {
        assertArrayEquals(expected, flatten(boxes));
    }

    // Boar's collision asks the session only which random offsets the platform uses.
    private record Offline() implements NetworkSession {
        @Override
        public String name() {
            return "Tester";
        }

        @Override
        public UUID uuid() {
            return new UUID(0, 0);
        }

        @Override
        public void sendMessage(String message) {
        }

        @Override
        public boolean hasPermission(String permission) {
            return false;
        }

        @Override
        public boolean isClosed() {
            return false;
        }

        @Override
        public void disconnect(String reason) {
        }

        @Override
        public boolean requiresPingMagnitude() {
            return false;
        }

        @Override
        public int protocolVersion() {
            return 0;
        }
    }
}
