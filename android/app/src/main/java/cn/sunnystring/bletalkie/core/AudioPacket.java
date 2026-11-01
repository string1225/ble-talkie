package cn.sunnystring.bletalkie.core;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Objects;
import java.util.UUID;

/** Shared with the Go relay; all multibyte fields use network byte order. */
public final class AudioPacket {
    public static final int HEADER_SIZE = 58;
    public static final int MAX_PAYLOAD = 1275;
    private static final int MAGIC = 0x42544b31;
    public final UUID roomId;
    public final UUID senderId;
    public final long epoch;
    public final int sequence;
    public final long timestamp;
    public final byte[] payload;

    public AudioPacket(UUID roomId, UUID senderId, long epoch, int sequence, long timestamp, byte[] payload) {
        this.roomId = Objects.requireNonNull(roomId);
        this.senderId = Objects.requireNonNull(senderId);
        if (payload == null || payload.length < 1 || payload.length > MAX_PAYLOAD)
            throw new IllegalArgumentException("Invalid Opus payload length");
        if (timestamp < 0) throw new IllegalArgumentException("Negative media timestamp");
        this.epoch = epoch;
        this.sequence = sequence;
        this.timestamp = timestamp;
        this.payload = payload.clone();
    }

    public byte[] encode() {
        ByteBuffer buffer = ByteBuffer.allocate(HEADER_SIZE + payload.length).order(ByteOrder.BIG_ENDIAN);
        buffer.putInt(MAGIC);
        putUuid(buffer, roomId);
        putUuid(buffer, senderId);
        buffer.putLong(epoch).putInt(sequence).putLong(timestamp).putShort((short) payload.length).put(payload);
        return buffer.array();
    }

    public static AudioPacket decode(byte[] bytes) {
        if (bytes == null || bytes.length <= HEADER_SIZE || bytes.length > HEADER_SIZE + MAX_PAYLOAD)
            throw new IllegalArgumentException("Invalid audio packet size");
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
        if (buffer.getInt() != MAGIC) throw new IllegalArgumentException("Unsupported audio protocol");
        UUID room = getUuid(buffer);
        UUID sender = getUuid(buffer);
        long epoch = buffer.getLong();
        int sequence = buffer.getInt();
        long timestamp = buffer.getLong();
        int size = Short.toUnsignedInt(buffer.getShort());
        if (size != buffer.remaining()) throw new IllegalArgumentException("Audio length mismatch");
        byte[] payload = new byte[size];
        buffer.get(payload);
        return new AudioPacket(room, sender, epoch, sequence, timestamp, payload);
    }

    private static void putUuid(ByteBuffer buffer, UUID id) {
        buffer.putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits());
    }
    private static UUID getUuid(ByteBuffer buffer) { return new UUID(buffer.getLong(), buffer.getLong()); }
}
