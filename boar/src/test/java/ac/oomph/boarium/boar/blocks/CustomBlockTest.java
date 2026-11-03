package ac.oomph.boarium.boar.blocks;

import org.cloudburstmc.nbt.NBTOutputStream;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtType;
import org.cloudburstmc.nbt.NbtUtils;
import org.cloudburstmc.protocol.bedrock.data.BlockPropertyData;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CustomBlockTest {
    private static BlockPropertyData block(String name, NbtMap... properties) {
        return new BlockPropertyData(name, NbtMap.builder().putList("properties", NbtType.COMPOUND, properties).putCompound("components", NbtMap.EMPTY).build());
    }

    private static <T> NbtMap property(String name, NbtType<T> type, List<T> values) {
        return NbtMap.builder().putString("name", name).putList("enum", type, values).build();
    }

    @Test
    void statesChangeTheFirstPropertyFastest() {
        CustomBlock pipe = CustomBlock.of(block("cubecraft:pipe", property("facing", NbtType.STRING, List.of("north", "east", "south", "west")), property("lit", NbtType.BYTE, List.of((byte) 0, (byte) 1))));
        List<NbtMap> states = new ArrayList<>();
        for (int i = 0; i < pipe.stateCount(); i++) {
            states.add(pipe.state(i));
        }
        List<NbtMap> expected = new ArrayList<>();
        for (byte lit = 0; lit <= 1; lit++) {
            for (String facing : List.of("north", "east", "south", "west")) {
                expected.add(NbtMap.builder().putString("facing", facing).putByte("lit", lit).build());
            }
        }
        assertEquals(expected, states);
        // A block without properties has one state.
        CustomBlock plain = CustomBlock.of(new BlockPropertyData("cubecraft:plain", NbtMap.EMPTY));
        assertEquals(1, plain.stateCount());
        assertEquals(NbtMap.EMPTY, plain.state(0));
    }

    @Test
    void manyPropertiesAreRejectedQuickly() {
        NbtMap[] properties = new NbtMap[60_000];
        for (int i = 0; i < properties.length; i++) {
            properties[i] = property("p" + i, NbtType.BYTE, List.of((byte) 0));
        }
        BlockPropertyData block = block("cubecraft:wide", properties);
        // Each property takes at least 1 bit, so the 33rd is past the 32 that Boar follows.
        assertTimeoutPreemptively(Duration.ofMillis(500), () -> assertThrows(IllegalArgumentException.class, () -> CustomBlock.of(block)));
    }

    // The client reads the first value of a property without checking that there is one.
    @Test
    void aPropertyWithoutValuesIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> CustomBlock.of(block("cubecraft:broken", property("open", NbtType.BYTE, List.of()))));
    }

    private static List<Integer> ints(int count) {
        List<Integer> values = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            values.add(i);
        }
        return values;
    }

    // The client makes a state for each value of the lowest 16 bits where the properties within them are in range.
    @Test
    void propertiesPast16BitsTakeTheValuesOfTheBitsBelow16() {
        // 65537 values need 17 bits: 65536 states, each with the first value.
        CustomBlock huge = CustomBlock.of(block("cubecraft:huge", property("n", NbtType.INT, ints(0x10001))));
        assertEquals(0x10000, huge.stateCount());
        assertEquals(NbtMap.builder().putInt("n", 0).build(), huge.state(0xFFFF));
        // 3 values take bits 0 and 1, and 65536 values bits 2 to 17, of which bits 2 to 15 count: 3 * 2^14 states.
        CustomBlock crossing = CustomBlock.of(block("cubecraft:crossing", property("a", NbtType.INT, ints(3)), property("b", NbtType.INT, ints(0x10000)), property("c", NbtType.INT, ints(2))));
        assertEquals(3 << 14, crossing.stateCount());
        assertEquals(NbtMap.builder().putInt("a", 2).putInt("b", (1 << 14) - 1).putInt("c", 0).build(), crossing.state((3 << 14) - 1));
        assertEquals(NbtMap.builder().putInt("a", 1).putInt("b", 1).putInt("c", 0).build(), crossing.state(4));
    }

    @Test
    void readsPropertiesAsTheClientDoes() {
        // Properties that are not a list of compounds are none.
        assertEquals(1, CustomBlock.of(new BlockPropertyData("cubecraft:a", NbtMap.builder().putCompound("properties", NbtMap.EMPTY).build())).stateCount());
        assertEquals(1, CustomBlock.of(new BlockPropertyData("cubecraft:b", NbtMap.builder().putList("properties", NbtType.STRING, "x").build())).stateCount());
        // A property without an "enum" list is skipped, one without a string name is named "", and values of any type count, twice if listed twice.
        NbtMap noEnum = NbtMap.builder().putString("name", "skipped").putString("enum", "x").build();
        NbtMap noName = NbtMap.builder().putList("enum", NbtType.FLOAT, 0.5F, 1.5F).build();
        CustomBlock block = CustomBlock.of(block("cubecraft:c", noEnum, noName, property("v", NbtType.STRING, List.of("a", "a"))));
        assertEquals(List.of("", "v"), block.keys());
        assertEquals(4, block.stateCount());
        assertEquals(NbtMap.builder().putFloat("", 1.5F).putString("v", "a").build(), block.state(3));
        // Of two properties with the same name, a state shows the last.
        CustomBlock twice = CustomBlock.of(block("cubecraft:d", property("f", NbtType.STRING, List.of("n", "e")), property("f", NbtType.STRING, List.of("s", "w"))));
        assertEquals(4, twice.stateCount());
        assertEquals(NbtMap.builder().putString("f", "s").build(), twice.state(1));
        assertEquals(NbtMap.builder().putString("f", "w").build(), twice.state(2));
    }

    // The client sorts keys by their UTF-8 bytes, where U+E000 comes before an emoji; Java's String order puts the emoji first.
    @Test
    void networkHashSortsKeysByTheirUtf8Bytes() throws IOException {
        String privateUse = "\uE000";
        String emoji = "\uD83D\uDE00";
        assertTrue(emoji.compareTo(privateUse) < 0);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (NBTOutputStream out = NbtUtils.createWriterLE(bytes)) {
            out.writeTag(NbtMap.builder().putString("name", "cubecraft:keys").putCompound("states", NbtMap.builder().putByte(privateUse, (byte) 0).putByte(emoji, (byte) 1).build()).build());
        }
        NbtMap states = NbtMap.builder().putByte(emoji, (byte) 1).putByte(privateUse, (byte) 0).build();
        assertEquals(CustomBlock.fnv1a(bytes.toByteArray()), CustomBlock.networkHash("cubecraft:keys", states));
    }

    // Stone's network hash in Geyser 2.11.3-b1248's 1.26.30 palette
    @Test
    void networkHashOfStone() {
        assertEquals(-2144268767, CustomBlock.networkHash("minecraft:stone", NbtMap.EMPTY));
    }
}
