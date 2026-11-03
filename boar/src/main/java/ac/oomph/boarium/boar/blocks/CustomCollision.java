package ac.oomph.boarium.boar.blocks;

import ac.boar.anticheat.util.math.Box;
import org.cloudburstmc.nbt.NbtList;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The collision boxes of each state of a custom block, as the 1.26.30 client builds them from the minecraft:collision_box components in StartGame's block properties.
 * A state whose boxes Boar cannot tell is a full cube, and {@link #guess()} says why.
 */
final class CustomCollision {
    static final List<Box> FULL_CUBE = List.of(new Box(0, 0, 0, 1, 1, 1));
    static final List<Box> NONE = List.of();
    // The client has no cap on boxes from the network; Boar stops at 16, the cap for resource packs (BlockCollisionBoxComponentDescriptor::fromCollisionBoxes).
    static final int MAX_BOXES = 16;
    static final int MAX_PERMUTATIONS = 1024;
    // Custom blocks keep their shapes for as long as their custom IDs, so the distinct shapes of all of them are capped, and one StartGame may add only a part of them.
    static final int MAX_SHAPES = 1 << 16;
    static final int MAX_NEW_SHAPES = MAX_SHAPES / 16;
    private static final Map<Corners, List<Box>> SHAPES = new ConcurrentHashMap<>();
    private static final String COLLISION = "minecraft:collision_box";
    private static final String TRANSFORMATION = "minecraft:transformation";
    private static final Component TRANSFORMED = new Component(null, "minecraft:transformation moves its boxes");
    private static final String SPENT = "Boar has spent its time on the custom blocks of this StartGame";

    static {
        SHAPES.put(new Corners(new float[]{0, 0, 0, 1, 1, 1}), FULL_CUBE);
        SHAPES.put(new Corners(new float[0]), NONE);
    }

    private final List<List<Box>> shapes;
    // Each state's index in shapes, or null when every state has the first
    private final char[] stateShapes;
    private final String guess;

    private CustomCollision(List<List<Box>> shapes, char[] stateShapes, String guess) {
        this.shapes = shapes;
        this.stateShapes = stateShapes;
        this.guess = guess;
    }

    /**
     * The collision of each state of a custom block.
     *
     * @param keys the block's keys, as {@link CustomBlock} has them
     * @param values each key's values
     * @param radices how many permutations each key spans in the block's states, where the first key changes fastest from one state to the next
     */
    static CustomCollision of(List<String> keys, List<NbtList<?>> values, int[] radices, int stateCount, NbtMap properties, Budget budget) {
        if (!budget.take(stateCount)) {
            return guessed(SPENT);
        }
        NbtMap components = properties.get("components") instanceof NbtMap map ? map : NbtMap.EMPTY;
        Component base = component(components.get(COLLISION), budget);
        boolean baseTransformed = components.get(TRANSFORMATION) instanceof NbtMap;
        // The client reads molangVersion only from an int tag, and uses 0 when it is missing or not from 0 to 13 (digestServerBlockProperties).
        int molangVersion = properties.get("molangVersion") instanceof Integer version && version >= 0 && version <= 13 ? version : 0;
        List<Permutation> permutations = new ArrayList<>();
        if (properties.get("permutations") instanceof NbtList<?> list && list.getType() == NbtType.COMPOUND) {
            for (Object element : list) {
                if (!budget.take(1)) {
                    return guessed(SPENT);
                }
                if (!(((NbtMap) element).get("components") instanceof NbtMap permutation)) {
                    continue;
                }
                Component collision = component(permutation.get(COLLISION), budget);
                boolean transformed = permutation.get(TRANSFORMATION) instanceof NbtMap;
                if (collision == null && !transformed) {
                    continue;
                }
                if (permutations.size() == MAX_PERMUTATIONS) {
                    return guessed("more than " + MAX_PERMUTATIONS + " of its permutations set its collision");
                }
                String text = ((NbtMap) element).get("condition") instanceof String string ? string : "";
                if (!budget.take(text.length())) {
                    return guessed(SPENT);
                }
                BlockCondition condition = BlockCondition.parse(text, keys, molangVersion);
                if (!budget.take((long) stateCount * (condition == null ? 1 : condition.size))) {
                    return guessed(SPENT);
                }
                String unread = "Boar cannot read the permutation condition \"" + (text.length() > 64 ? text.substring(0, 64) + "..." : text) + "\"";
                permutations.add(new Permutation(condition, collision, transformed, new Component(null, unread)));
            }
        }

        List<List<Box>> shapes = new ArrayList<>();
        Map<List<Box>, Integer> indices = new IdentityHashMap<>();
        char[] stateShapes = new char[stateCount];
        String guess = null;
        Object[] state = new Object[keys.size()];
        for (int s = 0; s < stateCount; s++) {
            int rest = s;
            for (int p = 0; p < state.length; p++) {
                state[p] = CustomBlock.value(values.get(p), rest % radices[p]);
                rest /= radices[p];
            }
            // Each permutation that applies replaces the components it has, in list order, so the last matching collision_box wins (BlockDefinitionGroup::initializeBlockFromDefinition; Block::Block lets a block's components be replaced).
            Component collision = base;
            Component transformation = baseTransformed ? TRANSFORMED : null;
            for (Permutation permutation : permutations) {
                Boolean applies = permutation.condition() == null ? null : permutation.condition().test(state);
                if (Boolean.FALSE.equals(applies)) {
                    continue;
                }
                if (permutation.collision() != null) {
                    collision = applies == null ? permutation.unread() : permutation.collision();
                }
                if (permutation.transformed()) {
                    transformation = applies == null ? permutation.unread() : TRANSFORMED;
                }
            }
            // Without the component, a custom block is a full cube. The client moves the boxes of a component by the block's transformation, which Boar does not.
            List<Box> boxes = collision == null ? FULL_CUBE : collision.boxes();
            String why = collision == null ? null : collision.guess();
            if (boxes != null && !boxes.isEmpty() && collision != null && transformation != null) {
                boxes = null;
                why = transformation.guess();
            }
            if (boxes == null) {
                boxes = FULL_CUBE;
                guess = guess == null ? why : guess;
            }
            stateShapes[s] = (char) (int) indices.computeIfAbsent(boxes, shape -> {
                shapes.add(shape);
                return shapes.size() - 1;
            });
        }
        return new CustomCollision(List.copyOf(shapes), shapes.size() == 1 ? null : stateShapes, guess);
    }

    // Every state a full cube, for this reason
    static CustomCollision guessed(String why) {
        return new CustomCollision(List.of(FULL_CUBE), null, why);
    }

    List<Box> boxes(int state) {
        return this.shapes.get(this.stateShapes == null ? 0 : this.stateShapes[state]);
    }

    // Sturdy means one full-cube box, as for vanilla blocks.
    boolean faceSturdy(int state) {
        return this.boxes(state) == FULL_CUBE;
    }

    // Why Boar takes some states for full cubes, or null when it knows every state's boxes
    String guess() {
        return this.guess;
    }

    // A minecraft:collision_box component as the client reads it: its boxes, or null and why Boar cannot tell them. Null when the client has no such component.
    private static Component component(Object tag, Budget budget) {
        // The client reads a component only from a compound (BlockDefinitionGroup::digestServerBlockProperties).
        if (!(tag instanceof NbtMap map)) {
            return null;
        }
        // BlockCollisionBoxDescription::initializeFromNetwork reads "enabled" as a byte, false when missing, and "boxes" as a list of compounds with float corners, each 0 when missing. It reads no "origin" or "size".
        if (!(map.get("enabled") instanceof Byte enabled) || enabled == 0) {
            return new Component(NONE, null);
        }
        @SuppressWarnings("unchecked")
        List<NbtMap> boxes = map.get("boxes") instanceof NbtList<?> list && list.getType() == NbtType.COMPOUND ? (List<NbtMap>) list : List.of();
        if (boxes.size() > MAX_BOXES) {
            return new Component(null, "a collision_box has more than " + MAX_BOXES + " boxes");
        }
        if (!budget.take(1 + boxes.size())) {
            return new Component(null, SPENT);
        }
        // Without boxes, the client uses a full cube (BlockCollisionBoxDescription::initializeComponent).
        if (boxes.isEmpty()) {
            return new Component(FULL_CUBE, null);
        }
        float[] corners = new float[boxes.size() * 6];
        int length = 0;
        for (NbtMap box : boxes) {
            float[] corner = corners(box);
            // The client leaves out a box without volume (BlockCollisionBoxComponent::addComponentCollisionShapes).
            if (corner[0] < corner[3] && corner[1] < corner[4] && corner[2] < corner[5]) {
                System.arraycopy(corner, 0, corners, length, 6);
                length += 6;
            }
        }
        return intern(Arrays.copyOf(corners, length), budget);
    }

    // One box in blocks, as BlockCollisionBoxDescription::initializeComponent turns its corners, given in 1/16 of a block: clamped to 0 to 16 on X and Z and 0 to 24 on Y,
    // each axis sorted, and X mirrored about the middle of the block. The float steps are the client's.
    private static float[] corners(NbtMap box) {
        float x1 = clampXY(corner(box, "minX"), 16);
        float x2 = clampXY(corner(box, "maxX"), 16);
        float xHalf = (Math.max(x1, x2) - Math.min(x1, x2)) * 0.5F;
        float xMiddle = 1 - (Math.min(x1, x2) + xHalf);
        float y1 = clampXY(corner(box, "minY"), 24);
        float y2 = clampXY(corner(box, "maxY"), 24);
        float yHalf = (Math.max(y1, y2) - Math.min(y1, y2)) * 0.5F;
        float yMiddle = Math.min(y1, y2) + yHalf;
        float z1 = clampZ(corner(box, "minZ"));
        float z2 = clampZ(corner(box, "maxZ"));
        float zLow = Math.min(z1, z2);
        float zHalf = (Math.max(z1, z2) - zLow) * 0.5F;
        return new float[]{xMiddle - xHalf, yMiddle - yHalf, zLow + zHalf - zHalf, xHalf + xMiddle, yHalf + yMiddle, zHalf + zLow + zHalf};
    }

    private static float corner(NbtMap box, String key) {
        return box.get(key) instanceof Float value ? value : 0;
    }

    // One X or Y corner in blocks, from 1/16 of a block, clamped to 0 to max sixteenths. NaN becomes 0.
    private static float clampXY(float value, float max) {
        return value > max ? max * 0.0625F : value > 0 ? value * 0.0625F : 0;
    }

    // One Z corner in blocks, from 1/16 of a block, clamped to 0 to 16 sixteenths. NaN becomes 16, as in the client.
    private static float clampZ(float value) {
        return value <= 16 ? (value <= 0 ? 0 : value * 0.0625F) : 1;
    }

    // The shared list of boxes with these corners, or why Boar keeps no new one
    private static Component intern(float[] corners, Budget budget) {
        Corners key = new Corners(corners);
        List<Box> shape = SHAPES.get(key);
        if (shape != null) {
            return new Component(shape, null);
        }
        if (SHAPES.size() >= MAX_SHAPES) {
            return new Component(null, "Boar keeps no more than " + MAX_SHAPES + " custom block shapes");
        }
        if (budget.newShapes == MAX_NEW_SHAPES) {
            return new Component(null, "Boar keeps no more than " + MAX_NEW_SHAPES + " new custom block shapes from one StartGame");
        }
        budget.newShapes++;
        List<Box> boxes = new ArrayList<>(corners.length / 6);
        for (int i = 0; i < corners.length; i += 6) {
            boxes.add(new Box(corners[i], corners[i + 1], corners[i + 2], corners[i + 3], corners[i + 4], corners[i + 5]));
        }
        return new Component(SHAPES.computeIfAbsent(key, k -> List.copyOf(boxes)), null);
    }

    // Equal when every state has the same boxes. Shapes are shared, so the same lists mean the same boxes.
    @Override
    public boolean equals(Object other) {
        if (!(other instanceof CustomCollision collision) || collision.shapes.size() != this.shapes.size() || !Arrays.equals(collision.stateShapes, this.stateShapes)) {
            return false;
        }
        for (int i = 0; i < this.shapes.size(); i++) {
            if (collision.shapes.get(i) != this.shapes.get(i)) {
                return false;
            }
        }
        return true;
    }

    @Override
    public int hashCode() {
        int hash = Arrays.hashCode(this.stateShapes);
        for (List<Box> shape : this.shapes) {
            hash = hash * 31 + System.identityHashCode(shape);
        }
        return hash;
    }

    @Override
    public String toString() {
        return this.shapes.size() + " shapes" + (this.guess == null ? "" : ", guessed: " + this.guess);
    }

    // A limit on the work Boar spends on the custom blocks of one StartGame, and the count of shapes it added
    static final class Budget {
        static final long MAX = 1L << 24;
        private long left;
        private int newShapes;

        Budget() {
            this(MAX);
        }

        Budget(long left) {
            this.left = left;
        }

        boolean take(long work) {
            this.left -= work;
            return this.left >= 0;
        }
    }

    private record Component(List<Box> boxes, String guess) {
    }

    private record Permutation(BlockCondition condition, Component collision, boolean transformed, Component unread) {
    }

    private record Corners(float[] values) {
        @Override
        public boolean equals(Object other) {
            return other instanceof Corners corners && Arrays.equals(corners.values, this.values);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(this.values);
        }
    }
}
