package ac.oomph.boarium.boar;

import ac.oomph.boarium.protocol.BedrockVersions;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodec;
import org.cloudburstmc.protocol.bedrock.packet.LevelChunkPacket;
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BoarProcessorTest {

    // Every codec has each packet Boar reads, under an ID of its own: 10 from the client and 36 from the server.
    @Test
    void decodesBoarsPacketsInEveryCodec() {
        for (BedrockVersions.Version version : BedrockVersions.SUPPORTED) {
            BedrockCodec codec = version.codec();
            BoarProcessor processor = new BoarProcessor(null, codec);
            assertEquals(10, processor.clientDecode().size(), version.names().toString());
            assertEquals(36, processor.serverDecode().size(), version.names().toString());
            assertTrue(processor.clientDecode().contains(codec.getPacketDefinition(PlayerAuthInputPacket.class).getId()));
            assertTrue(processor.serverDecode().contains(codec.getPacketDefinition(LevelChunkPacket.class).getId()));
        }
    }
}
