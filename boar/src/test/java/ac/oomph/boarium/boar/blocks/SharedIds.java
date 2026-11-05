package ac.oomph.boarium.boar.blocks;

import java.util.BitSet;
import java.util.HashMap;
import java.util.Map;

// Boar's intermediary IDs (shared IDs of BedrockBlockUnion) of the 1.26.30 palette, the one table whose players Boar checks
final class SharedIds {
    static final BedrockBlockTable V26_30 = BedrockBlockTable.get();
    static final BedrockBlockTable SHARED = BedrockBlockUnion.get().table();
    // The shared ID of each 1.26.30 palette index, in palette order
    static final int[] OF_26_30 = new int[V26_30.paletteSize()];
    private static final BitSet IN_26_30 = new BitSet();

    static {
        for (int i = 0; i < OF_26_30.length; i++) {
            OF_26_30[i] = BedrockBlockUnion.get().toShared(V26_30.protocol(), i);
            IN_26_30.set(OF_26_30[i]);
        }
    }

    private SharedIds() {
    }

    static boolean in26_30(int id) {
        return id >= 0 && IN_26_30.get(id);
    }

    // The shared ID of a 1.26.30 palette index; an ID from BedrockPalette.FIRST_CUSTOM_ID, or below 0, stays as it is, and one between the two tables' sizes throws
    static int shared(int index) {
        return index >= 0 && index < BedrockPalette.FIRST_CUSTOM_ID ? OF_26_30[index] : index;
    }

    // The 1.26.30 palette index of a shared ID, or -1 when it is another table's state; an ID from BedrockPalette.FIRST_CUSTOM_ID, or below 0, stays as it is
    static int own(int id) {
        if (id < 0 || id >= BedrockPalette.FIRST_CUSTOM_ID) {
            return id;
        }
        return in26_30(id) ? BedrockBlockUnion.get().fromShared(V26_30.protocol(), id) : -1;
    }

    static <T> Map<Integer, T> byShared(Map<Integer, T> byIndex) {
        Map<Integer, T> shared = new HashMap<>();
        byIndex.forEach((index, value) -> shared.put(OF_26_30[index], value));
        return shared;
    }
}
