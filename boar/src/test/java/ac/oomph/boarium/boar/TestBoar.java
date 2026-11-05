package ac.oomph.boarium.boar;

import ac.boar.anticheat.Boar;
import ac.boar.anticheat.player.BoarPlayer;
import ac.oomph.boarium.client.ClientLogin;
import ac.oomph.boarium.session.Processor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Function;

// Boar starts once in a JVM, so the test classes share one start.
public final class TestBoar {
    // The identifier of a static seat in the shared Boar's config
    public static final String STATIC_SEAT = "boarium:test_seat";
    private static Function<ClientLogin, Processor> processors;

    private TestBoar() {
    }

    // Marks a server batch as begun for this player, as BoarProcessor does at each packet from the server. Call on the client's event loop.
    public static void openServerBatch(BoarPlayer player) {
        ((BoariumPlayerManager.AckTransport) player.getAckTransport()).serverBatchOpen = true;
    }

    // Sets boarium's boar_mitigations for the players added from now on.
    public static void mitigations(boolean on) {
        ((BoariumPlayerManager) Boar.getInstance().getPlayerManager()).mitigations = on;
    }

    // Lets Boar warn again about players on a protocol it does not check.
    public static void forgetWarnings() {
        BoariumBoar.WARNED_PROTOCOLS.clear();
    }

    // Has Boar check the players who log in on this protocol from now on, or stop, for a protocol it does not check outside the tests.
    public static void check(int protocol, boolean on) {
        if (on) {
            BoariumBoar.TEST_PROTOCOLS.add(protocol);
        } else {
            BoariumBoar.TEST_PROTOCOLS.remove(protocol);
        }
    }

    public static synchronized Function<ClientLogin, Processor> start() throws IOException {
        if (processors == null) {
            processors = BoariumBoar.start(Files.createTempDirectory(Files.createDirectories(Path.of("build")), "boar"), false);
            Boar.getConfig().setStaticSeatIdentifiers(List.of(STATIC_SEAT));
        }
        return processors;
    }
}
