package ac.oomph.boarium.boar.blocks;

import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtMapBuilder;
import org.cloudburstmc.nbt.NbtType;
import org.cloudburstmc.protocol.bedrock.data.BlockPropertyData;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CustomComponentsTest {
    // A minecraft:friction or minecraft:destructible_by_mining as Geyser 2.12 (CustomBlockRegistryPopulator.convertComponents) and dragonfly write it
    private static NbtMap value(float value) {
        return NbtMap.builder().putFloat("value", value).build();
    }

    private static NbtMap components(Object... pairs) {
        NbtMapBuilder components = NbtMap.builder();
        for (int i = 0; i < pairs.length; i += 2) {
            components.put((String) pairs[i], pairs[i + 1]);
        }
        return components.build();
    }

    private static NbtMap permutation(String condition, NbtMap components) {
        return NbtMap.builder().putString("condition", condition).putCompound("components", components).build();
    }

    // A block with an open (bool) and age (int 0 to 2) property, these base components and these permutations. Its state is open + 2 * age.
    private static CustomComponents block(NbtMap components, NbtMap... permutations) {
        return customBlock(components, permutations).components();
    }

    private static CustomBlock customBlock(NbtMap components, NbtMap... permutations) {
        NbtMap open = NbtMap.builder().putString("name", "open").putList("enum", NbtType.BYTE, (byte) 0, (byte) 1).build();
        NbtMap age = NbtMap.builder().putString("name", "age").putList("enum", NbtType.INT, 0, 1, 2).build();
        NbtMap properties = NbtMap.builder()
                .putList("properties", NbtType.COMPOUND, open, age)
                .putCompound("components", components)
                .putList("permutations", NbtType.COMPOUND, permutations)
                .build();
        return CustomBlock.of(new BlockPropertyData("cubecraft:test", properties));
    }

    @Test
    void aBlockWithoutTheComponentsIsLikeABlockBoarDoesNotKnow() {
        CustomComponents components = block(NbtMap.EMPTY);
        for (int s = 0; s < 6; s++) {
            assertEquals(0.6F, components.friction(s));
            assertFalse(components.replaceable(s));
        }
        assertEquals(0, components.destroyTime());
        assertNull(components.guess());
    }

    // The client's friction is 1 minus the value sent.
    @Test
    void readsFrictionAsOneMinusTheValue() {
        assertEquals(1 - 0.02F, block(components("minecraft:friction", value(0.02F))).friction(0));
        // dragonfly sends Java's 0.6, which the client turns into 0.4.
        assertEquals(1 - 0.6F, block(components("minecraft:friction", value(0.6F))).friction(3));
        // The client reads the value only from a float tag, and takes 0 otherwise.
        assertEquals(1, block(components("minecraft:friction", NbtMap.EMPTY)).friction(0));
        assertEquals(1, block(components("minecraft:friction", NbtMap.builder().putDouble("value", 0.02).build())).friction(0));
        // A component that is not a compound is no component.
        assertEquals(0.6F, block(components("minecraft:friction", 0.02F)).friction(0));
        assertNull(block(components("minecraft:friction", NbtMap.EMPTY)).guess());
    }

    // An empty compound is enough, since the client reads nothing from it.
    @Test
    void readsReplaceableFromTheComponentBeingThere() {
        CustomComponents grass = block(components("minecraft:replaceable", NbtMap.EMPTY));
        for (int s = 0; s < 6; s++) {
            assertTrue(grass.replaceable(s));
        }
        assertFalse(block(components("minecraft:replaceable", (byte) 1)).replaceable(0));
    }

    @Test
    void readsDestructibleByMiningAsUnbreakableOnlyWhenEveryStateIsMinusOne() {
        assertEquals(-1, block(components("minecraft:destructible_by_mining", value(-1))).destroyTime());
        assertEquals(0, block(components("minecraft:destructible_by_mining", value(3))).destroyTime());
        // false is not a float, so the client reads 0 seconds.
        assertEquals(0, block(components("minecraft:destructible_by_mining", NbtMap.builder().putBoolean("value", false).build())).destroyTime());
        // Boar's block has one destroy time for every state, so a block unbreakable in only some states is breakable to Boar.
        CustomComponents some = block(NbtMap.EMPTY, permutation("q.block_state('open')", components("minecraft:destructible_by_mining", value(-1))));
        assertEquals(0, some.destroyTime());
        assertEquals("only some of its states are unbreakable, and Boar's block is breakable or not in every state", some.guess());
        // A permutation can make every state breakable again.
        CustomComponents all = block(components("minecraft:destructible_by_mining", value(-1)), permutation("q.block_state('age') == 2", components("minecraft:destructible_by_mining", value(1))));
        assertEquals(0, all.destroyTime());
    }

    // Each permutation that applies replaces the friction the state has, in list order, and adds replaceable.
    @Test
    void theLastPermutationThatAppliesWins() {
        CustomComponents components = block(components("minecraft:friction", value(0.4F)),
                permutation("q.block_state('open')", components("minecraft:friction", value(0.02F), "minecraft:replaceable", NbtMap.EMPTY)),
                permutation("q.block_state('age') == 2", components("minecraft:friction", value(0.1F))));
        assertEquals(1 - 0.4F, components.friction(0));
        assertFalse(components.replaceable(0));
        assertEquals(1 - 0.02F, components.friction(1));
        assertTrue(components.replaceable(1));
        assertEquals(1 - 0.1F, components.friction(4));
        assertEquals(1 - 0.1F, components.friction(5));
        assertTrue(components.replaceable(5));
        assertNull(components.guess());

        // A permutation cannot take replaceable away, since the client asks whether the state or the block has it.
        CustomComponents grass = block(components("minecraft:replaceable", NbtMap.EMPTY), permutation("q.block_state('open')", components("minecraft:friction", value(0.4F))));
        assertTrue(grass.replaceable(1));
    }

    // Boar cannot tell which states a condition it cannot read applies to, so each state gets the values of a block Boar does not know for the components the permutation has.
    @Test
    void anUnreadConditionGivesTheDefaults() {
        CustomComponents components = block(components("minecraft:friction", value(0.02F), "minecraft:destructible_by_mining", value(-1)),
                permutation("q.block_state('open') && math.random(0, 1) > 0.5", components("minecraft:friction", value(0.4F), "minecraft:replaceable", NbtMap.EMPTY)));
        assertEquals(0.6F, components.friction(0));
        assertEquals(0.6F, components.friction(1));
        // The permutation has no destructible_by_mining, so the block's stays.
        assertEquals(-1, components.destroyTime());
        assertFalse(components.replaceable(1));
        assertEquals("Boar cannot read the permutation condition \"q.block_state('open') && math.random(0, 1) > 0.5\"", components.guess());
    }

    // The client's speed takes friction outside 0 to 1 as it is, so Boar keeps it, and says that the client may slow the player down otherwise.
    @Test
    void keepsAFrictionOutsideZeroToOne() {
        CustomComponents sticky = block(components("minecraft:friction", value(-0.5F)));
        assertEquals(1.5F, sticky.friction(0));
        assertEquals("a minecraft:friction value is outside 0 to 1, where the client slows a player without USES_LEGACY_FRICTION more than Boar does", sticky.guess());
        assertNull(block(components("minecraft:friction", value(1))).guess());
        assertEquals(0, block(components("minecraft:friction", value(1))).friction(0));
    }

    @Test
    void aValueThatIsNotANumberGivesTheDefaults() {
        CustomComponents components = block(components("minecraft:friction", value(Float.NaN), "minecraft:destructible_by_mining", value(Float.NEGATIVE_INFINITY)));
        assertEquals(0.6F, components.friction(0));
        assertEquals(0, components.destroyTime());
        assertEquals("a minecraft:friction value is not a finite number", components.guess());
    }

    @Test
    void theComponentsHaveABudgetOfTheirOwn() {
        NbtMap properties = NbtMap.builder().putCompound("components", components("minecraft:friction", value(0.02F))).build();
        CustomBlock spent = CustomBlock.of(new BlockPropertyData("cubecraft:ice", properties), new CustomCollision.Budget(), new CustomCollision.Budget(0));
        assertEquals(0.6F, spent.components().friction(0));
        assertEquals("Boar has spent its time on the custom blocks of this StartGame", spent.components().guess());
        assertNull(spent.collision().guess());
        assertEquals(1 - 0.02F, CustomBlock.of(new BlockPropertyData("cubecraft:ice", properties), new CustomCollision.Budget(1), new CustomCollision.Budget(1)).components().friction(0));
    }

    // A block whose permutations set both its collision and its friction does not spend the collision's budget twice, so a later block keeps its boxes.
    @Test
    void theComponentsDoNotSpendTheCollisionsBudget() {
        List<Integer> values = new ArrayList<>();
        for (int i = 0; i < 1 << 16; i++) {
            values.add(i);
        }
        NbtMap n = NbtMap.builder().putString("name", "n").putList("enum", NbtType.INT, values).build();
        NbtMap both = components("minecraft:collision_box", NbtMap.builder().putBoolean("enabled", false).build(), "minecraft:friction", value(0.02F));
        NbtMap[] permutations = new NbtMap[60];
        for (int i = 0; i < permutations.length; i++) {
            permutations[i] = permutation("q.block_state('n') == " + i, both);
        }
        BlockPropertyData big = new BlockPropertyData("cubecraft:big", NbtMap.builder().putList("properties", NbtType.COMPOUND, n).putList("permutations", NbtType.COMPOUND, permutations).build());
        NbtMap box = NbtMap.builder().putFloat("minX", 0).putFloat("minY", 0).putFloat("minZ", 0).putFloat("maxX", 16).putFloat("maxY", 8).putFloat("maxZ", 16).build();
        NbtMap halfSlab = components("minecraft:collision_box", NbtMap.builder().putBoolean("enabled", true).putList("boxes", NbtType.COMPOUND, box).build());
        BlockPropertyData slab = new BlockPropertyData("cubecraft:slab", NbtMap.builder().putCompound("components", halfSlab).build());

        CustomCollision.Budget collision = new CustomCollision.Budget();
        CustomCollision.Budget components = new CustomCollision.Budget();
        CustomBlock first = CustomBlock.of(big, collision, components);
        assertNull(first.collision().guess());
        assertEquals(1 - 0.02F, first.components().friction(1));
        CustomBlock second = CustomBlock.of(slab, collision, components);
        assertNull(second.collision().guess());
        assertEquals(0.5F, second.collision().boxes(0).getFirst().maxY);
    }

    // Blocks share custom IDs only when they have the same components.
    @Test
    void componentsArePartOfTheBlock() {
        assertEquals(customBlock(components("minecraft:friction", value(0.02F))), customBlock(components("minecraft:friction", value(0.02F))));
        assertNotEquals(customBlock(components("minecraft:friction", value(0.02F))), customBlock(components("minecraft:friction", value(0.1F))));
        assertNotEquals(customBlock(NbtMap.EMPTY), customBlock(components("minecraft:replaceable", NbtMap.EMPTY)));
        // Boar only follows whether a block can be mined, so blocks that differ only in their seconds to mine share custom IDs.
        assertEquals(customBlock(components("minecraft:destructible_by_mining", value(3))), customBlock(components("minecraft:destructible_by_mining", value(5))));
        assertNotEquals(customBlock(components("minecraft:destructible_by_mining", value(3))), customBlock(components("minecraft:destructible_by_mining", value(-1))));
        // The same values from another form are the same block.
        assertEquals(customBlock(components("minecraft:replaceable", NbtMap.EMPTY)), customBlock(components("minecraft:replaceable", NbtMap.builder().putBoolean("value", true).build())));
    }
}
