package ac.oomph.boarium.boar.blocks;

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
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Expected boxes and rules come from the 1.26.30 client (Minecraft Education 1.26.30.1 in Ghidra): FenceBlock::addCollisionShapes, ThinFenceBlock::addAABBs, ChorusPlantBlock::_getShape,
// StairBlock::setStepShape and setInnerPieceShape, WallBlock::getVisualShapeInWorld, the connection rules in VanillaBlockTypes::registerBlocks, and the _addHardCodedBlockComponents of
// fence gates, trapdoors, stairs, slabs, top snow, shelves and PistonArmBlock. Wall boxes are also Oomph's wall.go, and the BDS data in BedrockBlockTable.
// What boarium's old Java block table did, through Geyser's Java to Bedrock mapping (the states.26_30.txt fixture and JAVA_STAIR_CELLS), is compared where the two should agree, and every difference is listed.
class BedrockConnectionsTest {
    private static final Vector3i POS = Vector3i.from(8, 64, 8);
    private static final float[] POST = {0.375F, 0, 0.375F, 0.625F, 1.5F, 0.625F};
    private static final float[] PANE_POST = {0.4375F, 0, 0.4375F, 0.5625F, 1, 0.5625F};
    private static final String JAVA_STAIR_CELLS = "4039f1f32ecdc45a36ef3a04d81f1db32f0e2ecd10f5eac84850f74e056f7a72";

    private static final BedrockBlockTable V26_50 = BedrockBlockTable.forProtocol(2193);
    private static final BedrockBlockTable V26_60 = BedrockBlockTable.forProtocol(2225);
    private static BedrockBlockTable table;
    private BoarPlayer player;

    @BeforeAll
    static void load() throws Exception {
        TestBoar.start();
        table = BedrockBlockTable.get();
    }

    // A player whose runtime IDs are shared IDs, with one empty overworld chunk at chunk 0, 0
    @BeforeEach
    void world() {
        int airIndex = table.firstIndex("minecraft:air");
        SimpleBlockDefinition air = new SimpleBlockDefinition("minecraft:air", SharedIds.shared(airIndex), table.states(airIndex));
        this.player = new BoarPlayer(new Offline(), null, null, new BlockMappingInfo(air, air, air, air, IntList.of(air.getRuntimeId()), definition -> false, id -> id, id -> id), null, null, null, Map.of(), true);
        this.player.compensatedWorld.setDimension(Dimension.OVERWORLD);
        this.player.compensatedWorld.put(0, 0, new BoarChunkSection[24], new boolean[24]);
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

    // Boar's world holds the palette entry by its shared ID.
    private void set(Vector3i pos, int index) {
        this.player.compensatedWorld.updateBlock(pos, 0, SharedIds.shared(index));
    }

    private List<Box> override(int index) {
        return new BedrockState(SharedIds.shared(index), POS, 0).connectionCollisionOverride(this.player, POS);
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

    // Every arrangement of oak stairs around an oak stair covers the same space as boarium's old Java block table, whose corners followed Java's StairBlock.
    // JAVA_STAIR_CELLS is the SHA-256 of that table's cells, one byte per arrangement in this loop's order, taken at commit c200cbe through Geyser 2.11.3-b1248's mapping.
    @Test
    void stairCornersCoverWhatJavasDo() throws NoSuchAlgorithmException {
        int first = table.firstIndex("minecraft:oak_stairs");
        int[] around = new int[9];
        for (int i = 0; i < 8; i++) {
            around[i + 1] = first + i;
        }
        around[0] = table.firstIndex("minecraft:air");
        Vector3i[] beside = {POS.north(), POS.east(), POS.south(), POS.west()};
        byte[] cells = new byte[8 * 9 * 9 * 9 * 9];
        int n = 0;
        int corners = 0;
        for (int stair = first; stair < first + 8; stair++) {
            for (int arrangement = 0; arrangement < 9 * 9 * 9 * 9; arrangement++) {
                for (int side = 0, rest = arrangement; side < 4; side++, rest /= 9) {
                    this.set(beside[side], around[rest % 9]);
                }
                List<Box> boxes = this.override(stair);
                corners += boxes == null ? 0 : 1;
                cells[n++] = (byte) cells(boxes == null ? table.collision(stair) : flatten(boxes));
            }
        }
        assertEquals(JAVA_STAIR_CELLS, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(cells)),
                "pins Java's stair corners through Geyser 2.11.3-b1248's 1.26.30 mapping; with new Bedrock data, rebuild it from a Geyser build with that palette, or drop this check");
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
        int unknown = BedrockPalette.FIRST_CUSTOM_ID + 5;
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
        rules.forEach((name, rule) -> assertEquals(rule, BedrockConnections.rule(SharedIds.shared(find(name))), name));
    }

    // Every Bedrock state that Geyser maps a Java state to, beside a fence and a pane on each side, against the states.26_30.txt fixture. The Java table it came from called a face sturdy only for a block of one
    // full-cube box, while the client joins on the rules of each block. Bedrock joins the full back of stairs and shelves, open trapdoors, top snow at full height, the head of a
    // piston, the slightly smaller boxes of honey, mud and soul sand, the composter, and the structure void, which has no rule. It never joins chorus flowers, jigsaw and structure blocks.
    @Test
    void differencesFromJava() {
        Map<String, String> fences = new TreeMap<>();
        Map<String, String> panes = new TreeMap<>();
        Map<String, String> expected = new TreeMap<>();
        Vector3i[] beside = {POS.north(), POS.east(), POS.south(), POS.west()};
        GeyserFixtures.javaStates(table).forEach((index, java) -> {
            String name = table.name(index);
            for (int side = 0; side < 4; side++) {
                this.set(beside[side], index);
                boolean fenceJoins = this.reaches(find("oak_fence"), side);
                boolean paneJoins = this.reaches(find("glass_pane"), side);
                if (fenceJoins != (java.fenceJoins().charAt(side) == '1')) {
                    fences.put(name, fenceJoins ? "joins" : "apart");
                }
                if (paneJoins != (java.paneJoins().charAt(side) == '1')) {
                    panes.put(name, paneJoins ? "joins" : "apart");
                }
                this.set(beside[side], table.firstIndex("minecraft:air"));
            }
            if (name.endsWith("_stairs") || name.endsWith("trapdoor") || name.endsWith("_shelf")) {
                expected.put(name, "joins");
            }
        });
        for (String name : Set.of("snow_layer", "piston_arm_collision", "sticky_piston_arm_collision", "honey_block", "mud", "soul_sand", "composter", "structure_void")) {
            expected.put("minecraft:" + name, "joins");
        }
        for (String name : Set.of("chorus_flower", "jigsaw", "structure_block")) {
            expected.put("minecraft:" + name, "apart");
        }
        assertEquals(expected, fences);
        assertEquals(expected, panes);
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

    // From 26.50 a fence's state holds its connections, so its own boxes are its shape, whatever is next to it.
    @Test
    void fenceOf26_50TakesItsShapeFromItsState() {
        int north = shared(V26_50, "oak_fence", "minecraft:connection_north", (byte) 1, "minecraft:connection_east", (byte) 0, "minecraft:connection_south", (byte) 0, "minecraft:connection_west", (byte) 0);
        assertBoxes(this.boarBoxes(north), 0.375F, 0, 0, 0.625F, 1.5F, 0.625F);
        this.set(POS.east(), find("stone"));
        assertBoxes(this.boarBoxes(north), 0.375F, 0, 0, 0.625F, 1.5F, 0.625F);
    }

    // A 26.50 fence or pane with no connection set, the state Boar places for a newer client, builds its arms from its neighbours as the 1.26.30 one does, and a fence is a post with no neighbours.
    @Test
    void unconnectedFenceOf26_50JoinsItsNeighbours() {
        int unconnected = shared(V26_50, "oak_fence", "minecraft:connection_north", (byte) 0, "minecraft:connection_east", (byte) 0, "minecraft:connection_south", (byte) 0, "minecraft:connection_west", (byte) 0);
        assertBoxes(this.boarBoxes(unconnected), POST);
        this.player.compensatedWorld.updateBlock(POS.north(), 0, unconnected);
        this.set(POS.east(), find("stone"));
        assertBoxes(this.boarBoxes(unconnected), 0.375F, 0, 0, 0.625F, 1.5F, 0.625F, 0.375F, 0, 0.375F, 1, 1.5F, 0.625F);
        int pane = shared(V26_50, "glass_pane", "minecraft:connection_north", (byte) 0, "minecraft:connection_east", (byte) 0, "minecraft:connection_south", (byte) 0, "minecraft:connection_west", (byte) 0);
        assertBoxes(this.boarBoxes(pane), 0.5F, 0, 0.4375F, 1, 1, 0.5625F);
    }

    // A 26.50 stair with corner none builds its corner from the stairs around it: a stair facing north ahead of an east-facing stair cuts its step to the north-east quarter.
    @Test
    void stairOf26_50WithoutACornerTakesItFromItsNeighbours() {
        int east = shared(V26_50, "oak_stairs", "weirdo_direction", 0, "upside_down_bit", (byte) 0, "minecraft:corner", "none");
        assertNull(new BedrockState(east, POS, 0).connectionCollisionOverride(this.player, POS));
        this.player.compensatedWorld.updateBlock(POS.east(), 0, shared(V26_50, "oak_stairs", "weirdo_direction", 3, "upside_down_bit", (byte) 0, "minecraft:corner", "none"));
        assertBoxes(this.boarBoxes(east), 0, 0, 0, 1, 0.5F, 1, 0.5F, 0.5F, 0, 1, 1, 0.5F);
    }

    // A 26.50 stair beside it that faces the same way keeps a corner-none stair straight, as in onlyTheSameStairBesideKeepsItStraight, whatever corner that stair holds.
    @Test
    void stairOf26_50BesideOneWithACornerStaysStraight() {
        int east = shared(V26_50, "oak_stairs", "weirdo_direction", 0, "upside_down_bit", (byte) 0, "minecraft:corner", "none");
        this.player.compensatedWorld.updateBlock(POS.east(), 0, shared(V26_50, "oak_stairs", "weirdo_direction", 3, "upside_down_bit", (byte) 0, "minecraft:corner", "outer_left"));
        this.player.compensatedWorld.updateBlock(POS.south(), 0, shared(V26_50, "oak_stairs", "weirdo_direction", 0, "upside_down_bit", (byte) 0, "minecraft:corner", "outer_right"));
        assertNull(new BedrockState(east, POS, 0).connectionCollisionOverride(this.player, POS));
        // Another kind of stair beside does not.
        this.player.compensatedWorld.updateBlock(POS.south(), 0, shared(V26_50, "stone_stairs", "weirdo_direction", 0, "upside_down_bit", (byte) 0, "minecraft:corner", "outer_right"));
        assertBoxes(this.boarBoxes(east), 0, 0, 0, 1, 0.5F, 1, 0.5F, 0.5F, 0, 1, 1, 0.5F);
    }

    // The 1.26.30 fence has no states, so it still builds its arms from its neighbours.
    @Test
    void fenceOf26_30TakesItsShapeFromItsNeighbours() {
        int fence = SharedIds.shared(find("oak_fence"));
        assertBoxes(this.boarBoxes(fence), POST);
        this.set(POS.north(), find("stone"));
        assertBoxes(this.boarBoxes(fence), 0.375F, 0, 0, 0.625F, 1.5F, 0.625F);
    }

    // A 26.40 poplar fence has no states, so it builds its arms from its neighbours by the rules for blocks added after 1.26.30: it joins poplar planks and fences, but not poplar leaves.
    @Test
    void poplarFenceOf26_40JoinsByTheNewerRules() {
        BedrockBlockTable v26_40 = BedrockBlockTable.forProtocol(2168);
        int fence = shared(v26_40, "poplar_fence");
        this.player.compensatedWorld.updateBlock(POS.north(), 0, shared(v26_40, "poplar_planks"));
        this.player.compensatedWorld.updateBlock(POS.east(), 0, shared(v26_40, "poplar_fence"));
        this.player.compensatedWorld.updateBlock(POS.south(), 0, shared(v26_40, "orange_poplar_leaves"));
        assertBoxes(this.boarBoxes(fence), 0.375F, 0, 0, 0.625F, 1.5F, 0.625F, 0.375F, 0, 0.375F, 1, 1.5F, 0.625F);
    }

    // Every block name that 1.26.30 does not have gets a rule from connection_rules.after_26_30.txt or from its states, or is in letEveryKindJoin.
    // That file names no 1.26.30 block, so the 1.26.30 rules stay as traced.
    @Test
    void namesAfter26_30HaveRules() throws IOException {
        BedrockBlockTable shared = BedrockBlockUnion.get().table();
        // Full blocks with no rule, as the 1.26.30 blocks they copy: oak's planks, logs and wood, crafting_table for the disc press, and every double slab
        Set<String> letEveryKindJoin = new TreeSet<>();
        for (String name : List.of("poplar_planks", "poplar_log", "poplar_wood", "stripped_poplar_log", "stripped_poplar_wood", "disc_press")) {
            letEveryKindJoin.add("minecraft:" + name);
        }
        for (String name : List.of("oak_planks", "oak_log", "oak_wood", "stripped_oak_log", "stripped_oak_wood", "crafting_table", "oak_double_slab")) {
            assertEquals(BedrockConnections.ALL, BedrockConnections.rule(SharedIds.shared(find(name))), name);
        }
        BedrockProperties.doubleSlabs(shared).values().stream().filter(name -> table.firstIndex(name) < 0).forEach(letEveryKindJoin::add);

        Set<String> withoutRule = new TreeSet<>();
        Set<String> everyKindJoins = new TreeSet<>();
        int newer = 0;
        for (int first = 0; first < shared.paletteSize(); first += shared.stateCount(shared.name(first))) {
            String name = shared.name(first);
            if (table.firstIndex(name) >= 0) {
                continue;
            }
            newer++;
            boolean ruled = true;
            boolean everyKind = true;
            for (int i = first; i < first + shared.stateCount(name); i++) {
                ruled &= BedrockConnections.rule(i) != BedrockConnections.ALL;
                everyKind &= BedrockConnections.rule(i) == BedrockConnections.ALL;
            }
            if (everyKind) {
                everyKindJoins.add(name);
            }
            if (!ruled && !letEveryKindJoin.contains(name)) {
                withoutRule.add(name);
            }
        }
        assertEquals(Set.of(), withoutRule);
        assertEquals(124, newer);
        assertEquals(39, letEveryKindJoin.size());
        assertEquals(letEveryKindJoin, everyKindJoins);

        String after = BedrockConnections.RULES_RESOURCES.getLast();
        try (InputStream in = BedrockConnections.class.getResourceAsStream(after)) {
            List<String> names = new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().filter(line -> !line.isBlank() && !line.startsWith("#")).map(line -> line.split(" ")[0]).toList();
            assertEquals(16, names.size());
            names.forEach(name -> assertTrue(table.firstIndex(name) < 0, name));
        }
    }

    // From 26.60 a chorus plant's state holds its six connections; the 26.50 chorus plant has one state and still looks at its neighbours.
    @Test
    void chorusPlantOf26_60TakesItsShapeFromItsState() {
        int up = shared(V26_60, "chorus_plant", "minecraft:connection_up", (byte) 1, "minecraft:connection_down", (byte) 0, "minecraft:connection_north", (byte) 0,
                "minecraft:connection_east", (byte) 0, "minecraft:connection_south", (byte) 0, "minecraft:connection_west", (byte) 0);
        assertBoxes(this.boarBoxes(up), 0.125F, 0.125F, 0.125F, 0.875F, 1, 0.875F);
        this.set(POS.down(), find("end_stone"));
        assertBoxes(this.boarBoxes(up), 0.125F, 0.125F, 0.125F, 0.875F, 1, 0.875F);

        int single = shared(V26_50, "chorus_plant");
        assertEquals(1, V26_50.stateCount("minecraft:chorus_plant"));
        assertBoxes(this.boarBoxes(single), 0.125F, 0, 0.125F, 0.875F, 0.875F, 0.875F);
    }

    // From 26.50 a stair's state holds its corner. An east-facing bottom stair's inner left corner adds the north-west quarter, whatever stairs are around it.
    @Test
    void stairOf26_50TakesItsCornerFromItsState() {
        int inner = shared(V26_50, "oak_stairs", "weirdo_direction", 0, "upside_down_bit", (byte) 0, "minecraft:corner", "inner_left");
        float[] own = V26_50.collision(V26_50.index("minecraft:oak_stairs", SharedIds.SHARED.states(inner)));
        assertEquals(cells(new float[]{0, 0, 0, 1, 0.5F, 1, 0.5F, 0.5F, 0, 1, 1, 1, 0, 0.5F, 0, 0.5F, 1, 0.5F}), cells(own));
        assertArrayEquals(own, flatten(this.boarBoxes(inner)));
        // A stair facing north ahead would make an outer corner from the neighbours.
        this.set(POS.east(), stairs("oak_stairs", 3, 0));
        assertArrayEquals(own, flatten(this.boarBoxes(inner)));
    }

    // A 26.50 stair is still a stair to the stairs next to it: one facing north ahead of a 1.26.30 east-facing stair cuts its step to the north-east quarter.
    @Test
    void stairOf26_50MakesTheCornerOfAStairBeside() {
        int ahead = shared(V26_50, "oak_stairs", "weirdo_direction", 3, "upside_down_bit", (byte) 0, "minecraft:corner", "none");
        this.player.compensatedWorld.updateBlock(POS.east(), 0, ahead);
        assertBoxes(this.override(stairs("oak_stairs", 0, 0)), 0, 0, 0, 1, 0.5F, 1, 0.5F, 0.5F, 0, 1, 1, 0.5F);
    }

    // Which fences, panes, bars, stairs and chorus plants of each version's table take their shape from their states: none up to 26.40, from 26.50 all but the chorus plant, and from 26.60 all.
    // A fence, pane, bar or stair state with no connection or corner set builds from its neighbours. No other block builds its shape from its neighbours, so walls, trip wire, fire and redstone wire, which also hold their connections, keep their table boxes.
    @Test
    void namesThatTakeTheirShapeFromTheirStates() {
        Map<String, Set<String>> byVersion = new TreeMap<>();
        Map<String, Integer> families = new TreeMap<>();
        for (int protocol : BedrockBlockTable.VERSIONS.keySet()) {
            BedrockBlockTable own = BedrockBlockTable.forProtocol(protocol);
            Set<String> fromStates = new TreeSet<>();
            int family = 0;
            for (int first = 0; first < own.paletteSize(); first += own.stateCount(own.name(first))) {
                String name = own.name(first);
                boolean inFamily = name.endsWith("_fence") || name.endsWith("_pane") || name.endsWith("_bars") || name.endsWith("_stairs") || name.equals("minecraft:chorus_plant");
                family += inFamily ? 1 : 0;
                for (int i = first; i < first + own.stateCount(name); i++) {
                    boolean fromNeighbours = BedrockConnections.fromNeighbours(BedrockBlockUnion.get().toShared(protocol, i));
                    assertTrue(inFamily || !fromNeighbours, name);
                    if (inFamily && protocol >= V26_50.protocol()) {
                        boolean noneSet = own.states(i).entrySet().stream().noneMatch(state -> state.getKey().startsWith("minecraft:connection_") && state.getValue().equals((byte) 1)
                                || state.getKey().equals("minecraft:corner") && !state.getValue().equals("none"));
                        boolean expected = name.equals("minecraft:chorus_plant") ? protocol == V26_50.protocol() : noneSet;
                        assertEquals(expected, fromNeighbours, name + " " + own.states(i));
                    }
                    if (inFamily && !fromNeighbours) {
                        fromStates.add(name);
                    }
                }
            }
            byVersion.put(BedrockBlockTable.version(protocol), fromStates);
            families.put(BedrockBlockTable.version(protocol), family);
        }
        for (String version : List.of("26_0", "26_10", "26_20", "26_30", "26_40")) {
            assertEquals(Set.of(), byVersion.get(version), version);
        }
        assertEquals(155, families.get("26_50"));
        assertEquals(154, byVersion.get("26_50").size());
        assertFalse(byVersion.get("26_50").contains("minecraft:chorus_plant"));
        assertEquals(155, byVersion.get("26_60").size());
    }

    // The shared ID of the first state of a version's table with this name and these state values
    private static int shared(BedrockBlockTable own, String name, Object... states) {
        int first = own.firstIndex("minecraft:" + name);
        assertTrue(first >= 0, name);
        for (int i = first; i < first + own.stateCount("minecraft:" + name); i++) {
            boolean match = true;
            for (int s = 0; s < states.length; s += 2) {
                match &= states[s + 1].equals(own.states(i).get((String) states[s]));
            }
            if (match) {
                return BedrockBlockUnion.get().toShared(own.protocol(), i);
            }
        }
        throw new AssertionError(name + " has no state " + List.of(states));
    }

    // The boxes Boar collides with for this shared ID at POS, moved back to the block's own corner
    private List<Box> boarBoxes(int id) {
        List<Box> boxes = new ArrayList<>();
        for (Box box : BedrockState.FACTORY.create(id, POS, 0).findCollision(this.player, POS, Box.EMPTY, false)) {
            boxes.add(box.offset(-POS.getX(), -POS.getY(), -POS.getZ()));
        }
        return boxes;
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
