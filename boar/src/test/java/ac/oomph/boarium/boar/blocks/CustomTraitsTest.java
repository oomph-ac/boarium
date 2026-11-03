package ac.oomph.boarium.boar.blocks;

import ac.boar.anticheat.player.data.BlockMappingInfo;
import ac.boar.anticheat.util.math.Box;
import ac.oomph.boarium.boar.TestBoar;
import org.cloudburstmc.nbt.NbtList;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtMapBuilder;
import org.cloudburstmc.nbt.NbtType;
import org.cloudburstmc.protocol.bedrock.data.BlockPropertyData;
import org.cloudburstmc.protocol.bedrock.data.definitions.SimpleBlockDefinition;
import org.cloudburstmc.protocol.bedrock.packet.StartGamePacket;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

// The expected states come from the 1.26.30 client (Ghidra), the trait orders from a real libc++ unordered_map, and the network hashes from an independent Go NBT writer and dragonfly's networkBlockHash.
class CustomTraitsTest {
    private static final int SORTED_STONE = 2706;
    private static final NbtMap VANILLA_BLOCK_DATA = NbtMap.builder().putInt("block_id", 0).build();
    private static final String CARDINAL = "minecraft:cardinal_direction";

    @BeforeAll
    static void start() throws Exception {
        TestBoar.start();
    }

    // A trait with these enabled_states, as a BDS add-on server sends it
    private static NbtMap trait(String name, NbtMap enabledStates) {
        return NbtMap.builder().putString("name", name).putCompound("enabled_states", enabledStates).build();
    }

    private static NbtMap enabled(String... states) {
        NbtMapBuilder enabled = NbtMap.builder();
        for (String state : states) {
            enabled.putByte(state, (byte) 1);
        }
        return enabled.build();
    }

    private static NbtMap direction(String... states) {
        return trait("minecraft:placement_direction", enabled(states));
    }

    private static NbtMap position(String... states) {
        return trait("minecraft:placement_position", enabled(states));
    }

    private static NbtMap connection(int enabledStates) {
        return NbtMap.builder().putString("name", "minecraft:connection").putInt("type", 0).putInt("enabled_states", enabledStates).build();
    }

    private static NbtMap multiBlock(int parts) {
        return NbtMap.builder().putString("name", "minecraft:multi_block").putCompound("enabled_states", enabled("multi_block")).putByte("part_count", (byte) parts).putByte("direction", (byte) 1).build();
    }

    @SuppressWarnings("unchecked")
    private static NbtMap property(String name, NbtType<?> type, Object... values) {
        NbtMapBuilder property = NbtMap.builder().putString("name", name);
        property.put("enum", new NbtList<>((NbtType<Object>) type, values));
        return property.build();
    }

    // cubecraft:pipe with these traits and properties, which sorts before stone and air
    private static BlockPropertyData block(List<NbtMap> traits, NbtMap... properties) {
        return block(traits, List.of(properties), List.of());
    }

    private static BlockPropertyData block(List<NbtMap> traits, List<NbtMap> properties, List<NbtMap> permutations) {
        NbtMapBuilder data = NbtMap.builder().putList("traits", NbtType.COMPOUND, traits).putList("properties", NbtType.COMPOUND, properties).putCompound("vanilla_block_data", VANILLA_BLOCK_DATA);
        if (!permutations.isEmpty()) {
            data.putList("permutations", NbtType.COMPOUND, permutations).putInt("molangVersion", 12);
        }
        return new BlockPropertyData("cubecraft:pipe", data.build());
    }

    private static CustomBlock of(NbtMap... traits) {
        return CustomBlock.of(block(List.of(traits)));
    }

    private static NbtMap states(Object... keysAndValues) {
        NbtMapBuilder states = NbtMap.builder();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            states.put((String) keysAndValues[i], keysAndValues[i + 1]);
        }
        return states.build();
    }

    private static List<NbtMap> all(CustomBlock block) {
        List<NbtMap> states = new ArrayList<>();
        for (int i = 0; i < block.stateCount(); i++) {
            states.add(block.state(i));
        }
        return states;
    }

    @Test
    void placementDirectionAddsItsStatesInTheClientsOrder() {
        CustomBlock cardinal = of(direction("cardinal_direction"));
        assertEquals(List.of(CARDINAL), cardinal.keys());
        assertEquals(List.of(states(CARDINAL, "south"), states(CARDINAL, "west"), states(CARDINAL, "north"), states(CARDINAL, "east")), all(cardinal));

        // corner_and_cardinal_direction adds cardinal_direction too, then corner.
        CustomBlock corner = of(direction("corner_and_cardinal_direction"));
        assertEquals(List.of(CARDINAL, "minecraft:corner"), corner.keys());
        assertEquals(20, corner.stateCount());
        assertEquals(states(CARDINAL, "south", "minecraft:corner", "inner_left"), corner.state(4));
        assertEquals(states(CARDINAL, "east", "minecraft:corner", "outer_right"), corner.state(19));

        CustomBlock facing = of(direction("facing_direction"));
        assertEquals(List.of("down", "up", "north", "south", "west", "east"), all(facing).stream().map(state -> state.getString("minecraft:facing_direction")).toList());

        // The client names the values past the 6th "down".
        CustomBlock sixteen = of(direction("sixteen_way_rotation"));
        assertEquals(16, sixteen.stateCount());
        assertEquals(List.of("down", "up", "north", "south", "west", "east", "down", "down", "down", "down", "down", "down", "down", "down", "down", "down"), all(sixteen).stream().map(state -> state.getString("minecraft:sixteen_way_rotation")).toList());

        // Whatever order the flags come in: cardinal_direction, corner, facing_direction, sixteen_way_rotation.
        CustomBlock every = of(direction("sixteen_way_rotation", "facing_direction", "corner_and_cardinal_direction", "cardinal_direction"));
        assertEquals(List.of(CARDINAL, "minecraft:corner", "minecraft:facing_direction", "minecraft:sixteen_way_rotation"), every.keys());
        assertEquals(4 * 5 * 6 * 16, every.stateCount());
        assertEquals(states(CARDINAL, "south", "minecraft:corner", "none", "minecraft:facing_direction", "up", "minecraft:sixteen_way_rotation", "down"), every.state(20));

        // A flag that is not a byte, or 0, is off, and so is a trait without enabled_states.
        NbtMap intFlag = trait("minecraft:placement_direction", NbtMap.builder().putInt("cardinal_direction", 1).putByte("facing_direction", (byte) 0).build());
        assertEquals(List.of(), of(intFlag).keys());
        assertEquals(1, of(NbtMap.builder().putString("name", "minecraft:placement_direction").build()).stateCount());
    }

    @Test
    void placementPositionAddsBlockFaceThenVerticalHalf() {
        CustomBlock block = of(position("vertical_half", "block_face"));
        assertEquals(List.of("minecraft:block_face", "minecraft:vertical_half"), block.keys());
        assertEquals(12, block.stateCount());
        assertEquals(states("minecraft:block_face", "east", "minecraft:vertical_half", "bottom"), block.state(5));
        assertEquals(states("minecraft:block_face", "down", "minecraft:vertical_half", "top"), block.state(6));
    }

    @Test
    void connectionAddsFourBoolsWhenBit0IsSet() {
        CustomBlock block = of(connection(1));
        assertEquals(List.of("minecraft:connection_north", "minecraft:connection_east", "minecraft:connection_south", "minecraft:connection_west"), block.keys());
        assertEquals(16, block.stateCount());
        assertEquals(states("minecraft:connection_north", (byte) 1, "minecraft:connection_east", (byte) 0, "minecraft:connection_south", (byte) 0, "minecraft:connection_west", (byte) 1), block.state(9));
        assertEquals(16, of(connection(3)).stateCount());
        // Only bit 0 counts, enabled_states is read only from an int, and any "type" value still adds the states.
        assertEquals(1, of(connection(2)).stateCount());
        assertEquals(1, of(NbtMap.builder().putString("name", "minecraft:connection").putByte("enabled_states", (byte) 1).build()).stateCount());
        assertEquals(16, of(NbtMap.builder().putString("name", "minecraft:connection").putInt("type", 5).putInt("enabled_states", 1).build()).stateCount());
    }

    @Test
    void multiBlockHasOneValueForEachPart() {
        CustomBlock block = of(multiBlock(6));
        assertEquals(List.of("minecraft:multi_block_part"), block.keys());
        // The client writes the parts from the 5th on as 0.
        assertEquals(List.of(0, 1, 2, 3, 0, 0), all(block).stream().map(state -> state.getInt("minecraft:multi_block_part")).toList());
        // part_count is an unsigned byte.
        assertEquals(255, of(multiBlock(-1)).stateCount());
        // Without the multi_block flag, it adds nothing.
        assertEquals(1, of(NbtMap.builder().putString("name", "minecraft:multi_block").putCompound("enabled_states", NbtMap.EMPTY).putByte("part_count", (byte) 4).build()).stateCount());
        // Without an enabled_states compound, the trait keeps its defaults: on, with 2 parts, whatever part_count says.
        assertEquals(List.of(0, 1), all(of(NbtMap.builder().putString("name", "minecraft:multi_block").build())).stream().map(state -> state.getInt("minecraft:multi_block_part")).toList());
        assertEquals(List.of(0, 1), all(of(NbtMap.builder().putString("name", "minecraft:multi_block").putInt("enabled_states", 1).putByte("part_count", (byte) 6).build())).stream().map(state -> state.getInt("minecraft:multi_block_part")).toList());
        // A part count of 0, missing, or not a byte, is a state without values, which the client cannot read.
        assertThrows(IllegalArgumentException.class, () -> of(multiBlock(0)));
        assertThrows(IllegalArgumentException.class, () -> of(NbtMap.builder().putString("name", "minecraft:multi_block").putCompound("enabled_states", enabled("multi_block")).putInt("part_count", 4).build()));
    }

    @Test
    void traitStatesTakeTheLowestBits() {
        CustomBlock block = CustomBlock.of(block(List.of(direction("cardinal_direction")), property("open", NbtType.BYTE, (byte) 0, (byte) 1)));
        assertEquals(List.of(CARDINAL, "open"), block.keys());
        assertEquals(states(CARDINAL, "west", "open", (byte) 0), block.state(1));
        assertEquals(states(CARDINAL, "south", "open", (byte) 1), block.state(4));
        // A property with a trait state's name is another state, which the state's NBT shows.
        CustomBlock same = CustomBlock.of(block(List.of(direction("cardinal_direction")), property(CARDINAL, NbtType.INT, 7, 8)));
        assertEquals(8, same.stateCount());
        assertEquals(states(CARDINAL, 8), same.state(4));
    }

    @Test
    void readsTraitsAsTheClientDoes() {
        // An unknown name, or none, is skipped, and a name is found in any case but keeps its own: two cases of a name are two traits, of which the second adds only what the first did not.
        NbtMap unknown = trait("minecraft:unknown_trait", enabled("cardinal_direction"));
        NbtMap noName = NbtMap.builder().putCompound("enabled_states", enabled("cardinal_direction")).build();
        NbtMap upper = trait("MINECRAFT:Placement_Direction", enabled("cardinal_direction"));
        NbtMap lower = direction("cardinal_direction", "facing_direction");
        assertEquals(List.of(CARDINAL, "minecraft:facing_direction"), of(unknown, noName, upper, lower).keys());
        // The first trait of a name stays.
        assertEquals(List.of(CARDINAL), of(direction("cardinal_direction"), direction("facing_direction")).keys());
        // Traits that are not a list of compounds are none.
        assertEquals(1, CustomBlock.of(new BlockPropertyData("cubecraft:pipe", NbtMap.builder().putCompound("traits", direction("cardinal_direction")).build())).stateCount());
        assertEquals(1, CustomBlock.of(new BlockPropertyData("cubecraft:pipe", NbtMap.builder().putList("traits", NbtType.STRING, "minecraft:connection").build())).stateCount());
    }

    // Each list of trait names whose order the client changes, from a libc++ unordered_map filled in list order and then copied; every other list keeps its order.
    private static final List<String> CHANGED_ORDERS = List.of(
            "multi_block placement_direction placement_position -> multi_block placement_position placement_direction",
            "placement_position placement_direction multi_block -> placement_position multi_block placement_direction",
            "multi_block connection placement_position -> multi_block placement_position connection",
            "placement_position connection multi_block -> placement_position multi_block connection",
            "connection multi_block placement_direction placement_position -> connection multi_block placement_position placement_direction",
            "connection placement_position placement_direction multi_block -> connection placement_position multi_block placement_direction",
            "multi_block connection placement_direction placement_position -> multi_block placement_position connection placement_direction",
            "multi_block connection placement_position placement_direction -> multi_block placement_position connection placement_direction",
            "multi_block placement_direction connection placement_position -> multi_block placement_position placement_direction connection",
            "multi_block placement_direction placement_position connection -> multi_block placement_position placement_direction connection",
            "placement_direction multi_block connection placement_position -> placement_direction multi_block placement_position connection",
            "placement_direction placement_position connection multi_block -> placement_direction placement_position multi_block connection",
            "placement_position connection multi_block placement_direction -> placement_position multi_block connection placement_direction",
            "placement_position connection placement_direction multi_block -> placement_position multi_block connection placement_direction",
            "placement_position placement_direction connection multi_block -> placement_position multi_block placement_direction connection",
            "placement_position placement_direction multi_block connection -> placement_position multi_block placement_direction connection");

    private static List<String> names(String list) {
        return Arrays.stream(list.split(" ")).map(name -> "minecraft:" + name).toList();
    }

    @Test
    void traitsApplyInTheOrderOfTheClientsHashMap() {
        assertEquals(0x7b6852b2025891d4L, CustomTraits.hash("minecraft:placement_direction"));
        assertEquals(0xb9389e76273a1792L, CustomTraits.hash("minecraft:placement_position"));
        assertEquals(0xf7e42da33ffe0448L, CustomTraits.hash("minecraft:connection"));
        assertEquals(0xa26a28df6743128bL, CustomTraits.hash("minecraft:multi_block"));

        Map<List<String>, List<String>> changed = new HashMap<>();
        for (String row : CHANGED_ORDERS) {
            String[] sides = row.split(" -> ");
            changed.put(names(sides[0]), names(sides[1]));
        }
        int lists = 0;
        List<String> all = names("placement_direction placement_position connection multi_block");
        for (List<String> list : permutationsOfSubsets(all)) {
            assertEquals(changed.getOrDefault(list, list), CustomTraits.order(list), list.toString());
            lists++;
        }
        assertEquals(64, lists);

        // As a block: placement_position, placement_direction, multi_block applies multi_block before placement_direction.
        CustomBlock three = of(position("block_face"), direction("cardinal_direction"), multiBlock(2));
        assertEquals(List.of("minecraft:block_face", "minecraft:multi_block_part", CARDINAL), three.keys());
        assertEquals(states("minecraft:block_face", "down", "minecraft:multi_block_part", 1, CARDINAL, "south"), three.state(6));
        // placement_direction, placement_position, connection, multi_block applies multi_block before connection.
        CustomBlock four = of(direction("cardinal_direction"), position("vertical_half"), connection(1), multiBlock(3));
        assertEquals(List.of(CARDINAL, "minecraft:vertical_half", "minecraft:multi_block_part", "minecraft:connection_north", "minecraft:connection_east", "minecraft:connection_south", "minecraft:connection_west"), four.keys());
        // 3 traits in an order the client keeps
        assertEquals(List.of(CARDINAL, "minecraft:vertical_half", "minecraft:connection_north", "minecraft:connection_east", "minecraft:connection_south", "minecraft:connection_west"), of(direction("cardinal_direction"), position("vertical_half"), connection(1)).keys());
    }

    private static List<List<String>> permutationsOfSubsets(List<String> names) {
        List<List<String>> lists = new ArrayList<>();
        for (int mask = 1; mask < 1 << names.size(); mask++) {
            List<String> subset = new ArrayList<>();
            for (int i = 0; i < names.size(); i++) {
                if ((mask & 1 << i) != 0) {
                    subset.add(names.get(i));
                }
            }
            permute(subset, 0, lists);
        }
        return lists;
    }

    private static void permute(List<String> list, int from, List<List<String>> out) {
        if (from == list.size()) {
            out.add(List.copyOf(list));
            return;
        }
        for (int i = from; i < list.size(); i++) {
            Collections.swap(list, from, i);
            permute(list, from + 1, out);
            Collections.swap(list, from, i);
        }
    }

    // The client makes states only from the lowest 16 bits, and follows no more than 32, with the traits' bits counted.
    @Test
    void traitBitsCountTowardsTheLimits() {
        // placement_direction takes bits 0 to 11, and 32 parts bits 12 to 16, of which 4 count; the property after them spans one value.
        CustomBlock block = CustomBlock.of(block(List.of(direction("cardinal_direction", "corner_and_cardinal_direction", "facing_direction", "sixteen_way_rotation"), multiBlock(32)), property("open", NbtType.BYTE, (byte) 0, (byte) 1)));
        assertEquals(1920 * 16, block.stateCount());
        assertEquals(states(CARDINAL, "east", "minecraft:corner", "outer_right", "minecraft:facing_direction", "east", "minecraft:sixteen_way_rotation", "down", "minecraft:multi_block_part", 0, "open", (byte) 0), block.state(1920 * 16 - 1));
        assertEquals(states(CARDINAL, "south", "minecraft:corner", "none", "minecraft:facing_direction", "down", "minecraft:sixteen_way_rotation", "down", "minecraft:multi_block_part", 3, "open", (byte) 0), block.state(1920 * 3));
        // 28 bits of traits leave 4 for properties. The client applies multi_block before connection, so placement_position ends at bit 15 and the states are those of the first two traits.
        List<NbtMap> traits = List.of(direction("cardinal_direction", "corner_and_cardinal_direction", "facing_direction", "sixteen_way_rotation"), position("block_face", "vertical_half"), connection(1), multiBlock(255));
        Object[] sixteen = new Object[16];
        Object[] seventeen = new Object[17];
        Arrays.setAll(sixteen, i -> i);
        Arrays.setAll(seventeen, i -> i);
        assertEquals(1920 * 12, CustomBlock.of(block(traits, property("n", NbtType.INT, sixteen))).stateCount());
        assertThrows(IllegalArgumentException.class, () -> CustomBlock.of(block(traits, property("n", NbtType.INT, seventeen))));
    }

    private static NbtMap noCollision(String condition) {
        NbtMap disabled = NbtMap.builder().putBoolean("enabled", false).putList("boxes", NbtType.COMPOUND).build();
        return NbtMap.builder().putString("condition", condition).putCompound("components", NbtMap.builder().putCompound("minecraft:collision_box", disabled).build()).build();
    }

    // The states of a block with no collision, by index
    private static List<Integer> passable(CustomBlock block) {
        assertNull(block.collision().guess());
        List<Integer> passable = new ArrayList<>();
        for (int s = 0; s < block.stateCount(); s++) {
            List<Box> boxes = block.collision().boxes(s);
            if (boxes.isEmpty()) {
                passable.add(s);
            } else {
                assertEquals(CustomCollision.FULL_CUBE, boxes);
            }
        }
        return passable;
    }

    // q.block_state reads a trait state as the client names its value: a string, a bool as 0 or 1, and an int.
    @Test
    void permutationsReadTraitStates() {
        CustomBlock cardinal = CustomBlock.of(block(List.of(direction("cardinal_direction")), List.of(), List.of(noCollision("q.block_state('minecraft:cardinal_direction') == 'north'"))));
        assertEquals(List.of(2), passable(cardinal));
        CustomBlock connected = CustomBlock.of(block(List.of(connection(1)), List.of(), List.of(noCollision("query.block_state('minecraft:connection_west') && !q.block_state('minecraft:connection_north')"))));
        assertEquals(List.of(8, 10, 12, 14), passable(connected));
        CustomBlock parts = CustomBlock.of(block(List.of(multiBlock(6)), List.of(), List.of(noCollision("q.block_state('minecraft:multi_block_part') == 0"))));
        assertEquals(List.of(0, 4, 5), passable(parts));
        CustomBlock sixteen = CustomBlock.of(block(List.of(direction("sixteen_way_rotation")), List.of(), List.of(noCollision("q.block_state('minecraft:sixteen_way_rotation') == 'down'"))));
        assertEquals(List.of(0, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15), passable(sixteen));
    }

    private static StartGamePacket startGame(boolean hashed, BlockPropertyData block) {
        StartGamePacket startGame = new StartGamePacket();
        startGame.setBlockNetworkIdsHashed(hashed);
        startGame.getBlockProperties().add(block);
        return startGame;
    }

    @Test
    void sortedIdsMoveStoneByTheTraitStates() {
        BlockMappingInfo cardinal = BedrockPalette.mappingInfo(startGame(false, block(List.of(direction("cardinal_direction")))));
        assertEquals(SORTED_STONE + 4, cardinal.fromIntermediary().applyAsInt(SORTED_STONE));
        BlockMappingInfo three = BedrockPalette.mappingInfo(startGame(false, block(List.of(position("block_face"), direction("cardinal_direction"), multiBlock(2)))));
        assertEquals(SORTED_STONE + 6 * 4 * 2, three.fromIntermediary().applyAsInt(SORTED_STONE));
        // A multi_block trait of only a name has its 2 default parts.
        BlockMappingInfo defaults = BedrockPalette.mappingInfo(startGame(false, block(List.of(NbtMap.builder().putString("name", "minecraft:multi_block").build()))));
        assertEquals(SORTED_STONE + 2, defaults.fromIntermediary().applyAsInt(SORTED_STONE));
    }

    // Each case of a trait's name is another trait to the client, so Boar bounds them.
    @Test
    void traitsAreBounded() {
        List<NbtMap> cases = new ArrayList<>();
        for (int mask = 0; mask <= CustomTraits.MAX_TRAITS; mask++) {
            char[] name = "minecraft:connection".toCharArray();
            for (int bit = 0; bit < 5; bit++) {
                if ((mask & 1 << bit) != 0) {
                    name[bit] = Character.toUpperCase(name[bit]);
                }
            }
            cases.add(NbtMap.builder().putString("name", new String(name)).putInt("enabled_states", 1).build());
        }
        assertEquals(16, CustomBlock.of(block(cases.subList(0, CustomTraits.MAX_TRAITS))).stateCount());
        assertThrows(IllegalArgumentException.class, () -> CustomBlock.of(block(cases)));
        // Traits do not draw on the budget that collision guesses from, so a spent budget still leaves their states.
        assertEquals(16, CustomBlock.of(block(cases.subList(0, 1)), new CustomCollision.Budget(1)).stateCount());
    }

    // The runtime ID of each state is the hash of its NBT, with the client's names and types for the trait states.
    @Test
    void hashedIdsHashTheTraitStates() {
        assertHashedId(-1789715636, block(List.of(direction("cardinal_direction"))), states(CARDINAL, "north"));
        assertHashedId(-1814907916, block(List.of(direction("corner_and_cardinal_direction"))), states(CARDINAL, "east", "minecraft:corner", "outer_right"));
        assertHashedId(-1266076361, block(List.of(direction("sixteen_way_rotation"))), states("minecraft:sixteen_way_rotation", "down"));
        assertHashedId(1454206495, block(List.of(connection(1))), states("minecraft:connection_north", (byte) 1, "minecraft:connection_east", (byte) 0, "minecraft:connection_south", (byte) 0, "minecraft:connection_west", (byte) 1));
        assertHashedId(-2015630885, block(List.of(multiBlock(6))), states("minecraft:multi_block_part", 3));
        assertHashedId(1348190050, block(List.of(position("block_face", "vertical_half"))), states("minecraft:block_face", "west", "minecraft:vertical_half", "top"));
        assertHashedId(-279001703, block(List.of(direction("cardinal_direction")), property("open", NbtType.BYTE, (byte) 0, (byte) 1)), states(CARDINAL, "west", "open", (byte) 1));
    }

    private static void assertHashedId(int runtimeId, BlockPropertyData block, NbtMap states) {
        BlockMappingInfo info = BedrockPalette.mappingInfo(startGame(true, block));
        int id = info.toIntermediary().applyAsInt(runtimeId);
        assertTrue(id >= BedrockPalette.FIRST_CUSTOM_ID && id < BedrockPalette.UNKNOWN_ID, states.toString());
        SimpleBlockDefinition definition = (SimpleBlockDefinition) BedrockPalette.of(info).definition(id);
        assertEquals(runtimeId, definition.getRuntimeId());
        assertEquals(NbtMap.builder().putString("name", "cubecraft:pipe").putCompound("states", states).build(), definition.getState());
    }
}
