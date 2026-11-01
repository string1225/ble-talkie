package cn.sunnystring.bletalkie.core;

import java.io.*;

public final class BluetoothFraming {
    public static final int CONTROL = 1, AUDIO = 2, MAX_FRAME = 16384;
    private BluetoothFraming() {}
    public static final class Frame {
        public final int kind;
        public final byte[] payload;
        Frame(int kind, byte[] payload) { this.kind = kind; this.payload = payload; }
    }
    public static Frame read(InputStream input) throws IOException {
        DataInputStream stream = new DataInputStream(input);
        int size = stream.readInt();
        if (size < 2 || size > MAX_FRAME) throw new IOException("Invalid Bluetooth frame length");
        int kind = stream.readUnsignedByte();
        if (kind != CONTROL && kind != AUDIO) throw new IOException("Unsupported Bluetooth message");
        byte[] bytes = new byte[size - 1];
        stream.readFully(bytes);
        return new Frame(kind, bytes);
    }
    public static void write(OutputStream output, int kind, byte[] bytes) throws IOException {
        if ((kind != CONTROL && kind != AUDIO) || bytes == null || bytes.length < 1 || bytes.length >= MAX_FRAME)
            throw new IOException("Invalid Bluetooth message");
        synchronized (output) {
            DataOutputStream stream = new DataOutputStream(output);
            stream.writeInt(bytes.length + 1);
            stream.writeByte(kind);
            stream.write(bytes);
            stream.flush();
        }
    }
}
