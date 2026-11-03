package ac.oomph.boarium.boar.blocks;

import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtType;
import org.cloudburstmc.protocol.bedrock.data.BlockPropertyData;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

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
        // Each property takes at least 1 bit, so the 17th is past the client's 16.
        assertTimeoutPreemptively(Duration.ofMillis(500), () -> assertThrows(IllegalArgumentException.class, () -> CustomBlock.of(block)));
    }

    @Test
    void malformedBlocksAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> CustomBlock.of(block("cubecraft:broken", property("open", NbtType.BYTE, List.of()))));
        // 65537 values need 17 bits, more than the client's 16.
        List<Integer> many = new ArrayList<>();
        for (int i = 0; i <= 0x10000; i++) {
            many.add(i);
        }
        assertThrows(IllegalArgumentException.class, () -> CustomBlock.of(block("cubecraft:huge", property("n", NbtType.INT, many))));
    }

    // Stone's network hash in Geyser 2.11.3-b1248's 1.26.30 palette
    @Test
    void networkHashOfStone() {
        assertEquals(-2144268767, CustomBlock.networkHash("minecraft:stone", NbtMap.EMPTY));
    }
}
