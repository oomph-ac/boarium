package ac.oomph.boarium.boar;

import ac.oomph.boarium.client.ClientLogin;
import ac.oomph.boarium.session.Processor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Function;

// Boar starts once in a JVM, so the test classes share one start.
public final class TestBoar {
    private static Function<ClientLogin, Processor> processors;

    private TestBoar() {
    }

    public static synchronized Function<ClientLogin, Processor> start() throws IOException {
        if (processors == null) {
            processors = BoariumBoar.start(Files.createTempDirectory(Files.createDirectories(Path.of("build")), "boar"));
        }
        return processors;
    }
}
