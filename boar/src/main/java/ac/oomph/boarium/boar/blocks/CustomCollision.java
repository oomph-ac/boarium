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
 * The collision boxes of each state of a custom block, as the 1.26.30 client builds them from the minecraft:collision_box and minecraft:transformation components in StartGame's block properties.
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
    private static final float[] CUBE_CORNERS = {0, 0, 0, 1, 1, 1};
    private static final Shape CUBE = new Shape(FULL_CUBE, null);
    // The client drops a box without volume only after it adds the block's world position as a float (BlockCollisionBoxComponent::addComponentCollisionShapes). A quarter turn leaves slivers about 6e-8 thick, which it keeps only within 2 blocks of 0 on the thin axis, so Boar drops every box thinner than this.
    private static final float MIN_EXTENT = 1e-6F;
    // The quarter turns of BlockMatrixHelpers::bakeRotationMatrix, as 3x3 matrices by columns
    private static final float[] X_TURN = {1, 0, 0, 0, 0, 1, 0, -1, 0};
    private static final float[] Y_TURN = {0, 0, -1, 0, 1, 0, 1, 0, 0};
    private static final float[] Z_TURN = {0, 1, 0, -1, 0, 0, 0, 0, 1};
    static final String SPENT = "Boar has spent its time on the custom blocks of this StartGame";

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
        Transformation baseTransformation = transformation(components.get(TRANSFORMATION));
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
                Transformation transformation = transformation(permutation.get(TRANSFORMATION));
                if (collision == null && transformation == null) {
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
                permutations.add(new Permutation(condition, collision, transformation, new Component(null, unread), new Transformation(null, unread)));
            }
        }

        List<List<Box>> shapes = new ArrayList<>();
        Map<List<Box>, Integer> indices = new IdentityHashMap<>();
        // Each state's shape by its transformation, which may be null, and its collision_box
        Map<Transformation, Map<Component, Shape>> finished = new IdentityHashMap<>();
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
            Component ownCollision = null;
            Transformation ownTransformation = null;
            for (Permutation permutation : permutations) {
                Boolean applies = permutation.condition() == null ? null : permutation.condition().test(state);
                if (Boolean.FALSE.equals(applies)) {
                    continue;
                }
                if (permutation.collision() != null) {
                    ownCollision = applies == null ? permutation.unread() : permutation.collision();
                    collision = ownCollision;
                }
                if (permutation.transformation() != null) {
                    ownTransformation = applies == null ? permutation.unreadTransformation() : permutation.transformation();
                }
            }
            // A state's collision_box from the permutations that apply to it is moved by the last transformation among them, else by the block's. The block's own collision_box is moved only by the block's transformation (_finalizeCollisionBoxComponent of BlockComponentStorageFinalizer and BlockTypeComponentStorageFinalizer).
            Transformation transformation = ownCollision != null && ownTransformation != null ? ownTransformation : baseTransformation;
            // Without the component, a custom block is a full cube.
            Shape shape = collision == null ? CUBE : finished.computeIfAbsent(transformation, t -> new IdentityHashMap<>()).computeIfAbsent(collision, c -> shape(c, transformation, budget));
            List<Box> boxes = shape.boxes();
            String why = shape.guess();
            if (boxes == null) {
                boxes = FULL_CUBE;
                guess = guess == null ? why : guess;
            }
            stateShapes[s] = (char) (int) indices.computeIfAbsent(boxes, added -> {
                shapes.add(added);
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

    // Each distinct shape is one list.
    boolean sameBoxes(int state, int other) {
        return this.boxes(state) == this.boxes(other);
    }

    // Sturdy means one full-cube box, as for vanilla blocks.
    boolean faceSturdy(int state) {
        return this.boxes(state) == FULL_CUBE;
    }

    // Why Boar takes some states for full cubes, or null when it knows every state's boxes
    String guess() {
        return this.guess;
    }

    // A minecraft:collision_box component as the client reads it. Null when the client has no such component.
    private static Component component(Object tag, Budget budget) {
        // The client reads a component only from a compound (BlockDefinitionGroup::digestServerBlockProperties).
        if (!(tag instanceof NbtMap map)) {
            return null;
        }
        // BlockCollisionBoxDescription::initializeFromNetwork reads "enabled" as a byte, false when missing, and "boxes" as a list of compounds with float corners, each 0 when missing. It reads no "origin" or "size".
        if (!(map.get("enabled") instanceof Byte enabled) || enabled == 0) {
            return new Component(new float[0], null);
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
            return new Component(CUBE_CORNERS, null);
        }
        float[] corners = new float[boxes.size() * 6];
        for (int i = 0; i < boxes.size(); i++) {
            System.arraycopy(corners(boxes.get(i)), 0, corners, i * 6, 6);
        }
        return new Component(corners, null);
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

    // The shared list of these boxes without the ones thinner than MIN_EXTENT, or why Boar keeps no new one
    private static Shape intern(float[] corners, Budget budget) {
        float[] solid = new float[corners.length];
        int length = 0;
        for (int i = 0; i < corners.length; i += 6) {
            if (corners[i + 3] - corners[i] > MIN_EXTENT && corners[i + 4] - corners[i + 1] > MIN_EXTENT && corners[i + 5] - corners[i + 2] > MIN_EXTENT) {
                // Adding 0 turns -0 into 0, so equal boxes have equal keys.
                for (int j = 0; j < 6; j++) {
                    solid[length + j] = corners[i + j] + 0F;
                }
                length += 6;
            }
        }
        Corners key = new Corners(Arrays.copyOf(solid, length));
        List<Box> shape = SHAPES.get(key);
        if (shape != null) {
            return new Shape(shape, null);
        }
        if (SHAPES.size() >= MAX_SHAPES) {
            return new Shape(null, "Boar keeps no more than " + MAX_SHAPES + " custom block shapes");
        }
        if (budget.newShapes == MAX_NEW_SHAPES) {
            return new Shape(null, "Boar keeps no more than " + MAX_NEW_SHAPES + " new custom block shapes from one StartGame");
        }
        budget.newShapes++;
        List<Box> boxes = new ArrayList<>(length / 6);
        for (int i = 0; i < length; i += 6) {
            boxes.add(new Box(solid[i], solid[i + 1], solid[i + 2], solid[i + 3], solid[i + 4], solid[i + 5]));
        }
        return new Shape(SHAPES.computeIfAbsent(key, k -> List.copyOf(boxes)), null);
    }

    // A minecraft:transformation as BlockTransformationDescription::initializeFromNetwork reads it, as the matrix that moves collision boxes. Null when the client has no such component.
    private static Transformation transformation(Object tag) {
        if (!(tag instanceof NbtMap map)) {
            return null;
        }
        // Rotations are int quarter turns, the rest floats, each 0 when missing or of another tag type. So a missing scale is 0, which flattens every box.
        float[] rotation = {1, 0, 0, 0, 1, 0, 0, 0, 1};
        rotation = turn(rotation, Z_TURN, map.get("RZ") instanceof Integer turns ? turns : 0);
        rotation = turn(rotation, Y_TURN, map.get("RY") instanceof Integer turns ? turns : 0);
        rotation = turn(rotation, X_TURN, map.get("RX") instanceof Integer turns ? turns : 0);
        float[] r = throughQuaternion(rotation);
        float[] t = floats(map, "TX", "TY", "TZ");
        float[] rp = floats(map, "RXP", "RYP", "RZP");
        float[] s = floats(map, "SX", "SY", "SZ");
        float[] sp = floats(map, "SXP", "SYP", "SZP");
        // BlockMatrixHelpers::calculateTransformMatrix builds M = T(t + rp) R T(-rp) T(sp) S T(-sp), with these float steps, and _finalizeCollisionBoxComponent centres it on the block: T(0.5) M T(-0.5).
        // The matrix is 3x3 by columns, then the translation.
        float[] matrix = new float[12];
        for (int i = 0; i < 3; i++) {
            float a0 = r[i];
            float a1 = r[3 + i];
            float a2 = r[6 + i];
            float m0 = a0 * s[0];
            float m1 = a1 * s[1];
            float m2 = a2 * s[2];
            float translation = (t[i] + rp[i]) + ((a1 * -rp[1] - a0 * rp[0]) - a2 * rp[2]);
            translation += (a0 * sp[0] + a1 * sp[1]) + a2 * sp[2];
            translation += (m1 * -sp[1] - m0 * sp[0]) - m2 * sp[2];
            matrix[i] = m0;
            matrix[3 + i] = m1;
            matrix[6 + i] = m2;
            matrix[9 + i] = ((m0 * -0.5F + m1 * -0.5F) + m2 * -0.5F) + (translation + 0.5F);
        }
        return new Transformation(matrix, null);
    }

    private static float[] floats(NbtMap map, String x, String y, String z) {
        return new float[]{map.get(x) instanceof Float value ? value : 0, map.get(y) instanceof Float value ? value : 0, map.get(z) instanceof Float value ? value : 0};
    }

    // The rotation times this quarter turn, this many times. The client turns only while the count is above 0, so a negative count is no turn.
    private static float[] turn(float[] rotation, float[] turn, int count) {
        // Four turns are no turn.
        for (int n = count > 0 ? count % 4 : 0; n > 0; n--) {
            float[] product = new float[9];
            for (int column = 0; column < 3; column++) {
                for (int row = 0; row < 3; row++) {
                    product[column * 3 + row] = ((rotation[row] * turn[column * 3] + rotation[3 + row] * turn[column * 3 + 1]) + rotation[6 + row] * turn[column * 3 + 2]) + 0F;
                }
            }
            rotation = product;
        }
        return rotation;
    }

    // The client turns the rotation into a quaternion and back (Quaternion::Quaternion(Matrix), then calculateTransformMatrix), so a quarter turn has entries like 5.96e-8 and 0.99999994.
    private static float[] throughQuaternion(float[] m) {
        float fourX = (m[0] - m[4]) - m[8];
        float fourY = (m[4] - m[0]) - m[8];
        float fourZ = (m[8] - m[0]) - m[4];
        float largest = (m[0] + m[4]) + m[8];
        int axis = 0;
        if (fourX > largest) {
            largest = fourX;
            axis = 1;
        }
        if (fourY > largest) {
            largest = fourY;
            axis = 2;
        }
        if (fourZ > largest) {
            largest = fourZ;
            axis = 3;
        }
        float big = (float) Math.sqrt(largest + 1) * 0.5F;
        float scale = 0.25F / big;
        float x;
        float y;
        float z;
        float w;
        switch (axis) {
            case 0 -> {
                w = big;
                x = (m[5] - m[7]) * scale;
                y = (m[6] - m[2]) * scale;
                z = (m[1] - m[3]) * scale;
            }
            case 1 -> {
                w = (m[5] - m[7]) * scale;
                x = big;
                y = (m[1] + m[3]) * scale;
                z = (m[2] + m[6]) * scale;
            }
            case 2 -> {
                w = (m[6] - m[2]) * scale;
                x = (m[1] + m[3]) * scale;
                y = big;
                z = (m[5] + m[7]) * scale;
            }
            default -> {
                w = (m[1] - m[3]) * scale;
                x = (m[2] + m[6]) * scale;
                y = (m[5] + m[7]) * scale;
                z = big;
            }
        }
        float xx = x * (x + x);
        float yy = y * (y + y);
        float zz = z * (z + z);
        float xy = (x + x) * y;
        float xz = (x + x) * z;
        float yz = (y + y) * z;
        float wx = x * (w + w);
        float wy = y * (w + w);
        float wz = z * (w + w);
        return new float[]{(1 - yy) - zz, xy + wz, xz - wy, xy - wz, (1 - xx) - zz, yz + wx, xz + wy, yz - wx, (1 - xx) - yy};
    }

    // A state's boxes: those of its collision_box, moved by its transformation when it has one
    private static Shape shape(Component collision, Transformation transformation, Budget budget) {
        if (collision.corners() == null) {
            return new Shape(null, collision.guess());
        }
        if (transformation == null) {
            return intern(collision.corners(), budget);
        }
        if (transformation.matrix() == null) {
            return new Shape(null, transformation.guess());
        }
        float[] from = collision.corners();
        if (!budget.take(1 + from.length / 6)) {
            return new Shape(null, SPENT);
        }
        // As AABB::cloneAndTransformByMatrix moves each box: its centre by the matrix, and its half size by the matrix's absolute values. The client does not clamp the moved boxes.
        float[] m = transformation.matrix();
        float[] to = new float[from.length];
        for (int i = 0; i < from.length; i += 6) {
            float hx = (from[i + 3] - from[i]) * 0.5F;
            float hy = (from[i + 4] - from[i + 1]) * 0.5F;
            float hz = (from[i + 5] - from[i + 2]) * 0.5F;
            float cx = from[i] + hx;
            float cy = from[i + 1] + hy;
            float cz = from[i + 2] + hz;
            for (int axis = 0; axis < 3; axis++) {
                float centre = (m[axis] * cx + m[3 + axis] * cy) + (m[6 + axis] * cz + m[9 + axis]);
                float half = (Math.abs(m[axis]) * hx + Math.abs(m[3 + axis]) * hy) + Math.abs(m[6 + axis]) * hz;
                to[i + axis] = centre - half;
                to[i + 3 + axis] = centre + half;
            }
        }
        for (float corner : to) {
            if (!Float.isFinite(corner)) {
                return new Shape(null, "a minecraft:transformation moves its boxes past the float range");
            }
        }
        return intern(to, budget);
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

    // A minecraft:collision_box component: every box's corners in blocks, or null and why Boar cannot tell them
    private record Component(float[] corners, String guess) {
    }

    // A state's boxes, shared, or null and why Boar cannot tell them
    private record Shape(List<Box> boxes, String guess) {
    }

    // The matrix of a minecraft:transformation, or null and why Boar cannot tell it
    private record Transformation(float[] matrix, String guess) {
    }

    private record Permutation(BlockCondition condition, Component collision, Transformation transformation, Component unread, Transformation unreadTransformation) {
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
