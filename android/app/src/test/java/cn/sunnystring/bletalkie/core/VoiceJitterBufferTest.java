package cn.sunnystring.bletalkie.core;
import org.junit.Test;
import java.util.UUID;
import static org.junit.Assert.*;

public class VoiceJitterBufferTest {
    private final UUID room = UUID.randomUUID(), member = UUID.randomUUID();
    private AudioPacket frame(long epoch, int sequence) {
        return new AudioPacket(room, member, epoch, sequence, 0, new byte[]{(byte) sequence});
    }
    @Test public void reordersDuringInitialPlayoutDelay() {
        VoiceJitterBuffer buffer = new VoiceJitterBuffer();
        buffer.offer(frame(1, 2), 0);
        buffer.offer(frame(1, 1), 10);
        assertNull(buffer.take(39));
        assertArrayEquals(new byte[]{1}, buffer.take(40));
        assertArrayEquals(new byte[]{2}, buffer.take(60));
    }
    @Test public void bufferIsBoundedAndNewEpochDropsOldAudio() {
        VoiceJitterBuffer buffer = new VoiceJitterBuffer();
        for (int i = 0; i < 30; i++) buffer.offer(frame(1, i), i);
        assertTrue(buffer.size() <= 8);
        buffer.offer(frame(2, 0), 100);
        assertNull(buffer.take(130));
        assertArrayEquals(new byte[]{0}, buffer.take(140));
    }
    @Test public void silenceDoesNotReplayOldPackets() {
        VoiceJitterBuffer buffer = new VoiceJitterBuffer();
        buffer.offer(frame(1, 0), 0);
        assertArrayEquals(new byte[]{0}, buffer.take(40));
        assertNull(buffer.take(1000));
        buffer.offer(frame(1, 1), 1200);
        assertNull(buffer.take(1230));
        assertArrayEquals(new byte[]{1}, buffer.take(1240));
    }
    @Test public void fastOutputPollingDoesNotConsumeFutureSequences() {
        VoiceJitterBuffer buffer = new VoiceJitterBuffer();
        buffer.offer(frame(1, 0), 0);
        assertArrayEquals(new byte[]{0}, buffer.take(40));
        assertNull(buffer.take(41));
        buffer.offer(frame(1, 1), 45);
        assertNull(buffer.take(59));
        assertArrayEquals(new byte[]{1}, buffer.take(60));
    }
    @Test public void rebufferAfterNetworkLatencyStepPreservesContinuingSpeech() {
        VoiceJitterBuffer buffer = new VoiceJitterBuffer();
        for (int i = 0; i < 5; i++) {
            buffer.offer(frame(1, i), i * 20);
            if (i >= 2) buffer.take(i * 20);
        }
        buffer.take(100); buffer.take(120);
        buffer.take(140); buffer.take(160); buffer.take(180);
        buffer.offer(frame(1, 5), 200);
        buffer.offer(frame(1, 6), 220);
        assertNull(buffer.take(220));
        assertArrayEquals(new byte[]{5}, buffer.take(240));
        assertArrayEquals(new byte[]{6}, buffer.take(260));
    }
}
