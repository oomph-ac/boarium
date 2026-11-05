package ac.oomph.boarium.boar.blocks;

import ac.oomph.boarium.boar.BoariumBoar;
import org.cloudburstmc.protocol.bedrock.packet.StartGamePacket;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

// Run in a JVM of its own by BedrockBlockUnionTest.heapAtStart: prints the heap taken and the block tables loaded after loading the shared and 1.26.30 tables, after starting Boar with one 1.26.30 palette, and after loading every table.
public final class HeapAtStart {
    public static void main(String[] args) throws Exception {
        long before = usedHeap();
        BedrockBlockUnion.get().projection(BedrockBlockTable.get().protocol());
        System.out.println("tables " + (usedHeap() - before) + " " + loadedTables());
        BoariumBoar.start(Files.createTempDirectory(Path.of(args[0]), "boar"), false);
        BedrockPalette.mappingInfo(new StartGamePacket());
        System.out.println("start " + (usedHeap() - before) + " " + loadedTables());
        BedrockBlockUnion.build();
        System.out.println("all " + (usedHeap() - before) + " " + loadedTables());
    }

    private static int loadedTables() throws ReflectiveOperationException {
        Field loaded = BedrockBlockTable.class.getDeclaredField("LOADED");
        loaded.setAccessible(true);
        return ((Map<?, ?>) loaded.get(null)).size();
    }

    private static long usedHeap() throws InterruptedException {
        long least = Long.MAX_VALUE;
        for (int i = 0; i < 5; i++) {
            System.gc();
            Thread.sleep(50);
            least = Math.min(least, Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory());
        }
        return least;
    }
}
