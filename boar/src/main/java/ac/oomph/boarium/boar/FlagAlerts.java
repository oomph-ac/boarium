package ac.oomph.boarium.boar;

import ac.boar.anticheat.check.api.Check;
import ac.boar.anticheat.player.BoarPlayer;
import ac.boar.anticheat.violation.Violation;
import ac.boar.anticheat.violation.ViolationListener;
import ac.oomph.boarium.client.Identity;
import ac.oomph.boarium.flag.Flag;
import ac.oomph.boarium.flag.FlagSink;
import ac.oomph.boarium.session.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.IntFunction;
import java.util.function.LongSupplier;

/**
 * Sends each of Boar's flags to boarium's flag sinks, each with its own limit a minute for each player and check. Boar still counts and acts on every flag.
 */
final class FlagAlerts implements ViolationListener {
    private static final Logger LOG = LoggerFactory.getLogger(FlagAlerts.class);
    static final long MINUTE = TimeUnit.MINUTES.toNanos(1);

    private record Output(FlagSink sink, ConsoleAlerts.Limiter limiter, AtomicBoolean warned) {
    }

    private final List<Output> outputs = new CopyOnWriteArrayList<>();

    // limit is how many flags a minute, for each player and check, the sink gets, or 0 for all. The returned task removes the sink.
    Runnable add(FlagSink sink, int limit) {
        return this.add(sink, limit, System::nanoTime);
    }

    Runnable add(FlagSink sink, int limit, LongSupplier clock) {
        if (limit < 0) {
            throw new IllegalArgumentException("limit must be 0 or more, got " + limit);
        }
        Output output = new Output(sink, new ConsoleAlerts.Limiter(limit, MINUTE, clock), new AtomicBoolean());
        this.outputs.add(output);
        return () -> this.outputs.remove(output);
    }

    @Override
    public void onViolation(Violation violation) {
        if (this.outputs.isEmpty()) {
            return;
        }
        BoarPlayer player = violation.player();
        this.send(player, violation.check(), () -> player.getSession().isClosed(), held -> flag(violation, held));
    }

    // Gives each sink whose limit lets it through the flag, with the count its limit held back.
    void send(Object player, Object check, BooleanSupplier left, IntFunction<Flag> flag) {
        for (Output output : this.outputs) {
            int held = output.limiter().pass(player, check, left);
            if (held < 0) {
                continue;
            }
            try {
                output.sink().accept(flag.apply(held));
            } catch (RuntimeException e) {
                // A sink runs on the player's event loop, which must not see what it throws; warn once for each sink, as it would most likely throw at every flag.
                if (output.warned().compareAndSet(false, true)) {
                    LOG.warn("the flag sink {} threw, and its later failures are not logged", output.sink(), e);
                }
            }
        }
    }

    // Drops the counts of a player that has left.
    void forget(Object player) {
        for (Output output : this.outputs) {
            output.limiter().forget(player);
        }
    }

    boolean tracks(Object player) {
        return this.outputs.stream().anyMatch(output -> output.limiter().tracks(player));
    }

    private static Flag flag(Violation violation, int held) {
        Session session = ((BoariumPlayerManager.BoariumNetworkSession) violation.player().getSession()).session();
        Identity identity = session.client().identity();
        Check check = violation.check();
        String details = violation.verbose() == null ? "" : violation.verbose().lines().findFirst().map(ConsoleAlerts::plain).orElse("").strip();
        return new Flag(Instant.now(), session.serverAddress(), identity.displayName(), identity.xuid(), identity.uuid(), check.name(), check.type(), check.experimental(),
                violation.vl(), details, !violation.player().disableMitigations(), held);
    }
}
