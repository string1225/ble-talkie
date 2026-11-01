package cn.sunnystring.bletalkie.core;

import java.util.LinkedHashSet;

/** Bounded across all sources, so changing stream epochs cannot grow memory indefinitely. */
public final class PacketHistory {
    private final int capacity;
    private final LinkedHashSet<String> seen = new LinkedHashSet<>();
    public PacketHistory(int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("Empty packet history");
        this.capacity = capacity;
    }
    public synchronized boolean accept(AudioPacket packet) {
        String key = packet.roomId + ":" + packet.senderId + ":" + packet.epoch + ":" + packet.sequence;
        if (!seen.add(key)) return false;
        if (seen.size() > capacity) seen.remove(seen.iterator().next());
        return true;
    }
    public synchronized int size() { return seen.size(); }
    public synchronized void clear() { seen.clear(); }
}
