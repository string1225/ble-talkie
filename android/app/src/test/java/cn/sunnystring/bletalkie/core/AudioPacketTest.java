package cn.sunnystring.bletalkie.core;

import org.junit.Test;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.UUID;
import static org.junit.Assert.*;

public class AudioPacketTest {
    private final UUID room = UUID.fromString("12a5b83c-0321-49ab-8245-039d35629873");
    private final UUID sender = UUID.fromString("21a5b83c-0321-49ab-8245-039d35629873");

    @Test public void matchesRelayWireContractAndRoundTrips() {
        AudioPacket packet = new AudioPacket(room, sender, 7L, 3, 120L, new byte[]{1, 2, 3});
        byte[] encoded = packet.encode();
        assertEquals(61, encoded.length);
        assertEquals("BTK1", new String(encoded, 0, 4, java.nio.charset.StandardCharsets.US_ASCII));
        ByteBuffer wire = ByteBuffer.wrap(encoded).order(ByteOrder.BIG_ENDIAN);
        assertEquals(7L, wire.getLong(36));
        assertEquals(3, wire.getInt(44));
        assertEquals(120L, wire.getLong(48));
        assertEquals(3, wire.getShort(56));
        AudioPacket result = AudioPacket.decode(encoded);
        assertEquals(room, result.roomId);
        assertEquals(sender, result.senderId);
        assertArrayEquals(packet.payload, result.payload);
    }

    @Test public void rejectsTruncatedOversizedAndTrailingData() {
        assertThrows(IllegalArgumentException.class, () -> AudioPacket.decode(new byte[57]));
        assertThrows(IllegalArgumentException.class, () -> new AudioPacket(room, sender, 1, 1, 1, new byte[1276]));
        byte[] encoded = new AudioPacket(room, sender, 1, 1, 1, new byte[]{1}).encode();
        encoded[57] = 10;
        assertThrows(IllegalArgumentException.class, () -> AudioPacket.decode(encoded));
        encoded[57] = 0;
        assertThrows(IllegalArgumentException.class, () -> AudioPacket.decode(encoded));
    }

    @Test public void deduplicationIsBoundedAndAllowsNewStreamEpoch() {
        PacketHistory history = new PacketHistory(4);
        AudioPacket first = new AudioPacket(room, sender, 1, 0, 0, new byte[]{1});
        assertTrue(history.accept(first));
        assertFalse(history.accept(first));
        assertTrue(history.accept(new AudioPacket(room, sender, 2, 0, 0, new byte[]{1})));
        for (int sequence = 1; sequence <= 10; sequence++)
            history.accept(new AudioPacket(room, sender, 2, sequence, 0, new byte[]{1}));
        assertEquals(4, history.size());
    }
}
