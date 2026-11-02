package ac.oomph.boarium.session;

import org.cloudburstmc.protocol.bedrock.packet.AddEntityPacket;
import org.cloudburstmc.protocol.bedrock.packet.AddItemEntityPacket;
import org.cloudburstmc.protocol.bedrock.packet.AddPaintingPacket;
import org.cloudburstmc.protocol.bedrock.packet.AddPlayerPacket;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket;
import org.cloudburstmc.protocol.bedrock.packet.BossEventPacket;
import org.cloudburstmc.protocol.bedrock.packet.MobEffectPacket;
import org.cloudburstmc.protocol.bedrock.packet.PlayerListPacket;
import org.cloudburstmc.protocol.bedrock.packet.RemoveEntityPacket;
import org.cloudburstmc.protocol.bedrock.packet.RemoveObjectivePacket;
import org.cloudburstmc.protocol.bedrock.packet.SetDisplayObjectivePacket;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * What a server showed the client that the next server would not know to remove: entities, boss bars, effects, player list entries and scoreboards, as spectrum-internal's tracker records them.
 * It only sees the packets the server asked the proxy to decode.
 */
final class Tracker {
    private final Set<Long> entities = new HashSet<>();
    private final Set<Long> bossBars = new HashSet<>();
    private final Set<Integer> effects = new HashSet<>();
    private final Set<UUID> players = new HashSet<>();
    private final Set<String> scoreboards = new HashSet<>();

    synchronized void observe(BedrockPacket packet) {
        switch (packet) {
            case AddEntityPacket add -> this.entities.add(add.getUniqueEntityId());
            case AddItemEntityPacket add -> this.entities.add(add.getUniqueEntityId());
            case AddPaintingPacket add -> this.entities.add(add.getUniqueEntityId());
            case AddPlayerPacket add -> this.entities.add(add.getUniqueEntityId());
            case RemoveEntityPacket remove -> this.entities.remove(remove.getUniqueEntityId());
            case BossEventPacket boss -> this.bossBars.add(boss.getBossUniqueEntityId());
            case MobEffectPacket effect when effect.getEvent() == MobEffectPacket.Event.ADD -> this.effects.add(effect.getEffectId());
            case MobEffectPacket effect when effect.getEvent() == MobEffectPacket.Event.REMOVE -> this.effects.remove(effect.getEffectId());
            case PlayerListPacket list -> list.getEntries().forEach(entry -> {
                if (list.getAction() == PlayerListPacket.Action.ADD) {
                    this.players.add(entry.getUuid());
                } else {
                    this.players.remove(entry.getUuid());
                }
            });
            case SetDisplayObjectivePacket display -> this.scoreboards.add(display.getObjectiveId());
            case RemoveObjectivePacket remove -> this.scoreboards.remove(remove.getObjectiveId());
            default -> {
            }
        }
    }

    /**
     * Sends the client what removes everything recorded, and forgets it. {@code runtimeId} is the player's own entity, which the effects are on.
     */
    void clear(Consumer<BedrockPacket> send, long runtimeId) {
        // Built under the lock and sent after it, since send runs the processor.
        List<BedrockPacket> removals = new ArrayList<>();
        synchronized (this) {
            for (long entity : this.entities) {
                RemoveEntityPacket remove = new RemoveEntityPacket();
                remove.setUniqueEntityId(entity);
                removals.add(remove);
            }
            for (long bossBar : this.bossBars) {
                BossEventPacket hide = new BossEventPacket();
                hide.setBossUniqueEntityId(bossBar);
                hide.setAction(BossEventPacket.Action.REMOVE);
                removals.add(hide);
            }
            for (int effectId : this.effects) {
                MobEffectPacket remove = new MobEffectPacket();
                remove.setRuntimeEntityId(runtimeId);
                remove.setEvent(MobEffectPacket.Event.REMOVE);
                remove.setEffectId(effectId);
                removals.add(remove);
            }
            if (!this.players.isEmpty()) {
                PlayerListPacket remove = new PlayerListPacket();
                remove.setAction(PlayerListPacket.Action.REMOVE);
                this.players.forEach(uuid -> remove.getEntries().add(new PlayerListPacket.Entry(uuid)));
                removals.add(remove);
            }
            for (String objective : this.scoreboards) {
                RemoveObjectivePacket remove = new RemoveObjectivePacket();
                remove.setObjectiveId(objective);
                removals.add(remove);
            }
            this.entities.clear();
            this.bossBars.clear();
            this.effects.clear();
            this.players.clear();
            this.scoreboards.clear();
        }
        removals.forEach(send);
    }
}
