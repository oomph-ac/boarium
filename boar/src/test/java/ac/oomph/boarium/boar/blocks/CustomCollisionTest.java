package ac.oomph.boarium.boar.blocks;

import ac.boar.anticheat.util.math.Box;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtMapBuilder;
import org.cloudburstmc.nbt.NbtType;
import org.cloudburstmc.protocol.bedrock.data.BlockPropertyData;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CustomCollisionTest {
    private static final float[] CUBE = {0, 0, 0, 1, 1, 1};

    // A minecraft:collision_box as Geyser 2.11 and 2.12 write it (CustomBlockRegistryPopulator.convertCollisionBoxes): each box's corners in 1/16 of a block
    private static NbtMap collision(float[]... boxes) {
        List<NbtMap> list = new ArrayList<>();
        for (float[] box : boxes) {
            list.add(NbtMap.builder().putFloat("minX", box[0]).putFloat("minY", box[1]).putFloat("minZ", box[2]).putFloat("maxX", box[3]).putFloat("maxY", box[4]).putFloat("maxZ", box[5]).build());
        }
        return NbtMap.builder().putBoolean("enabled", boxes.length > 0).putList("boxes", NbtType.COMPOUND, list).build();
    }

    private static NbtMap disabled() {
        return NbtMap.builder().putBoolean("enabled", false).putList("boxes", NbtType.COMPOUND).build();
    }

    private static NbtMap components(Object collision) {
        if (collision == null) {
            return NbtMap.EMPTY;
        }
        NbtMapBuilder components = NbtMap.builder();
        components.put("minecraft:collision_box", collision);
        return components.build();
    }

    private static NbtMap permutation(String condition, NbtMap components) {
        return NbtMap.builder().putString("condition", condition).putCompound("components", components).build();
    }

    // A block with an open (bool), age (int 0 to 2) and color (red or blue) property, these base components and these permutations
    private static CustomBlock block(NbtMap components, NbtMap... permutations) {
        NbtMap open = NbtMap.builder().putString("name", "open").putList("enum", NbtType.BYTE, (byte) 0, (byte) 1).build();
        NbtMap age = NbtMap.builder().putString("name", "age").putList("enum", NbtType.INT, 0, 1, 2).build();
        NbtMap color = NbtMap.builder().putString("name", "color").putList("enum", NbtType.STRING, "red", "blue").build();
        NbtMapBuilder properties = NbtMap.builder()
                .putList("properties", NbtType.COMPOUND, open, age, color)
                .putCompound("components", components)
                .putList("permutations", NbtType.COMPOUND, permutations);
        return CustomBlock.of(new BlockPropertyData("cubecraft:test", properties.build()));
    }

    // The index of the state with these values, where open changes fastest, then age, then color
    private static int state(int open, int age, String color) {
        return open + 2 * (age + 3 * (color.equals("red") ? 0 : 1));
    }

    private static float[] corners(List<Box> boxes) {
        float[] corners = new float[boxes.size() * 6];
        for (int i = 0; i < boxes.size(); i++) {
            Box box = boxes.get(i);
            System.arraycopy(new float[]{box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ}, 0, corners, i * 6, 6);
        }
        return corners;
    }

    // The boxes of one collision_box component, as every state of a block without permutations has them
    private static float[] read(Object collision) {
        CustomBlock block = block(components(collision));
        float[] corners = corners(block.collision().boxes(0));
        for (int s = 1; s < block.stateCount(); s++) {
            assertArrayEquals(corners, corners(block.collision().boxes(s)));
        }
        assertNull(block.collision().guess());
        return corners;
    }

    @Test
    void boxesAreInSixteenthsWithXMirrored() {
        // A bottom slab
        assertArrayEquals(new float[]{0, 0, 0, 1, 0.5F, 1}, read(collision(new float[]{0, 0, 0, 16, 8, 16})));
        // Geyser writes a Java box from origin [-8, 0, -8] plus 8 on X and Z, so a post in the middle reads back where it was.
        assertArrayEquals(new float[]{0.375F, 0, 0.375F, 0.625F, 1, 0.625F}, read(collision(new float[]{6, 0, 6, 10, 16, 10})));
        // The client mirrors X: a box at the low X end of the 1/16 grid is at the high X end of the block, while Y and Z stay.
        assertArrayEquals(new float[]{0.75F, 0, 0, 1, 0.25F, 0.5F}, read(collision(new float[]{0, 0, 0, 4, 4, 8})));
        // Two boxes, in the order given
        assertArrayEquals(new float[]{0, 0, 0, 1, 0.5F, 1, 0.5F, 0.5F, 0, 1, 1, 1}, read(collision(new float[]{0, 0, 0, 16, 8, 16}, new float[]{0, 8, 0, 8, 16, 16})));
    }

    @Test
    void cornersAreClampedAndSorted() {
        // X and Z stop at 16, Y at 24, and each axis is sorted.
        assertArrayEquals(new float[]{0, 0, 0, 1, 1.5F, 1}, read(collision(new float[]{-4, -4, -4, 40, 40, 40})));
        assertArrayEquals(new float[]{0, 0, 0, 1, 0.5F, 1}, read(collision(new float[]{16, 8, 16, 0, 0, 0})));
        // A box without volume is left out, so a component of only such boxes has none.
        assertArrayEquals(new float[]{0, 0, 0, 1, 1, 1}, read(collision(new float[]{0, 0, 0, 16, 0, 16}, CUBE_PIXELS)));
        assertArrayEquals(new float[0], read(collision(new float[]{0, 0, 0, 16, 0, 16})));
        // A missing or non-float corner is 0.
        NbtMap halfMissing = NbtMap.builder().putBoolean("enabled", true)
                .putList("boxes", NbtType.COMPOUND, NbtMap.builder().putFloat("maxX", 16).putFloat("maxY", 8).putFloat("maxZ", 16).build()).build();
        assertArrayEquals(new float[]{0, 0, 0, 1, 0.5F, 1}, read(halfMissing));
        NbtMap intCorners = NbtMap.builder().putBoolean("enabled", true)
                .putList("boxes", NbtType.COMPOUND, NbtMap.builder().putInt("maxX", 16).putFloat("maxY", 8).putFloat("maxZ", 16).build()).build();
        assertArrayEquals(new float[0], read(intCorners));
    }

    private static final float[] CUBE_PIXELS = {0, 0, 0, 16, 16, 16};

    @Test
    void enabledAndTheOtherForms() {
        // Disabled, or with "enabled" missing, the block has no collision.
        assertArrayEquals(new float[0], read(disabled()));
        assertArrayEquals(new float[0], read(NbtMap.builder().putList("boxes", NbtType.COMPOUND, collision(CUBE_PIXELS).getList("boxes", NbtType.COMPOUND)).build()));
        // Enabled without boxes, and the old origin and size form, which the client does not read from the network, are a full cube.
        assertArrayEquals(CUBE, read(NbtMap.builder().putBoolean("enabled", true).build()));
        assertArrayEquals(CUBE, read(NbtMap.builder().putBoolean("enabled", true).putList("origin", NbtType.FLOAT, -8F, 0F, -8F).putList("size", NbtType.FLOAT, 16F, 8F, 16F).build()));
        // A collision_box that is no compound is no component, and without one a custom block is a full cube.
        assertArrayEquals(CUBE, read((byte) 0));
        assertArrayEquals(CUBE, read(null));
    }

    @Test
    void onlyAnExactFullCubeIsSturdy() {
        CustomBlock cube = block(components(collision(CUBE_PIXELS)));
        CustomBlock slab = block(components(collision(new float[]{0, 0, 0, 16, 8, 16})));
        CustomBlock split = block(components(collision(new float[]{0, 0, 0, 16, 8, 16}, new float[]{0, 8, 0, 16, 16, 16})));
        assertTrue(cube.collision().faceSturdy(0));
        assertTrue(block(NbtMap.EMPTY).collision().faceSturdy(0));
        assertFalse(slab.collision().faceSturdy(0));
        assertFalse(split.collision().faceSturdy(0));
        assertFalse(block(components(disabled())).collision().faceSturdy(0));
        // Equal shapes are shared.
        assertSame(slab.collision().boxes(0), block(components(collision(new float[]{0, 0, 0, 16, 8, 16}))).collision().boxes(0));
    }

    @Test
    void permutationsOverrideTheBaseAndTheLastMatchWins() {
        NbtMap slab = collision(new float[]{0, 0, 0, 16, 8, 16});
        NbtMap post = collision(new float[]{6, 0, 6, 10, 16, 10});
        CustomBlock block = block(components(collision(CUBE_PIXELS)),
                permutation("q.block_state('open') == true", components(disabled())),
                permutation("q.block_state('age') == 2", components(slab)),
                // Overlaps the two above when age is 2 and color is blue, or when open and blue
                permutation("q.block_state('color') == 'blue'", components(post)),
                // No collision_box, so it changes nothing
                permutation("q.block_state('age') == 1", NbtMap.builder().putCompound("minecraft:geometry", NbtMap.EMPTY).build()));
        CustomCollision collision = block.collision();
        assertArrayEquals(CUBE, corners(collision.boxes(state(0, 0, "red"))));
        assertArrayEquals(CUBE, corners(collision.boxes(state(0, 1, "red"))));
        assertArrayEquals(new float[0], corners(collision.boxes(state(1, 0, "red"))));
        assertArrayEquals(new float[]{0, 0, 0, 1, 0.5F, 1}, corners(collision.boxes(state(0, 2, "red"))));
        assertArrayEquals(new float[]{0, 0, 0, 1, 0.5F, 1}, corners(collision.boxes(state(1, 2, "red"))));
        for (int open = 0; open <= 1; open++) {
            for (int age = 0; age <= 2; age++) {
                assertArrayEquals(new float[]{0.375F, 0, 0.375F, 0.625F, 1, 0.625F}, corners(collision.boxes(state(open, age, "blue"))));
            }
        }
        assertNull(collision.guess());
    }

    @Test
    void unknownConditionsAreFullCubes() {
        NbtMap slab = collision(new float[]{0, 0, 0, 16, 8, 16});
        CustomBlock block = block(components(disabled()),
                permutation("q.block_state('age') > 0", components(slab)),
                // A later known match decides the states it matches.
                permutation("q.block_state('color') == 'blue'", components(collision(CUBE_PIXELS))),
                // An unknown condition without collision_box changes nothing.
                permutation("q.cardinal_facing == 2", NbtMap.builder().putCompound("minecraft:geometry", NbtMap.EMPTY).build()));
        CustomCollision collision = block.collision();
        for (int s = 0; s < block.stateCount(); s++) {
            assertArrayEquals(CUBE, corners(collision.boxes(s)));
            assertTrue(collision.faceSturdy(s));
        }
        assertEquals("Boar cannot read the permutation condition \"q.block_state('age') > 0\"", collision.guess());
        // A block whose every permutation it reads has no guess.
        assertNull(block(components(disabled()), permutation("q.block_state('open')", components(slab))).collision().guess());
    }

    @Test
    void aTransformationMakesItsBoxesUnknown() {
        NbtMap transformation = NbtMap.builder().putInt("RX", 1).build();
        NbtMap slab = collision(new float[]{0, 0, 0, 16, 8, 16});
        CustomBlock block = block(NbtMap.builder().putCompound("minecraft:collision_box", slab).putCompound("minecraft:transformation", transformation).build());
        assertArrayEquals(CUBE, corners(block.collision().boxes(0)));
        assertEquals("minecraft:transformation moves its boxes", block.collision().guess());
        // From a permutation too, but a block without collision stays without.
        CustomBlock turned = block(components(slab), permutation("q.block_state('open')", NbtMap.builder().putCompound("minecraft:transformation", transformation).build()));
        assertArrayEquals(CUBE, corners(turned.collision().boxes(state(1, 0, "red"))));
        assertArrayEquals(new float[]{0, 0, 0, 1, 0.5F, 1}, corners(turned.collision().boxes(state(0, 0, "red"))));
        CustomBlock ghost = block(NbtMap.builder().putCompound("minecraft:collision_box", disabled()).putCompound("minecraft:transformation", transformation).build());
        assertArrayEquals(new float[0], corners(ghost.collision().boxes(0)));
        assertNull(ghost.collision().guess());
    }

    @Test
    void malformedInputIsAFullCubeWithAGuess() {
        float[][] many = Collections.nCopies(CustomCollision.MAX_BOXES + 1, CUBE_PIXELS).toArray(float[][]::new);
        CustomBlock tooMany = block(components(collision(many)));
        assertArrayEquals(CUBE, corners(tooMany.collision().boxes(0)));
        assertEquals("a collision_box has more than 16 boxes", tooMany.collision().guess());
        assertNull(block(components(collision(Collections.nCopies(CustomCollision.MAX_BOXES, CUBE_PIXELS).toArray(float[][]::new)))).collision().guess());
        // A permutation without a condition, or one that is no string, is unknown.
        NbtMap noCondition = NbtMap.builder().putCompound("components", components(disabled())).build();
        NbtMap intCondition = NbtMap.builder().putInt("condition", 1).putCompound("components", components(disabled())).build();
        for (NbtMap permutation : List.of(noCondition, intCondition)) {
            CustomBlock block = block(NbtMap.EMPTY, permutation);
            assertArrayEquals(CUBE, corners(block.collision().boxes(0)));
            assertEquals("Boar cannot read the permutation condition \"\"", block.collision().guess());
        }
        // Permutations that are not compounds, or components that are no compound, are skipped.
        BlockPropertyData odd = new BlockPropertyData("cubecraft:odd", NbtMap.builder()
                .putCompound("components", components(disabled()))
                .putList("permutations", NbtType.STRING, "q.block_state('open')")
                .build());
        assertArrayEquals(new float[0], corners(CustomBlock.of(odd).collision().boxes(0)));
        assertArrayEquals(new float[0], corners(block(components(disabled()), NbtMap.builder().putString("condition", "1").putString("components", "x").build()).collision().boxes(0)));
    }

    // As Geyser 2.12 sends a block of its custom mappings: string properties, molangVersion 1 and q.block_property conditions (BlockMappingsReader_v1)
    @Test
    void readsGeyserMappedBlocks() {
        NbtMap open = NbtMap.builder().putString("name", "open").putList("enum", NbtType.STRING, "false", "true").build();
        BlockPropertyData gate = new BlockPropertyData("cubecraft:gate", NbtMap.builder()
                .putList("properties", NbtType.COMPOUND, open)
                .putCompound("components", components(collision(CUBE_PIXELS)))
                .putInt("molangVersion", 1)
                .putList("permutations", NbtType.COMPOUND,
                        permutation("q.block_property('open') == 'false'", components(collision(CUBE_PIXELS))),
                        permutation("q.block_property('open') == 'true'", components(disabled())))
                .build());
        CustomCollision collision = CustomBlock.of(gate).collision();
        assertArrayEquals(CUBE, corners(collision.boxes(0)));
        assertArrayEquals(new float[0], corners(collision.boxes(1)));
        assertNull(collision.guess());
    }

    // The client takes a missing molangVersion, or one above 13, as version 0, which still reads q.block_property and groups a mix of && and || the old way.
    @Test
    void readsAMissingOrBadMolangVersionAsZero() {
        NbtMap open = NbtMap.builder().putString("name", "open").putList("enum", NbtType.STRING, "false", "true").build();
        for (Object version : new Object[]{null, 14, -1, "10"}) {
            NbtMapBuilder properties = NbtMap.builder()
                    .putList("properties", NbtType.COMPOUND, open)
                    .putCompound("components", components(collision(CUBE_PIXELS)))
                    .putList("permutations", NbtType.COMPOUND,
                            permutation("q.block_state('open') == 'false' || q.block_state('open') == 'true' && false", components(collision(CUBE_PIXELS))),
                            permutation("q.block_property('open') == 'true'", components(disabled())));
            if (version != null) {
                properties.put("molangVersion", version);
            }
            CustomCollision collision = CustomBlock.of(new BlockPropertyData("cubecraft:gate", properties.build())).collision();
            assertArrayEquals(new float[0], corners(collision.boxes(1)), String.valueOf(version));
            assertNotNull(collision.guess(), String.valueOf(version));
        }
    }

    @Test
    void collisionIsPartOfTheBlock() {
        NbtMap slab = collision(new float[]{0, 0, 0, 16, 8, 16});
        assertEquals(block(components(slab)), block(components(collision(new float[]{0, 0, 0, 16, 8, 16}))));
        assertNotEquals(block(components(slab)), block(components(disabled())));
        // The same boxes from another form are the same block.
        assertEquals(block(NbtMap.EMPTY), block(components(NbtMap.builder().putBoolean("enabled", true).build())));
    }

    // One StartGame may add only MAX_NEW_SHAPES shapes to those that all custom blocks share, and shapes Boar already keeps cost none of them.
    @Test
    void oneStartGameAddsABoundedNumberOfShapes() {
        CustomCollision.Budget budget = new CustomCollision.Budget();
        float[] known = {0, 0, 0, 16, 8, 16};
        CustomBlock.of(new BlockPropertyData("cubecraft:known", NbtMap.builder().putCompound("components", components(collision(known))).build()), new CustomCollision.Budget());
        String guess = null;
        int added = 0;
        // Heights in steps of 1/1024 of a sixteenth, so no other test has made these shapes
        for (int i = 1; guess == null; i++) {
            float[] box = {0, 0, 0, 16, 16 - i / 1024F, 16};
            guess = CustomBlock.of(new BlockPropertyData("cubecraft:shape_" + i, NbtMap.builder().putCompound("components", components(collision(box))).build()), budget).collision().guess();
            added = guess == null ? i : added;
        }
        assertEquals(CustomCollision.MAX_NEW_SHAPES, added);
        assertEquals("Boar keeps no more than " + CustomCollision.MAX_NEW_SHAPES + " new custom block shapes from one StartGame", guess);
        assertNull(CustomBlock.of(new BlockPropertyData("cubecraft:again", NbtMap.builder().putCompound("components", components(collision(known))).build()), budget).collision().guess());
        // The next StartGame has its own share.
        assertNull(CustomBlock.of(new BlockPropertyData("cubecraft:next", NbtMap.builder().putCompound("components", components(collision(new float[]{0, 0, 0, 16, 1 / 4096F, 16}))).build()), new CustomCollision.Budget()).collision().guess());
    }

    @Test
    void theWorkOfOneStartGameIsBounded() {
        // 2^16 states and 1024 permutations of the longest conditions: past its budget, a block is a full cube.
        List<Integer> values = new ArrayList<>();
        for (int i = 0; i < 1 << 16; i++) {
            values.add(i);
        }
        String longest = "q.block_state('n') == 1 || ".repeat(37) + "q.block_state('n') == 1";
        assertTrue(longest.length() <= BlockCondition.MAX_LENGTH);
        NbtMap[] permutations = new NbtMap[CustomCollision.MAX_PERMUTATIONS];
        for (int i = 0; i < permutations.length; i++) {
            permutations[i] = permutation(longest, components(disabled()));
        }
        NbtMap n = NbtMap.builder().putString("name", "n").putList("enum", NbtType.INT, values).build();
        BlockPropertyData hostile = new BlockPropertyData("cubecraft:hostile", NbtMap.builder()
                .putList("properties", NbtType.COMPOUND, n)
                .putList("permutations", NbtType.COMPOUND, permutations)
                .build());
        CustomCollision.Budget budget = new CustomCollision.Budget();
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
            CustomBlock block = CustomBlock.of(hostile, budget);
            assertArrayEquals(CUBE, corners(block.collision().boxes(1)));
            assertEquals("Boar has spent its time on the custom blocks of this StartGame", block.collision().guess());
            // The budget is spent for the rest of the StartGame.
            assertEquals("Boar has spent its time on the custom blocks of this StartGame", CustomBlock.of(new BlockPropertyData("cubecraft:after", NbtMap.EMPTY), budget).collision().guess());
        });
        // One more permutation than the cap is a guess too.
        NbtMap[] over = Collections.nCopies(CustomCollision.MAX_PERMUTATIONS + 1, permutation("1", components(disabled()))).toArray(NbtMap[]::new);
        assertEquals("more than 1024 of its permutations set its collision", block(NbtMap.EMPTY, over).collision().guess());
    }
}
