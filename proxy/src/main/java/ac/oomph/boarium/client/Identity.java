package ac.oomph.boarium.client;

import java.util.UUID;

/**
 * The player a client logged in as, with an empty {@code xuid} when Xbox Live did not sign it and an empty {@code titleId} when the login did not carry one.
 */
public record Identity(String displayName, UUID uuid, String xuid, String titleId) {
}
