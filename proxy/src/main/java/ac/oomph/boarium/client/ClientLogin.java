package ac.oomph.boarium.client;

import org.cloudburstmc.protocol.bedrock.BedrockServerSession;
import org.cloudburstmc.protocol.bedrock.util.ChainValidationResult;

/**
 * A client that finished logging in to the proxy.
 * {@code clientData} is the JSON body of the client's signed client data, and {@code xboxAuthenticated} says whether Xbox Live signed its identity.
 */
public record ClientLogin(BedrockServerSession session, ChainValidationResult.IdentityData identity, byte[] clientData, boolean xboxAuthenticated, boolean clientCacheSupported) {
}
