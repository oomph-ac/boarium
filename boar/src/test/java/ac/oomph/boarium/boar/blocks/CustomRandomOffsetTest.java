package ac.oomph.boarium.boar.blocks;

import ac.boar.anticheat.collision.BambooCollision;
import ac.boar.anticheat.collision.BlockRandomOffset;
import ac.boar.anticheat.util.math.Box;
import ac.oomph.boarium.boar.blocks.CustomComponents.RandomOffset;
import org.cloudburstmc.math.vector.Vector3i;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtMapBuilder;
import org.cloudburstmc.nbt.NbtType;
import org.cloudburstmc.protocol.bedrock.data.BlockPropertyData;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

// The expected offsets were computed by a line-by-line copy of the disassembly of BlockRandomOffsetComponent::getRandomOffset (0x10a430474) in the 1.26.30 macOS arm64 client, which seeds from 32-bit X like Android.
class CustomRandomOffsetTest {
    private static final BlockRandomOffset.Axis BAMBOO = new BlockRandomOffset.Axis(-0.25F, 0.25F, 16);
    private static final float[] CUBE = {0, 0, 0, 1, 1, 1};

    // One axis of a minecraft:random_offset as BlockRandomOffsetDescription's schema reads it, with min and max in 1/16 of a block
    private static NbtMap axis(float min, float max, int steps) {
        return NbtMap.builder().putCompound("range", NbtMap.builder().putFloat("min", min).putFloat("max", max).build()).putInt("steps", steps).build();
    }

    private static NbtMap offset(NbtMap x, NbtMap y, NbtMap z) {
        return NbtMap.builder().putCompound("x", x).putCompound("y", y).putCompound("z", z).build();
    }

    // Bamboo's offset as a custom block sends it: -4 to 4 sixteenths in 16 steps on X and Z
    private static NbtMap stalk() {
        return offset(axis(-4, 4, 16), axis(0, 0, 0), axis(-4, 4, 16));
    }

    private static NbtMap components(Object... pairs) {
        NbtMapBuilder components = NbtMap.builder();
        for (int i = 0; i < pairs.length; i += 2) {
            components.put((String) pairs[i], pairs[i + 1]);
        }
        return components.build();
    }

    private static NbtMap cubeBox() {
        NbtMap box = NbtMap.builder().putFloat("minX", 0).putFloat("minY", 0).putFloat("minZ", 0).putFloat("maxX", 16).putFloat("maxY", 16).putFloat("maxZ", 16).build();
        return NbtMap.builder().putBoolean("enabled", true).putList("boxes", NbtType.COMPOUND, box).build();
    }

    private static NbtMap permutation(String condition, NbtMap components) {
        return NbtMap.builder().putString("condition", condition).putCompound("components", components).build();
    }

    // A block with an open (bool) property, these base components and these permutations. Its state is open.
    private static CustomBlock block(NbtMap components, NbtMap... permutations) {
        NbtMap open = NbtMap.builder().putString("name", "open").putList("enum", NbtType.BYTE, (byte) 0, (byte) 1).build();
        return CustomBlock.of(new BlockPropertyData("cubecraft:stalk", NbtMap.builder()
                .putList("properties", NbtType.COMPOUND, open)
                .putCompound("components", components)
                .putList("permutations", NbtType.COMPOUND, permutations)
                .build()));
    }

    private static float[] corners(List<Box> boxes) {
        float[] corners = new float[boxes.size() * 6];
        for (int i = 0; i < boxes.size(); i++) {
            Box box = boxes.get(i);
            System.arraycopy(new float[]{box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ}, 0, corners, i * 6, 6);
        }
        return corners;
    }

    @Test
    void boarsOffsetIsTheClients() {
        int[][] positions = {{0, 0}, {1, 0}, {0, 1}, {-1, -1}, {123, -456}, {-30000000, 29999999}, {700, 5}, {-687, 12}};
        float[][] bamboo = {{-0.18333333730697632F, -0.11666665971279144F}, {0.25F, -0.016666650772094727F}, {0.21666669845581055F, 0.050000011920928955F}, {-0.0833333283662796F, -0.18333333730697632F},
                {0.11666667461395264F, 0.25F}, {-0.25F, 0.21666669845581055F}, {-0.14999999105930328F, 0.050000011920928955F}, {-0.14999999105930328F, -0.25F}};
        for (int i = 0; i < positions.length; i++) {
            float[] offset = BlockRandomOffset.get(positions[i][0], positions[i][1], false, BAMBOO, BlockRandomOffset.Axis.NONE, BAMBOO);
            assertArrayEquals(new float[]{bamboo[i][0], 0, bamboo[i][1]}, offset, "x " + positions[i][0] + " z " + positions[i][1]);
        }
        // Any value in the range when steps is 0, the middle when it is 1, and the second of three steps on Z here
        BlockRandomOffset.Axis any = new BlockRandomOffset.Axis(-0.5F, 0.5F, 0);
        BlockRandomOffset.Axis middle = new BlockRandomOffset.Axis(-0.25F, 0.75F, 1);
        BlockRandomOffset.Axis three = new BlockRandomOffset.Axis(0, 1, 3);
        assertArrayEquals(new float[]{-0.33525633811950684F, 0.25F, 0}, BlockRandomOffset.get(0, 0, false, any, middle, three));
        assertArrayEquals(new float[]{0.17874735593795776F, 0.25F, 0.5F}, BlockRandomOffset.get(5, -3, false, any, middle, three));
        assertArrayEquals(new float[]{0.1669936180114746F, 0.25F, 0.5F}, BlockRandomOffset.get(-1000, 1000, false, any, middle, three));
        // A range whose min is not below its max gives its min.
        assertArrayEquals(new float[]{-0.25F, 0.5F, 0}, BlockRandomOffset.get(3, 4, false, new BlockRandomOffset.Axis(-0.25F, -0.25F, 4), new BlockRandomOffset.Axis(0.5F, 0.5F, 0), BlockRandomOffset.Axis.NONE));
    }

    // Vanilla bamboo moves by the same function, unrounded (BambooStalkBlock::getCollisionShape).
    @Test
    void vanillaBambooMovesByTheSameOffset() {
        Box box = BambooCollision.getCollisionBox(123, 7, -456, false, false);
        assertEquals(0.5F + (123 + 0.11666667461395264F), box.minX);
        assertEquals(0.5F + (-456 + 0.25F), box.minZ);
        assertEquals(7, box.minY);
        box = BambooCollision.getCollisionBox(-1, 0, -1, false, true);
        assertEquals(0.5F + (-1 - 0.0833333283662796F), box.minX);
        assertEquals(0.5F + (-1 - 0.18333333730697632F), box.minZ);
    }

    // A custom block with collision_box moves by whole blocks: the offset rounded down.
    @Test
    void aCustomBlockMovesByWholeBlocks() {
        CustomBlock block = block(components("minecraft:collision_box", cubeBox(), "minecraft:random_offset", stalk()));
        assertNull(block.collision().guess());
        RandomOffset offset = block.collision().offset(1);
        assertEquals(new RandomOffset(BAMBOO, new BlockRandomOffset.Axis(0, 0, 0), BAMBOO, null), offset);
        assertEquals(Vector3i.from(-1, 0, -1), offset.blocks(0, 0, false));
        assertEquals(Vector3i.from(0, 0, -1), offset.blocks(1, 0, false));
        assertEquals(Vector3i.from(0, 0, 0), offset.blocks(0, 1, false));
        assertEquals(Vector3i.from(0, 0, 0), offset.blocks(123, -456, false));
        // The boxes themselves stay where the collision_box puts them.
        assertArrayEquals(CUBE, corners(block.collision().boxes(1)));
        assertTrue(block.collision().faceSturdy(1));
        // Y moves too: 0.25 is still in its block, -0.0625 is in the one below.
        RandomOffset upAndDown = block(components("minecraft:collision_box", cubeBox(), "minecraft:random_offset", offset(axis(0, 0, 0), axis(-1, -1, 0), axis(4, 4, 0)))).collision().offset(0);
        assertEquals(Vector3i.from(0, -1, 0), upAndDown.blocks(9, 9, false));
    }

    // The last permutation that matches sets a state's offset, and replaces the block's.
    @Test
    void permutationsSetTheOffsetOfTheirStates() {
        NbtMap far = offset(axis(-16, -16, 0), axis(0, 0, 0), axis(0, 0, 0));
        NbtMap farther = offset(axis(-32, -32, 0), axis(0, 0, 0), axis(0, 0, 0));
        CustomBlock block = block(components("minecraft:collision_box", cubeBox(), "minecraft:random_offset", stalk()),
                permutation("q.block_state('open')", components("minecraft:random_offset", far)),
                permutation("q.block_state('open') == true", components("minecraft:random_offset", farther)));
        assertEquals(Vector3i.from(-1, 0, -1), block.collision().offset(0).blocks(0, 0, false));
        assertEquals(Vector3i.from(-2, 0, 0), block.collision().offset(1).blocks(0, 0, false));
        assertFalse(block.collision().sameCollision(0, 1));
        assertNull(block.collision().guess());

        CustomBlock onlyOpen = block(components("minecraft:collision_box", cubeBox()), permutation("q.block_state('open')", components("minecraft:random_offset", far)));
        assertNull(onlyOpen.collision().offset(0));
        assertEquals(Vector3i.from(-1, 0, 0), onlyOpen.collision().offset(1).blocks(5, 5, false));
        // A component that is not a compound is no component.
        assertNull(block(components("minecraft:collision_box", cubeBox(), "minecraft:random_offset", 1)).collision().offset(0));
        // Two blocks that differ only in their offset are not the same block.
        assertNotEquals(block(components("minecraft:collision_box", cubeBox())), onlyOpen);
        assertNotEquals(block(components("minecraft:collision_box", cubeBox(), "minecraft:random_offset", stalk())).collision(), block(components("minecraft:collision_box", cubeBox(), "minecraft:random_offset", far)).collision());
    }

    // A state with no boxes has nothing to move.
    @Test
    void aBlockWithoutBoxesHasNoOffset() {
        NbtMap disabled = NbtMap.builder().putBoolean("enabled", false).putList("boxes", NbtType.COMPOUND).build();
        CustomBlock block = block(components("minecraft:collision_box", disabled, "minecraft:random_offset", stalk()));
        assertNull(block.collision().offset(0));
        assertNull(block.collision().guess());
    }

    // An offset Boar cannot read leaves the states it applies to a full cube that does not move, with a guess.
    @Test
    void anOffsetBoarCannotReadIsAGuess() {
        String unread = "a minecraft:random_offset is not x, y and z, each a float range and int steps";
        String far = "a minecraft:random_offset reaches past 1024 blocks";
        Object[] bad = {
                NbtMap.builder().putCompound("x", axis(-4, 4, 16)).putCompound("z", axis(-4, 4, 16)).build(), unread,
                offset(axis(-4, 4, 16), NbtMap.builder().putCompound("range", NbtMap.builder().putFloat("min", 0).putFloat("max", 0).build()).build(), axis(0, 0, 0)), unread,
                offset(axis(-4, 4, 16), axis(0, 0, 0), NbtMap.builder().putCompound("range", NbtMap.builder().putDouble("min", 0).putFloat("max", 1).build()).putInt("steps", 0).build()), unread,
                offset(axis(-4, 4, 16), axis(0, 0, 0), NbtMap.builder().putCompound("range", NbtMap.builder().putFloat("min", 0).putFloat("max", 1).build()).putByte("steps", (byte) 0).build()), unread,
                offset(axis(-4, 4, -1), axis(0, 0, 0), axis(0, 0, 0)), "a minecraft:random_offset has steps of 2^31 or more",
                offset(axis(0, Float.POSITIVE_INFINITY, 16), axis(0, 0, 0), axis(0, 0, 0)), far,
                offset(axis(0, 0, 0), axis(-16 * 1024 - 1, 0, 0), axis(0, 0, 0)), far,
                offset(axis(0, Float.NaN, 0), axis(0, 0, 0), axis(0, 0, 0)), "a minecraft:random_offset has a range whose max is NaN",
        };
        for (int i = 0; i < bad.length; i += 2) {
            CustomBlock block = block(components("minecraft:collision_box", cubeBox(), "minecraft:random_offset", bad[i]));
            assertSame(CustomCollision.FULL_CUBE, block.collision().boxes(0), bad[i].toString());
            assertNull(block.collision().offset(0));
            assertEquals(bad[i + 1], block.collision().guess());
        }
        // Each state keeps the reason of its own offset.
        CustomBlock twoReasons = block(components("minecraft:collision_box", cubeBox(), "minecraft:random_offset", bad[0]),
                permutation("q.block_state('open')", components("minecraft:random_offset", bad[8])));
        assertEquals(unread, twoReasons.components().offset(0).unread());
        assertEquals("a minecraft:random_offset has steps of 2^31 or more", twoReasons.components().offset(1).unread());
        // Only the states of the permutation are a guess.
        NbtMap slab = NbtMap.builder().putBoolean("enabled", true).putList("boxes", NbtType.COMPOUND,
                NbtMap.builder().putFloat("minX", 0).putFloat("minY", 0).putFloat("minZ", 0).putFloat("maxX", 16).putFloat("maxY", 8).putFloat("maxZ", 16).build()).build();
        CustomBlock some = block(components("minecraft:collision_box", slab), permutation("q.block_state('open')", components("minecraft:random_offset", (NbtMap) bad[0])));
        assertArrayEquals(new float[]{0, 0, 0, 1, 0.5F, 1}, corners(some.collision().boxes(0)));
        assertSame(CustomCollision.FULL_CUBE, some.collision().boxes(1));
        assertEquals(unread, some.collision().guess());
        // So are the states of a permutation whose condition Boar cannot read.
        CustomBlock unreadCondition = block(components("minecraft:collision_box", slab), permutation("q.cardinal_facing == 2", components("minecraft:random_offset", stalk())));
        assertSame(CustomCollision.FULL_CUBE, unreadCondition.collision().boxes(0));
        assertEquals("Boar cannot read the permutation condition \"q.cardinal_facing == 2\"", unreadCondition.collision().guess());
        assertNull(unreadCondition.components().guess());
    }

    // Where the client and BlockRandomOffset agree on an odd range, Boar follows it: a min above max gives min, and a NaN min gives NaN, which rounds to 0 on the arm64 client and in Java.
    @Test
    void oddRangesBothSidesAgreeOnAreRead() {
        CustomBlock block = block(components("minecraft:collision_box", cubeBox(), "minecraft:random_offset", offset(axis(-8, -16, 16), axis(Float.NaN, 4, 2), axis(Float.NaN, Float.NaN, 0))));
        assertNull(block.collision().guess());
        assertArrayEquals(new float[]{-0.5F, Float.NaN, Float.NaN}, BlockRandomOffset.get(3, 4, false, block.collision().offset(0).x(), block.collision().offset(0).y(), block.collision().offset(0).z()));
        assertEquals(Vector3i.from(-1, 0, 0), block.collision().offset(0).blocks(3, 4, false));
        // 1024 blocks is still read.
        assertEquals(Vector3i.from(0, 1024, 0), block(components("minecraft:collision_box", cubeBox(), "minecraft:random_offset", offset(axis(0, 0, 0), axis(16 * 1024, 16 * 1024, 0), axis(0, 0, 0)))).collision().offset(0).blocks(0, 0, false));
    }

    // Without a collision_box, the client moves the block's shape by the offset unrounded, which Boar does not follow.
    @Test
    void anOffsetWithoutACollisionBoxIsAGuess() {
        CustomBlock block = block(components("minecraft:random_offset", stalk()));
        assertSame(CustomCollision.FULL_CUBE, block.collision().boxes(0));
        assertNull(block.collision().offset(0));
        assertEquals("it has a minecraft:random_offset without a minecraft:collision_box", block.collision().guess());
    }

    // Reading the offsets takes from the components' budget. Past it, a block with an offset does not know its collision, and one without still does.
    @Test
    void offsetsAreBoundedByTheComponentsBudget() {
        BlockPropertyData moving = new BlockPropertyData("cubecraft:stalk", NbtMap.builder().putCompound("components", components("minecraft:collision_box", cubeBox(), "minecraft:random_offset", stalk())).build());
        CustomBlock spent = CustomBlock.of(moving, new CustomCollision.Budget(), new CustomCollision.Budget(0));
        assertSame(CustomCollision.FULL_CUBE, spent.collision().boxes(0));
        assertNull(spent.collision().offset(0));
        assertEquals(CustomCollision.SPENT, spent.collision().guess());
        BlockPropertyData still = new BlockPropertyData("cubecraft:still", NbtMap.builder().putCompound("components", components("minecraft:collision_box", cubeBox())).build());
        assertNull(CustomBlock.of(still, new CustomCollision.Budget(), new CustomCollision.Budget(0)).collision().guess());
        assertNull(CustomBlock.of(moving, new CustomCollision.Budget(), new CustomCollision.Budget()).collision().guess());
    }
}
