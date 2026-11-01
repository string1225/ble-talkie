package cn.sunnystring.bletalkie.audio;

import android.media.*;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.NoiseSuppressor;
import android.os.Process;
import android.os.SystemClock;
import cn.sunnystring.bletalkie.core.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/** Capture and playback never wait on network or Bluetooth I/O. */
public final class AudioEngine implements AutoCloseable {
    public interface Sender { void send(byte[] opus); }
    private final Map<UUID, Source> sources = new ConcurrentHashMap<>();
    private volatile boolean running, muted;
    private volatile long decodedFrames, decodedEnergy;
    private Set<UUID> allowed = Collections.emptySet();
    private AudioRecord record;
    private AudioTrack track;
    private Thread captureThread, playbackThread;
    private AcousticEchoCanceler echo;
    private NoiseSuppressor noise;

    // The foreground-service entry point checks RECORD_AUDIO before starting this engine.
    @android.annotation.SuppressLint("MissingPermission")
    public synchronized void start(Sender sender, Consumer<String> status, Consumer<String> failure) {
        if (running) return;
        try {
            int inputSize = Math.max(2560, AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT));
            record = new AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                .setAudioFormat(new AudioFormat.Builder().setSampleRate(16000).setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build()).setBufferSizeInBytes(inputSize).build();
            int outputSize = Math.max(2560, AudioTrack.getMinBufferSize(16000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT));
            track = new AudioTrack.Builder().setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(new AudioFormat.Builder().setSampleRate(16000).setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build()).setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(outputSize).build();
            if (record.getState() != AudioRecord.STATE_INITIALIZED || track.getState() != AudioTrack.STATE_INITIALIZED)
                throw new IllegalStateException("无法初始化 16 kHz 通话音频");
            if (AcousticEchoCanceler.isAvailable()) { echo = AcousticEchoCanceler.create(record.getAudioSessionId()); if (echo != null) echo.setEnabled(true); }
            if (NoiseSuppressor.isAvailable()) { noise = NoiseSuppressor.create(record.getAudioSessionId()); if (noise != null) noise.setEnabled(true); }
            record.startRecording();
            track.play();
            running = true;
            final AudioRecord activeRecord = record;
            final AudioTrack activeTrack = track;
            captureThread = new Thread(() -> capture(activeRecord, sender, failure), "talkie-microphone");
            playbackThread = new Thread(() -> playback(activeTrack), "talkie-playback");
            captureThread.start(); playbackThread.start();
            AudioRouting.OnRoutingChangedListener changed = routing -> reportRoute(activeRecord, activeTrack, status);
            android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
            record.addOnRoutingChangedListener(changed, main);
            track.addOnRoutingChangedListener(changed, main);
            reportRoute(activeRecord, activeTrack, status);
            main.postDelayed(() -> { if (running && record == activeRecord) reportRoute(activeRecord, activeTrack, status); }, 700);
        } catch (Exception error) {
            close(); failure.accept("音频启动失败：" + error.getMessage());
        }
    }
    public void setMuted(boolean muted) { this.muted = muted; }
    public boolean isRunning() { return running; }
    public long decodedFrames() { return decodedFrames; }
    public long decodedEnergy() { return decodedEnergy; }
    public int decodedSources() { return sources.size(); }
    public synchronized void retainMembers(Set<UUID> members) {
        allowed = new HashSet<>(members);
        for (Map.Entry<UUID, Source> entry : sources.entrySet()) {
            if (!members.contains(entry.getKey()) && sources.remove(entry.getKey(), entry.getValue())) entry.getValue().close();
        }
    }
    public synchronized void receive(AudioPacket packet) {
        if (!running || !allowed.contains(packet.senderId)) return;
        Source source = sources.get(packet.senderId);
        if (source == null && sources.size() < 3) {
            source = new Source();
            Source existing = sources.putIfAbsent(packet.senderId, source);
            if (existing != null) { source.close(); source = existing; }
        }
        if (source != null) source.offer(packet);
    }
    private void reportRoute(AudioRecord input, AudioTrack output, Consumer<String> status) {
        if (!running || record != input || track != output) return;
        AudioDeviceInfo microphone = input.getRoutedDevice(), speaker = output.getRoutedDevice();
        status.accept("麦克风：" + (microphone == null ? "路由建立中" : microphone.getProductName())
            + " · 播放：" + (speaker == null ? "路由建立中" : speaker.getProductName()));
    }
    private void capture(AudioRecord input, Sender sender, Consumer<String> failure) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);
        try (OpusCodec.Encoder encoder = new OpusCodec.Encoder()) {
            short[] pcm = new short[320];
            while (running) {
                int offset = 0;
                while (running && offset < pcm.length) {
                    int count = input.read(pcm, offset, pcm.length - offset, AudioRecord.READ_BLOCKING);
                    if (count < 0) throw new IllegalStateException("麦克风读取失败 (" + count + ")");
                    offset += count;
                }
                if (running && !muted && offset == 320) sender.send(encoder.encode(pcm));
            }
        } catch (Exception error) { if (running) failure.accept("音频采集已停止：" + error.getMessage()); }
    }
    private void playback(AudioTrack output) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);
        long nextTick = SystemClock.elapsedRealtimeNanos();
        while (running) {
            long remaining = nextTick - SystemClock.elapsedRealtimeNanos();
            if (remaining > 0) java.util.concurrent.locks.LockSupport.parkNanos(remaining);
            if (!running) break;
            long now = SystemClock.elapsedRealtime();
            short[][] decoded = new short[3][];
            int i = 0;
            for (Source source : sources.values()) { if (i < 3) decoded[i++] = source.next(now); }
            short[] mixed = AudioMath.mix(decoded, 320);
            int offset = 0;
            while (running && offset < mixed.length) {
                int count = output.write(mixed, offset, mixed.length - offset, AudioTrack.WRITE_BLOCKING);
                if (count <= 0) break;
                offset += count;
            }
            nextTick += 20_000_000L;
            long finished = SystemClock.elapsedRealtimeNanos();
            if (finished - nextTick > 40_000_000L) nextTick = finished;
        }
    }
    @Override public synchronized void close() {
        running = false;
        if (record != null) { try { record.stop(); } catch (IllegalStateException ignored) {} }
        if (track != null) { try { track.pause(); track.flush(); } catch (IllegalStateException ignored) {} }
        join(captureThread); join(playbackThread);
        captureThread = null; playbackThread = null;
        if (record != null) { record.release(); record = null; }
        if (track != null) { track.release(); track = null; }
        if (echo != null) { echo.release(); echo = null; }
        if (noise != null) { noise.release(); noise = null; }
        for (Source source : sources.values()) source.close();
        sources.clear();
    }
    private static void join(Thread thread) {
        if (thread != null && thread != Thread.currentThread()) {
            try { thread.join(700); } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
        }
    }
    private final class Source implements AutoCloseable {
        private final VoiceJitterBuffer jitter = new VoiceJitterBuffer();
        private OpusCodec.Decoder decoder;
        private long epoch;
        synchronized void offer(AudioPacket packet) {
            if (decoder == null || packet.epoch != epoch) {
                if (decoder != null) decoder.close();
                decoder = new OpusCodec.Decoder(); epoch = packet.epoch;
            }
            jitter.offer(packet, SystemClock.elapsedRealtime());
        }
        synchronized short[] next(long now) {
            byte[] bytes = jitter.take(now);
            if (decoder == null || bytes == null) return null;
            try {
                short[] pcm = decoder.decode(bytes.length == 0 ? null : bytes);
                if (bytes.length > 0) { decodedFrames++; for (short sample : pcm) decodedEnergy += Math.abs((int) sample); }
                return pcm;
            }
            catch (IllegalStateException invalidFrame) { return null; }
        }
        @Override public synchronized void close() { if (decoder != null) decoder.close(); decoder = null; }
    }
}
