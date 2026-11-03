package ac.oomph.boarium.boar.blocks;

import ac.boar.anticheat.collision.BlockRandomOffset;
import org.cloudburstmc.math.vector.Vector3i;
import org.cloudburstmc.nbt.NbtList;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The friction, replaceability, destroy time and random offset of each state of a custom block, as the 1.26.30 client reads the minecraft:friction, minecraft:replaceable, minecraft:destructible_by_mining and minecraft:random_offset components in StartGame's block properties.
 * A state whose value Boar cannot tell gets the value of a block Boar does not know, and {@link #guess()} says why Boar may not predict some states. A random offset Boar cannot read says why in {@link RandomOffset#unread()}.
 */
final class CustomComponents {
    // A custom block without minecraft:friction keeps its BlockType's friction (BlockComponentDirectData::_finalizeInit), which BlockType::BlockType sets to 0.6.
    static final float DEFAULT_FRICTION = 0.6F;
    private static final String FRICTION = "minecraft:friction";
    private static final String REPLACEABLE = "minecraft:replaceable";
    private static final String MINING = "minecraft:destructible_by_mining";
    private static final String RANDOM_OFFSET = "minecraft:random_offset";
    // 1024 blocks, in 1/16 of a block
    private static final float MAX_OFFSET = 16 * 1024;
    // What a server sends for a block that cannot be mined, such as destructible_by_mining false in its JSON
    private static final float UNBREAKABLE = -1;
    // A block without destructible_by_mining can be mined, since registerBlockFromDefinition gives every block type one of 0 seconds.
    private static final Values DEFAULT = new Values(DEFAULT_FRICTION, false, false, null);

    private final List<Values> values;
    // Each state's index in values, or null when every state has the first
    private final char[] stateValues;
    private final float destroyTime;
    private final String guess;

    private CustomComponents(List<Values> values, char[] stateValues, float destroyTime, String guess) {
        this.values = values;
        this.stateValues = stateValues;
        this.destroyTime = destroyTime;
        this.guess = guess;
    }

    /**
     * The components of each state of a custom block. Each permutation whose condition holds replaces the friction, destructible_by_mining and random_offset it has, in list order, as for minecraft:collision_box.
     * BlockType::addCollisionShapes (0x109e5a450) also takes the random offset from the state's components first, then from the block's.
     *
     * @param keys the block's keys, as {@link CustomBlock} has them
     * @param values each key's values
     * @param radices how many permutations each key spans in the block's states, as {@link CustomBlock#radices(List)} gives them
     */
    static CustomComponents of(List<String> keys, List<NbtList<?>> values, int[] radices, int stateCount, NbtMap properties, CustomCollision.Budget budget) {
        boolean offsets = hasOffset(properties);
        if (!budget.take(stateCount)) {
            return guessed(CustomCollision.SPENT, offsets);
        }
        Reader reader = new Reader();
        NbtMap components = properties.get("components") instanceof NbtMap map ? map : NbtMap.EMPTY;
        float baseFriction = reader.friction(components.get(FRICTION), DEFAULT_FRICTION);
        boolean baseReplaceable = components.get(REPLACEABLE) instanceof NbtMap;
        boolean baseUnbreakable = reader.unbreakable(components.get(MINING), DEFAULT.unbreakable());
        RandomOffset baseOffset = reader.offset(components.get(RANDOM_OFFSET));
        int molangVersion = properties.get("molangVersion") instanceof Integer version && version >= 0 && version <= 13 ? version : 0;
        List<Permutation> permutations = new ArrayList<>();
        if (properties.get("permutations") instanceof NbtList<?> list && list.getType() == NbtType.COMPOUND) {
            for (Object element : list) {
                if (!budget.take(1)) {
                    return guessed(CustomCollision.SPENT, offsets);
                }
                if (!(((NbtMap) element).get("components") instanceof NbtMap permutation)) {
                    continue;
                }
                Object friction = permutation.get(FRICTION);
                boolean replaceable = permutation.get(REPLACEABLE) instanceof NbtMap;
                Object mining = permutation.get(MINING);
                Object offset = permutation.get(RANDOM_OFFSET);
                if (!(friction instanceof NbtMap) && !replaceable && !(mining instanceof NbtMap) && !(offset instanceof NbtMap)) {
                    continue;
                }
                if (permutations.size() == CustomCollision.MAX_PERMUTATIONS) {
                    return guessed("more than " + CustomCollision.MAX_PERMUTATIONS + " of its permutations set its friction, replaceable, destructible_by_mining or random_offset", offsets);
                }
                String text = ((NbtMap) element).get("condition") instanceof String string ? string : "";
                if (!budget.take(text.length())) {
                    return guessed(CustomCollision.SPENT, offsets);
                }
                BlockCondition condition = BlockCondition.parse(text, keys, molangVersion);
                if (!budget.take((long) stateCount * (condition == null ? 1 : condition.size))) {
                    return guessed(CustomCollision.SPENT, offsets);
                }
                String unread = "Boar cannot read the permutation condition \"" + (text.length() > 64 ? text.substring(0, 64) + "..." : text) + "\"";
                Float ownFriction = friction instanceof NbtMap ? reader.friction(friction, DEFAULT_FRICTION) : null;
                Boolean ownUnbreakable = mining instanceof NbtMap ? reader.unbreakable(mining, DEFAULT.unbreakable()) : null;
                boolean ownValues = ownFriction != null || replaceable || ownUnbreakable != null;
                permutations.add(new Permutation(condition, ownFriction, replaceable, ownUnbreakable, reader.offset(offset), ownValues, unread));
            }
        }

        List<Values> distinct = new ArrayList<>();
        Map<Values, Integer> indices = new HashMap<>();
        char[] stateValues = new char[stateCount];
        boolean someUnbreakable = false;
        boolean someBreakable = false;
        String guess = reader.guess;
        Object[] state = new Object[keys.size()];
        for (int s = 0; s < stateCount; s++) {
            int rest = s;
            for (int p = 0; p < state.length; p++) {
                state[p] = CustomBlock.value(values.get(p), rest % radices[p]);
                rest /= radices[p];
            }
            float friction = baseFriction;
            boolean replaceable = baseReplaceable;
            boolean unbreakable = baseUnbreakable;
            RandomOffset offset = baseOffset;
            for (Permutation permutation : permutations) {
                Boolean applies = permutation.condition() == null ? null : permutation.condition().test(state);
                if (Boolean.FALSE.equals(applies)) {
                    continue;
                }
                if (applies == null && guess == null && permutation.ownValues()) {
                    guess = permutation.unread();
                }
                if (permutation.friction() != null) {
                    friction = applies == null ? DEFAULT.friction() : permutation.friction();
                }
                // BlockType::canBeBuiltOver asks whether the state's own components or the block's have minecraft:replaceable, so a permutation can add it but not take it away.
                if (permutation.replaceable() && applies != null) {
                    replaceable = true;
                }
                if (permutation.unbreakable() != null) {
                    unbreakable = applies == null ? DEFAULT.unbreakable() : permutation.unbreakable();
                }
                if (permutation.offset() != null) {
                    offset = applies == null ? RandomOffset.unread(permutation.unread()) : permutation.offset();
                }
            }
            someUnbreakable |= unbreakable;
            someBreakable |= !unbreakable;
            stateValues[s] = (char) (int) indices.computeIfAbsent(new Values(friction, replaceable, unbreakable, offset), added -> {
                distinct.add(added);
                return distinct.size() - 1;
            });
        }
        // Boar asks a block, not a state, whether it can be mined, so a block that is unbreakable in only some states is breakable to it.
        if (someUnbreakable && someBreakable && guess == null) {
            guess = "only some of its states are unbreakable, and Boar's block is breakable or not in every state";
        }
        float destroyTime = someBreakable ? 0 : UNBREAKABLE;
        return new CustomComponents(List.copyOf(distinct), distinct.size() == 1 ? null : stateValues, destroyTime, guess);
    }

    // Every state a block Boar does not know, for this reason. When the block has a random_offset, Boar does not know any state's offset either.
    private static CustomComponents guessed(String why, boolean offsets) {
        Values values = offsets ? new Values(DEFAULT.friction(), DEFAULT.replaceable(), DEFAULT.unbreakable(), RandomOffset.unread(why)) : DEFAULT;
        return new CustomComponents(List.of(values), null, 0, why);
    }

    private static boolean hasOffset(NbtMap properties) {
        if (properties.get("components") instanceof NbtMap components && components.get(RANDOM_OFFSET) instanceof NbtMap) {
            return true;
        }
        if (properties.get("permutations") instanceof NbtList<?> permutations) {
            for (Object permutation : permutations) {
                if (permutation instanceof NbtMap map && map.get("components") instanceof NbtMap components && components.get(RANDOM_OFFSET) instanceof NbtMap) {
                    return true;
                }
            }
        }
        return false;
    }

    // The state's friction
    float friction(int state) {
        return this.values(state).friction();
    }

    // Whether a block placed against this state replaces it
    boolean replaceable(int state) {
        return this.values(state).replaceable();
    }

    // -1 when no state can be mined, else 0, as Boar's Block has one destroy time for every state
    float destroyTime() {
        return this.destroyTime;
    }

    // The state's random offset, or null when it has none
    RandomOffset offset(int state) {
        return this.values(state).offset();
    }

    // Why Boar may not predict some states' values, or null when it knows every state's values
    String guess() {
        return this.guess;
    }

    private Values values(int state) {
        return this.values.get(this.stateValues == null ? 0 : this.stateValues[state]);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof CustomComponents components && components.values.equals(this.values) && Arrays.equals(components.stateValues, this.stateValues);
    }

    @Override
    public int hashCode() {
        return this.values.hashCode() * 31 + Arrays.hashCode(this.stateValues);
    }

    @Override
    public String toString() {
        return this.values.size() + " sets of friction, replaceable, destroy time and random offset" + (this.guess == null ? "" : ", guessed: " + this.guess);
    }

    // Reads the components, keeping the first reason Boar cannot use a friction or destructible_by_mining value. A random offset keeps its own reason.
    private static final class Reader {
        private String guess;

        // The friction of a minecraft:friction compound. BlockFrictionDescription::initializeFromNetwork reads "value" only from a float tag, else 0, and initializeComponent makes the friction 1 minus it.
        float friction(Object tag, float otherwise) {
            if (!(tag instanceof NbtMap map)) {
                return otherwise;
            }
            float friction = 1 - (map.get("value") instanceof Float value ? value : 0);
            if (!Float.isFinite(friction)) {
                this.guess = this.guess == null ? "a minecraft:friction value is not a finite number" : this.guess;
                return DEFAULT.friction();
            }
            // The client's speed on the block takes any friction (GroundTravelTypeSystemImpl::_calcGroundFrictionV2), and so does Boar.
            // Its slowdown takes any friction only for an actor with USES_LEGACY_FRICTION, and else one clamped to 0 to 1 (MobMovementFriction::tickNormalFriction), while Boar always uses it unclamped.
            if (friction < 0 || friction > 1) {
                this.guess = this.guess == null ? "a minecraft:friction value is outside 0 to 1, where the client slows a player without USES_LEGACY_FRICTION more than Boar does" : this.guess;
            }
            return friction;
        }

        // Whether a minecraft:destructible_by_mining compound makes the block unbreakable. BlockDestructibleByMiningDescription::initializeFromNetwork reads "value" only from a float tag, else 0. A server sends -1 for a block that cannot be mined.
        boolean unbreakable(Object tag, boolean otherwise) {
            if (!(tag instanceof NbtMap map)) {
                return otherwise;
            }
            return map.get("value") instanceof Float value && value == UNBREAKABLE;
        }

        /**
         * A minecraft:random_offset compound, or null when it is not a compound. BlockRandomOffsetDescription's schema (bindType 0x10a430a80) reads x, y and z, each {range: {min, max}, steps}, and its NBT reader takes min and max only from float tags and steps only from an int tag (NBTSchemaReader::asFloat, asUInt32).
         * Boar reads only that whole form, since it has not followed what the client does with the rest, and only the values where the client and Boar's BlockRandomOffset agree.
         */
        RandomOffset offset(Object tag) {
            if (!(tag instanceof NbtMap map)) {
                return null;
            }
            BlockRandomOffset.Axis[] axes = new BlockRandomOffset.Axis[3];
            String[] names = {"x", "y", "z"};
            for (int i = 0; i < axes.length; i++) {
                if (!(map.get(names[i]) instanceof NbtMap axis && axis.get("range") instanceof NbtMap range && range.get("min") instanceof Float min && range.get("max") instanceof Float max && axis.get("steps") instanceof Integer steps)) {
                    return RandomOffset.unread("a minecraft:random_offset is not x, y and z, each a float range and int steps");
                }
                // The client reads steps as unsigned, and BlockRandomOffset takes them as signed.
                if (steps < 0) {
                    return RandomOffset.unread("a minecraft:random_offset has steps of 2^31 or more");
                }
                // So that a moved position stays well inside an int
                if (Math.abs(min) > MAX_OFFSET || Math.abs(max) > MAX_OFFSET) {
                    return RandomOffset.unread("a minecraft:random_offset reaches past 1024 blocks");
                }
                // Else a min not below max gives min in both (0x10a430544), and a NaN min gives NaN, which both round to 0 (the arm64 client's fcvtms, and Java's cast). With a NaN max, the client's min < max is unordered, which takes the branch that gives NaN, where BlockRandomOffset gives min.
                if (Float.isNaN(max) && !Float.isNaN(min)) {
                    return RandomOffset.unread("a minecraft:random_offset has a range whose max is NaN");
                }
                // BlockRandomOffsetDescription::initializeComponent (0x10a430700) turns min and max from 1/16 of a block into blocks.
                axes[i] = new BlockRandomOffset.Axis(min * 0.0625F, max * 0.0625F, steps);
            }
            return new RandomOffset(axes[0], axes[1], axes[2], null);
        }
    }

    /**
     * A minecraft:random_offset, in blocks, or no axes and why Boar cannot read it. BlockRandomOffsetComponent::getRandomOffset (0x10a430474) gives the offset of a block position, which Boar's BlockRandomOffset computes the same way, with a separate multiply and add.
     */
    record RandomOffset(BlockRandomOffset.Axis x, BlockRandomOffset.Axis y, BlockRandomOffset.Axis z, String unread) {
        static RandomOffset unread(String why) {
            return new RandomOffset(null, null, null, why);
        }

        // BlockType::addCollisionShapes (0x109e5a450) rounds the offset down to a BlockPos, and adds that to the block's position, so a collision_box moves by whole blocks.
        Vector3i blocks(int x, int z, boolean wideSeed) {
            float[] offset = BlockRandomOffset.get(x, z, wideSeed, this.x, this.y, this.z);
            return Vector3i.from((int) Math.floor(offset[0]), (int) Math.floor(offset[1]), (int) Math.floor(offset[2]));
        }
    }

    // One state's values
    private record Values(float friction, boolean replaceable, boolean unbreakable, RandomOffset offset) {
    }

    // A permutation's components, each null or false when it does not have it. ownValues says whether it has a friction, replaceable or destructible_by_mining.
    private record Permutation(BlockCondition condition, Float friction, boolean replaceable, Boolean unbreakable, RandomOffset offset, boolean ownValues, String unread) {
    }
}
