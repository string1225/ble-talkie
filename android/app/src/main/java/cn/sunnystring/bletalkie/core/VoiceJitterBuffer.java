package cn.sunnystring.bletalkie.core;

import java.util.TreeMap;

/** A short, bounded playout buffer; a long interruption starts a fresh playout window. */
public final class VoiceJitterBuffer {
    private final TreeMap<Long, byte[]> frames = new TreeMap<>();
    private long epoch, due, lastArrival;
    private int expected, lastRealSequence, consecutiveMisses;
    private boolean initialized, started, hasRealSequence;

    public synchronized void offer(AudioPacket packet, long now) {
        boolean newStream = !initialized || packet.epoch != epoch || now - lastArrival > 200;
        boolean recover = consecutiveMisses >= 3 && (!hasRealSequence || packet.sequence - lastRealSequence > 0);
        if (newStream || recover) {
            frames.clear();
            epoch = packet.epoch;
            expected = packet.sequence;
            due = now + 40;
            started = false;
            initialized = true;
            consecutiveMisses = 0;
            if (newStream) hasRealSequence = false;
        }
        if (started && packet.sequence - expected < 0) return;
        if (!started && packet.sequence - expected < 0) expected = packet.sequence;
        frames.put(Integer.toUnsignedLong(packet.sequence), packet.payload);
        lastArrival = now;
        while (frames.size() > 8) {
            int removed = frames.pollFirstEntry().getKey().intValue();
            if (removed - expected >= 0) expected = removed + 1;
        }
    }

    /** null = initial/idle silence; zero length = one lost frame for Opus PLC. */
    public synchronized byte[] take(long now) {
        if (!initialized || now < due || now - lastArrival > 200) return null;
        started = true;
        due += 20;
        byte[] frame = frames.remove(Integer.toUnsignedLong(expected++));
        if (frame == null) { consecutiveMisses++; return new byte[0]; }
        consecutiveMisses = 0; lastRealSequence = expected - 1; hasRealSequence = true;
        return frame;
    }
    public synchronized int size() { return frames.size(); }
}
