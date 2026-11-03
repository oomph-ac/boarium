package ac.oomph.boarium.boar.blocks;

import org.cloudburstmc.nbt.NbtList;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The friction, replaceability and destroy time of each state of a custom block, as the 1.26.30 client reads the minecraft:friction, minecraft:replaceable and minecraft:destructible_by_mining components in StartGame's block properties.
 * A state whose value Boar cannot tell gets the value of a block Boar does not know, and {@link #guess()} says why Boar may not predict some states.
 */
final class CustomComponents {
    // A custom block without minecraft:friction keeps its BlockType's friction (BlockComponentDirectData::_finalizeInit), which BlockType::BlockType sets to 0.6.
    static final float DEFAULT_FRICTION = 0.6F;
    private static final String FRICTION = "minecraft:friction";
    private static final String REPLACEABLE = "minecraft:replaceable";
    private static final String MINING = "minecraft:destructible_by_mining";
    // What a server sends for a block that cannot be mined, such as destructible_by_mining false in its JSON
    private static final float UNBREAKABLE = -1;
    // A block without destructible_by_mining can be mined, since registerBlockFromDefinition gives every block type one of 0 seconds.
    private static final Values DEFAULT = new Values(DEFAULT_FRICTION, false, false);

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
     * The components of each state of a custom block. Each permutation whose condition holds replaces the friction and destructible_by_mining it has, in list order, as for minecraft:collision_box.
     *
     * @param keys the block's keys, as {@link CustomBlock} has them
     * @param values each key's values
     * @param radices how many permutations each key spans in the block's states, as {@link CustomBlock#radices(List)} gives them
     */
    static CustomComponents of(List<String> keys, List<NbtList<?>> values, int[] radices, int stateCount, NbtMap properties, CustomCollision.Budget budget) {
        if (!budget.take(stateCount)) {
            return guessed(CustomCollision.SPENT);
        }
        Reader reader = new Reader();
        NbtMap components = properties.get("components") instanceof NbtMap map ? map : NbtMap.EMPTY;
        float baseFriction = reader.friction(components.get(FRICTION), DEFAULT_FRICTION);
        boolean baseReplaceable = components.get(REPLACEABLE) instanceof NbtMap;
        boolean baseUnbreakable = reader.unbreakable(components.get(MINING), DEFAULT.unbreakable());
        int molangVersion = properties.get("molangVersion") instanceof Integer version && version >= 0 && version <= 13 ? version : 0;
        List<Permutation> permutations = new ArrayList<>();
        if (properties.get("permutations") instanceof NbtList<?> list && list.getType() == NbtType.COMPOUND) {
            for (Object element : list) {
                if (!budget.take(1)) {
                    return guessed(CustomCollision.SPENT);
                }
                if (!(((NbtMap) element).get("components") instanceof NbtMap permutation)) {
                    continue;
                }
                Object friction = permutation.get(FRICTION);
                boolean replaceable = permutation.get(REPLACEABLE) instanceof NbtMap;
                Object mining = permutation.get(MINING);
                if (!(friction instanceof NbtMap) && !replaceable && !(mining instanceof NbtMap)) {
                    continue;
                }
                if (permutations.size() == CustomCollision.MAX_PERMUTATIONS) {
                    return guessed("more than " + CustomCollision.MAX_PERMUTATIONS + " of its permutations set its friction, replaceable or destructible_by_mining");
                }
                String text = ((NbtMap) element).get("condition") instanceof String string ? string : "";
                if (!budget.take(text.length())) {
                    return guessed(CustomCollision.SPENT);
                }
                BlockCondition condition = BlockCondition.parse(text, keys, molangVersion);
                if (!budget.take((long) stateCount * (condition == null ? 1 : condition.size))) {
                    return guessed(CustomCollision.SPENT);
                }
                String unread = "Boar cannot read the permutation condition \"" + (text.length() > 64 ? text.substring(0, 64) + "..." : text) + "\"";
                Float ownFriction = friction instanceof NbtMap ? reader.friction(friction, DEFAULT_FRICTION) : null;
                Boolean ownUnbreakable = mining instanceof NbtMap ? reader.unbreakable(mining, DEFAULT.unbreakable()) : null;
                permutations.add(new Permutation(condition, ownFriction, replaceable, ownUnbreakable, unread));
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
            for (Permutation permutation : permutations) {
                Boolean applies = permutation.condition() == null ? null : permutation.condition().test(state);
                if (Boolean.FALSE.equals(applies)) {
                    continue;
                }
                if (applies == null && guess == null) {
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
            }
            someUnbreakable |= unbreakable;
            someBreakable |= !unbreakable;
            stateValues[s] = (char) (int) indices.computeIfAbsent(new Values(friction, replaceable, unbreakable), added -> {
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

    // Every state a block Boar does not know, for this reason
    static CustomComponents guessed(String why) {
        return new CustomComponents(List.of(DEFAULT), null, 0, why);
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
        return this.values.size() + " sets of friction, replaceable and destroy time" + (this.guess == null ? "" : ", guessed: " + this.guess);
    }

    // Reads the components, keeping the first reason Boar cannot use a value
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
    }

    // One state's values
    private record Values(float friction, boolean replaceable, boolean unbreakable) {
    }

    // A permutation's components, each null or false when it does not have it
    private record Permutation(BlockCondition condition, Float friction, boolean replaceable, Boolean unbreakable, String unread) {
    }
}
