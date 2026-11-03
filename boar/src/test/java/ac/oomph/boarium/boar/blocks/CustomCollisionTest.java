package ac.oomph.boarium.boar.blocks;

import ac.boar.anticheat.util.math.Box;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtMapBuilder;
import org.cloudburstmc.nbt.NbtType;
import org.cloudburstmc.protocol.bedrock.data.BlockPropertyData;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
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

    // A minecraft:transformation as Geyser 2.12 writes it (CustomBlockRegistryPopulator): quarter turns as ints, scale and translation as floats, and no pivots
    private static NbtMap geyser(int rx, int ry, int rz, float sx, float sy, float sz, float tx, float ty, float tz) {
        return NbtMap.builder().putInt("RX", rx).putInt("RY", ry).putInt("RZ", rz).putFloat("SX", sx).putFloat("SY", sy).putFloat("SZ", sz).putFloat("TX", tx).putFloat("TY", ty).putFloat("TZ", tz).build();
    }

    private static NbtMap turn(int rx, int ry, int rz) {
        return geyser(rx, ry, rz, 1, 1, 1, 0, 0, 0);
    }

    private static NbtMap components(NbtMap collision, NbtMap transformation) {
        NbtMapBuilder components = NbtMap.builder();
        if (collision != null) {
            components.putCompound("minecraft:collision_box", collision);
        }
        components.putCompound("minecraft:transformation", transformation);
        return components.build();
    }

    // The boxes of a block with this collision_box and transformation, as every state has them
    private static float[] moved(NbtMap collision, NbtMap transformation) {
        return readComponents(components(collision, transformation));
    }

    private static float[] readComponents(NbtMap components) {
        CustomBlock block = block(components);
        float[] corners = corners(block.collision().boxes(0));
        for (int s = 1; s < block.stateCount(); s++) {
            assertArrayEquals(corners, corners(block.collision().boxes(s)));
        }
        assertNull(block.collision().guess());
        return corners;
    }

    /**
     * The box, in blocks, that the client's formula gives, worked out here from the 8 moved corners with exact quarter turns: each corner p becomes R(S(p - c - sp) + sp - rp) + rp + t + c, where c is the block's centre (0.5, 0.5, 0.5).
     * R turns a point about X first, then Y, then Z, each quarter turn right-handed: about X, y goes to z and z to -y; about Y, z goes to x and x to -z; about Z, x goes to y and y to -x.
     *
     * @param pivots the rotation pivot, then the scale pivot
     */
    private static float[] expected(float[] box, int[] turns, float[] scale, float[] translation, float[]... pivots) {
        float[] rp = pivots.length > 0 ? pivots[0] : new float[3];
        float[] sp = pivots.length > 1 ? pivots[1] : new float[3];
        double[] low = {Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE};
        double[] high = {-Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};
        for (int corner = 0; corner < 8; corner++) {
            double[] p = new double[3];
            for (int axis = 0; axis < 3; axis++) {
                double at = (corner >> axis & 1) == 0 ? box[axis] : box[3 + axis];
                p[axis] = scale[axis] * (at - 0.5 - sp[axis]) + sp[axis] - rp[axis];
            }
            for (int i = 0; i < Math.max(0, turns[0]) % 4; i++) {
                p = new double[]{p[0], -p[2], p[1]};
            }
            for (int i = 0; i < Math.max(0, turns[1]) % 4; i++) {
                p = new double[]{p[2], p[1], -p[0]};
            }
            for (int i = 0; i < Math.max(0, turns[2]) % 4; i++) {
                p = new double[]{-p[1], p[0], p[2]};
            }
            for (int axis = 0; axis < 3; axis++) {
                double at = p[axis] + rp[axis] + translation[axis] + 0.5;
                low[axis] = Math.min(low[axis], at);
                high[axis] = Math.max(high[axis], at);
            }
        }
        return new float[]{(float) low[0], (float) low[1], (float) low[2], (float) high[0], (float) high[1], (float) high[2]};
    }

    // The client's quarter turns go through a quaternion, so they are off by a few float steps.
    private static void assertNear(float[] expected, float[] actual) {
        assertEquals(expected.length, actual.length);
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i], actual[i], 1e-6F, Arrays.toString(actual));
        }
    }

    // An off-centre box: in 1/16 it is 0 to 4 on X, 0 to 8 on Y and 0 to 12 on Z, and the client mirrors X.
    private static final float[] OFF_CENTRE_PIXELS = {0, 0, 0, 4, 8, 12};
    private static final float[] OFF_CENTRE = {0.75F, 0, 0, 1, 0.5F, 0.75F};
    private static final float[] ONE = {1, 1, 1};
    private static final float[] ZERO = {0, 0, 0};

    @Test
    void aTranslationMovesTheBoxesWithoutClamping() {
        NbtMap slab = collision(new float[]{0, 0, 0, 16, 8, 16});
        assertArrayEquals(new float[]{0.25F, 0.5F, -0.125F, 1.25F, 1, 0.875F}, moved(slab, geyser(0, 0, 0, 1, 1, 1, 0.25F, 0.5F, -0.125F)));
        // The 1/16 corners are still clamped first, to 24 on Y, and the moved box is not clamped again.
        assertArrayEquals(new float[]{0, 1, 0, 1, 2.5F, 1}, moved(collision(new float[]{0, 0, 0, 16, 40, 16}), geyser(0, 0, 0, 1, 1, 1, 0, 1, 0)));
    }

    @Test
    void aScaleWorksAboutTheBlockCentreAndItsPivot() {
        NbtMap slab = collision(new float[]{0, 0, 0, 16, 8, 16});
        assertArrayEquals(new float[]{0.25F, 0, -0.5F, 0.75F, 0.5F, 1.5F}, moved(slab, geyser(0, 0, 0, 0.5F, 1, 2, 0, 0, 0)));
        NbtMap pivoted = geyser(0, 0, 0, 0.5F, 0.5F, 0.5F, 0, 0, 0).toBuilder().putFloat("SXP", 0.5F).putFloat("SYP", -0.5F).putFloat("SZP", 0.25F).build();
        assertArrayEquals(expected(new float[]{0, 0, 0, 1, 0.5F, 1}, new int[3], new float[]{0.5F, 0.5F, 0.5F}, ZERO, ZERO, new float[]{0.5F, -0.5F, 0.25F}), moved(slab, pivoted));
        // By hand: the scale pivot is 0.5 east of the centre, at X 1, so X runs from 1 + 0.5 * (0 - 1) = 0.5 to 1 + 0.5 * (1 - 1) = 1.
        assertArrayEquals(new float[]{0.5F, 0, 0.375F, 1, 0.25F, 0.875F}, moved(slab, pivoted));
    }

    @Test
    void eachQuarterTurnOfEachAxis() {
        NbtMap box = collision(OFF_CENTRE_PIXELS);
        for (int axis = 0; axis < 3; axis++) {
            for (int count = 1; count <= 5; count++) {
                int[] turns = new int[3];
                turns[axis] = count;
                assertNear(expected(OFF_CENTRE, turns, ONE, ZERO), moved(box, turn(turns[0], turns[1], turns[2])));
            }
        }
        // By hand, one turn about X: from the centre, Y -0.5 to 0 and Z -0.5 to 0.25 become Z -0.5 to 0 and Y -0.25 to 0.5.
        assertNear(new float[]{0.75F, 0.25F, 0, 1, 1, 0.5F}, moved(box, turn(1, 0, 0)));
        // Turns about every axis, where the order matters: a point turns about X first, then Y, then Z.
        int[] turns = {1, 2, 3};
        assertNear(expected(OFF_CENTRE, turns, ONE, ZERO), moved(box, turn(1, 2, 3)));
    }

    // Worked by hand through the client's float steps: a quarter turn about Z holds 5.96e-8 and 0.99999994 where 0 and 1 belong, and still gives these exact corners.
    @Test
    void aSlabTurnedAboutZIsAnUprightHalfBlock() {
        NbtMap slab = collision(new float[]{0, 0, 0, 16, 8, 16});
        CustomBlock block = block(components(slab, turn(0, 0, 1)));
        assertArrayEquals(new float[]{0.5F, 0, 0, 1, 1, 1}, corners(block.collision().boxes(0)));
        assertFalse(block.collision().faceSturdy(0));
        // A full cube stays an exact full cube, so it is sturdy.
        CustomBlock cube = block(components(collision(CUBE_PIXELS), turn(0, 1, 0)));
        assertSame(CustomCollision.FULL_CUBE, cube.collision().boxes(0));
        assertTrue(cube.collision().faceSturdy(0));
    }

    // Worked out through the client's float steps: the off-centre box turned a quarter about Y keeps an error of 2^-24 and -2^-26 at two corners.
    @Test
    void theQuaternionErrorIsKeptBitForBit() {
        assertArrayEquals(new float[]{0x1p-24F, 0, -0x1p-26F, 0.75F, 0.5F, 0.25F}, moved(collision(OFF_CENTRE_PIXELS), turn(0, 1, 0)));
    }

    // A quarter turn gives a flat floor plate a thickness of 5.96e-8, which the client loses at every block but those within 2 of 0, so the plate has no boxes.
    @Test
    void aSliverFromATurnHasNoBoxes() {
        assertArrayEquals(new float[0], moved(collision(new float[]{0, 0, 0, 16, 0, 16}), turn(1, 0, 0)));
        assertArrayEquals(new float[0], moved(collision(new float[]{0, 0, 0, 0, 16, 16}), turn(0, 0, 1)));
    }

    @Test
    void aNegativeTurnCountIsNoTurn() {
        NbtMap box = collision(OFF_CENTRE_PIXELS);
        CustomBlock plain = block(components(box));
        for (NbtMap transformation : List.of(turn(-1, 0, 0), turn(0, -3, 0), turn(0, 0, Integer.MIN_VALUE))) {
            assertSame(plain.collision().boxes(0), block(components(box, transformation)).collision().boxes(0));
        }
    }

    @Test
    void theRotationPivot() {
        NbtMap box = collision(OFF_CENTRE_PIXELS);
        float[] pivot = {0.5F, 0, -0.25F};
        NbtMap transformation = turn(0, 1, 0).toBuilder().putFloat("RXP", pivot[0]).putFloat("RYP", pivot[1]).putFloat("RZP", pivot[2]).build();
        assertNear(expected(OFF_CENTRE, new int[]{0, 1, 0}, ONE, ZERO, pivot), moved(box, transformation));
        // By hand: a half turn about a pivot 0.5 east of the centre puts X 0.25 to 0.5 from the centre at 0.5 to 0.75 from it.
        assertNear(new float[]{1, 0, 0.25F, 1.25F, 0.5F, 1}, moved(box, turn(0, 2, 0).toBuilder().putFloat("RXP", 0.5F).build()));
    }

    @Test
    void everyPartAtOnce() {
        NbtMap box = collision(OFF_CENTRE_PIXELS);
        float[] scale = {0.5F, 2, 1.5F};
        float[] translation = {0.125F, -0.25F, 0.5F};
        float[] rotationPivot = {0.25F, -0.125F, 0.5F};
        float[] scalePivot = {-0.5F, 0.25F, 0.125F};
        NbtMap transformation = geyser(1, 3, 2, scale[0], scale[1], scale[2], translation[0], translation[1], translation[2]).toBuilder()
                .putFloat("RXP", rotationPivot[0]).putFloat("RYP", rotationPivot[1]).putFloat("RZP", rotationPivot[2])
                .putFloat("SXP", scalePivot[0]).putFloat("SYP", scalePivot[1]).putFloat("SZP", scalePivot[2])
                .build();
        assertNear(expected(OFF_CENTRE, new int[]{1, 3, 2}, scale, translation, rotationPivot, scalePivot), moved(box, transformation));
    }

    // Each value is 0 when it is missing or of another tag type, so a missing scale flattens every box and leaves no collision.
    @Test
    void missingOrMistypedValuesAreZero() {
        NbtMap slab = collision(new float[]{0, 0, 0, 16, 8, 16});
        assertArrayEquals(new float[0], moved(slab, NbtMap.EMPTY));
        assertArrayEquals(new float[0], moved(slab, NbtMap.builder().putInt("RX", 1).build()));
        NbtMapBuilder noY = turn(0, 0, 0).toBuilder();
        noY.remove("SY");
        assertArrayEquals(new float[0], moved(slab, noY.build()));
        assertArrayEquals(new float[0], moved(slab, turn(0, 0, 0).toBuilder().putInt("SX", 1).build()));
        // A turn count that is a float is no turn, and a translation that is a double does not move.
        assertArrayEquals(new float[]{0, 0, 0, 1, 0.5F, 1}, moved(slab, turn(0, 0, 0).toBuilder().putFloat("RX", 1).putDouble("TY", 1).build()));
        // A transformation that is no compound is no component.
        assertArrayEquals(new float[]{0, 0, 0, 1, 0.5F, 1}, readComponents(NbtMap.builder().putCompound("minecraft:collision_box", slab).putInt("minecraft:transformation", 1).build()));
        assertFalse(block(components(slab, NbtMap.EMPTY)).collision().faceSturdy(0));
    }

    @Test
    void onlyACollisionBoxIsMoved() {
        // Without a collision_box the block is a full cube that the transformation does not move, and a disabled one stays without collision.
        assertArrayEquals(CUBE, moved(null, geyser(0, 0, 0, 1, 1, 1, 0, 1, 0)));
        assertArrayEquals(new float[0], moved(disabled(), geyser(0, 0, 0, 1, 1, 1, 0, 1, 0)));
        // An enabled collision_box without boxes is a full cube that the transformation moves.
        assertArrayEquals(new float[]{0, 1, 0, 1, 2, 1}, moved(NbtMap.builder().putBoolean("enabled", true).build(), geyser(0, 0, 0, 1, 1, 1, 0, 1, 0)));
        // A box without volume stays without after a translation.
        assertArrayEquals(new float[]{0, 1, 0, 1, 2, 1}, moved(collision(new float[]{0, 0, 0, 16, 0, 16}, CUBE_PIXELS), geyser(0, 0, 0, 1, 1, 1, 0, 1, 0)));
    }

    @Test
    void valuesPastTheFloatRangeAreAFullCubeWithAGuess() {
        NbtMap slab = collision(new float[]{0, 0, 0, 16, 8, 16});
        for (NbtMap transformation : List.of(geyser(0, 0, 0, 1, 1, 1, Float.NaN, 0, 0), geyser(0, 0, 0, Float.POSITIVE_INFINITY, 1, 1, 0, 0, 0), geyser(0, 0, 0, 1, 1, Float.MAX_VALUE, 0, 0, Float.MAX_VALUE))) {
            CustomCollision collision = block(components(slab, transformation)).collision();
            assertArrayEquals(CUBE, corners(collision.boxes(0)));
            assertEquals("a minecraft:transformation moves its boxes past the float range", collision.guess());
        }
    }

    // A state's collision_box from its permutations is moved by the last transformation among its permutations, else by the block's. The block's own collision_box is moved only by the block's transformation.
    @Test
    void permutationsAndTheBlocksTransformation() {
        NbtMap slab = collision(new float[]{0, 0, 0, 16, 8, 16});
        NbtMap post = collision(new float[]{6, 0, 6, 10, 16, 10});
        NbtMap up = geyser(0, 0, 0, 1, 1, 1, 0, 1, 0);
        NbtMap east = geyser(0, 0, 0, 1, 1, 1, 0.25F, 0, 0);
        float[] slabUp = {0, 1, 0, 1, 1.5F, 1};
        float[] slabEast = {0.25F, 0, 0, 1.25F, 0.5F, 1};
        float[] postUp = {0.375F, 1, 0.375F, 0.625F, 2, 0.625F};
        float[] postEast = {0.625F, 0, 0.375F, 0.875F, 1, 0.625F};
        CustomBlock block = block(components(slab, up),
                // Only a collision_box: moved by the block's transformation
                permutation("q.block_state('age') == 1", components(post)),
                // Only a transformation: the block's collision_box does not move with it
                permutation("q.block_state('open') == true", NbtMap.builder().putCompound("minecraft:transformation", east).build()),
                // Both: its own transformation
                permutation("q.block_state('age') == 2", components(post, east)));
        CustomCollision collision = block.collision();
        assertArrayEquals(slabUp, corners(collision.boxes(state(0, 0, "red"))));
        assertArrayEquals(postUp, corners(collision.boxes(state(0, 1, "red"))));
        assertArrayEquals(slabUp, corners(collision.boxes(state(1, 0, "red"))));
        // A collision_box of one permutation is moved by the transformation of a later one.
        assertArrayEquals(postEast, corners(collision.boxes(state(1, 1, "red"))));
        assertArrayEquals(postEast, corners(collision.boxes(state(0, 2, "red"))));
        assertNull(collision.guess());

        // A permutation's transformation leaves the block's collision_box where it is when the block has no transformation too.
        CustomBlock plain = block(components(slab), permutation("q.block_state('age') == 1", components(post)), permutation("q.block_state('open') == true", NbtMap.builder().putCompound("minecraft:transformation", east).build()));
        assertArrayEquals(new float[]{0, 0, 0, 1, 0.5F, 1}, corners(plain.collision().boxes(state(1, 0, "red"))));
        assertArrayEquals(postEast, corners(plain.collision().boxes(state(1, 1, "red"))));
        assertArrayEquals(new float[]{0.375F, 0, 0.375F, 0.625F, 1, 0.625F}, corners(plain.collision().boxes(state(0, 1, "red"))));
        assertArrayEquals(slabEast, corners(block(components(slab, east)).collision().boxes(0)));
    }

    // An unknown condition on a transformation matters only for the states whose collision_box it could move.
    @Test
    void unknownTransformationConditions() {
        NbtMap slab = collision(new float[]{0, 0, 0, 16, 8, 16});
        NbtMap up = geyser(0, 0, 0, 1, 1, 1, 0, 1, 0);
        NbtMap unknown = NbtMap.builder().putString("condition", "q.cardinal_facing == 2").putCompound("components", NbtMap.builder().putCompound("minecraft:transformation", up).build()).build();
        CustomBlock baseBoxes = block(components(slab), unknown);
        assertArrayEquals(new float[]{0, 0, 0, 1, 0.5F, 1}, corners(baseBoxes.collision().boxes(0)));
        assertNull(baseBoxes.collision().guess());
        CustomBlock ownBoxes = block(NbtMap.EMPTY, permutation("q.block_state('open') == true", components(slab)), unknown);
        assertArrayEquals(CUBE, corners(ownBoxes.collision().boxes(state(0, 0, "red"))));
        assertArrayEquals(CUBE, corners(ownBoxes.collision().boxes(state(1, 0, "red"))));
        assertEquals("Boar cannot read the permutation condition \"q.cardinal_facing == 2\"", ownBoxes.collision().guess());
    }

    @Test
    void movedShapesAreSharedAndChargedToTheBudget() {
        NbtMap slab = collision(new float[]{0, 0, 0, 16, 8, 16});
        assertSame(block(components(slab, turn(0, 0, 1))).collision().boxes(0), block(components(collision(new float[]{0, 0, 0, 8, 16, 16}))).collision().boxes(0));
        // One state, one box read (2), and one box moved (2)
        BlockPropertyData moving = new BlockPropertyData("cubecraft:moving", NbtMap.builder().putCompound("components", components(slab, turn(0, 0, 1))).build());
        assertEquals("Boar has spent its time on the custom blocks of this StartGame", CustomBlock.of(moving, new CustomCollision.Budget(4)).collision().guess());
        assertNull(CustomBlock.of(moving, new CustomCollision.Budget(5)).collision().guess());
    }

    // Boar keeps only each state's final shape, so a moved block takes one of the MAX_NEW_SHAPES of a StartGame, as a block that is not moved does.
    @Test
    void aMovedBlockAddsOneShape() {
        CustomCollision.Budget budget = new CustomCollision.Budget();
        NbtMap up = geyser(0, 0, 0, 1, 1, 1, 0, 0.25F, 0);
        String guess = null;
        int added = 0;
        for (int i = 1; guess == null; i++) {
            float[] box = {0, 0, 0, 16, 16 - i / 1024F, 16};
            guess = CustomBlock.of(new BlockPropertyData("cubecraft:moved_" + i, NbtMap.builder().putCompound("components", components(collision(box), up)).build()), budget).collision().guess();
            added = guess == null ? i : added;
        }
        assertEquals(CustomCollision.MAX_NEW_SHAPES, added);
        assertEquals("Boar keeps no more than " + CustomCollision.MAX_NEW_SHAPES + " new custom block shapes from one StartGame", guess);
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
