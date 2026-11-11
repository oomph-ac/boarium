package ac.oomph.boarium.flag;

import java.time.Instant;
import java.util.UUID;

/**
 * One of Boar's flags on a player.
 *
 * @param server       the address of the server the player was on, as boarium's config names it, such as {@code practice:19133}
 * @param xuid         the player's Xbox user ID, or empty when Xbox Live did not sign the player in
 * @param check        the check's name, such as {@code Bad Packet}
 * @param type         the check's type, such as {@code B}, or empty
 * @param vl           the player's violation level in this check
 * @param details      the first line of what Boar found, without Minecraft's colour codes, or empty
 * @param mitigations  whether Boar may act on the player, as boar_mitigations sets
 * @param heldBack     how many flags of this player and check the sink's limit held back since the last one it got
 */
public record Flag(Instant time, String server, String player, String xuid, UUID uuid, String check, String type, boolean experimental, int vl, String details,
                   boolean mitigations, int heldBack) {
}
