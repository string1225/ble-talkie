package cn.sunnystring.bletalkie.core;

import org.junit.Test;
import java.io.*;
import static org.junit.Assert.*;

public class FramingAndMixTest {
    @Test public void preservesAdjacentBluetoothMessages() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        BluetoothFraming.write(output, 1, new byte[]{4, 5});
        BluetoothFraming.write(output, 2, new byte[]{6});
        ByteArrayInputStream input = new ByteArrayInputStream(output.toByteArray());
        BluetoothFraming.Frame first = BluetoothFraming.read(input);
        assertEquals(1, first.kind);
        assertArrayEquals(new byte[]{4, 5}, first.payload);
        assertEquals(2, BluetoothFraming.read(input).kind);
    }
    @Test public void rejectsOversizeBeforeAllocating() {
        byte[] prefix = {0, 1, 0, 0};
        assertThrows(IOException.class, () -> BluetoothFraming.read(new ByteArrayInputStream(prefix)));
        assertThrows(IOException.class, () -> BluetoothFraming.read(new ByteArrayInputStream(new byte[]{0,0,0,2,9,1})));
    }
    @Test public void mixingSaturatesBothDirections() {
        short[] mixed = AudioMath.mix(new short[][]{{30000, -30000}, {20000, -20000}}, 2);
        assertArrayEquals(new short[]{32767, -32768}, mixed);
    }
}
