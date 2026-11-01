package cn.sunnystring.bletalkie.audio;

/** Fixed 16 kHz mono, 20 ms frames. Each decoder belongs to exactly one source stream. */
public final class OpusCodec {
    static { System.loadLibrary("talkie_opus"); }
    private OpusCodec() {}
    public static final class Encoder implements AutoCloseable {
        private long handle = createEncoder();
        public synchronized byte[] encode(short[] pcm) {
            if (handle == 0) throw new IllegalStateException("Encoder closed");
            if (pcm == null || pcm.length != 320) throw new IllegalArgumentException("Expected 320 PCM samples");
            return encodeFrame(handle, pcm);
        }
        @Override public synchronized void close() { if (handle != 0) { destroyEncoder(handle); handle = 0; } }
    }
    public static final class Decoder implements AutoCloseable {
        private long handle = createDecoder();
        public synchronized short[] decode(byte[] packet) {
            if (handle == 0) throw new IllegalStateException("Decoder closed");
            if (packet != null && (packet.length == 0 || packet.length > 1275))
                throw new IllegalArgumentException("Invalid Opus packet");
            return decodeFrame(handle, packet);
        }
        @Override public synchronized void close() { if (handle != 0) { destroyDecoder(handle); handle = 0; } }
    }
    private static native long createEncoder();
    private static native long createDecoder();
    private static native byte[] encodeFrame(long handle, short[] pcm);
    private static native short[] decodeFrame(long handle, byte[] packet);
    private static native void destroyEncoder(long handle);
    private static native void destroyDecoder(long handle);
}
