package cn.sunnystring.bletalkie;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.Bundle;
import cn.sunnystring.bletalkie.audio.OpusCodec;

/** Runs on a real device without opening a microphone or transmitting audio. */
public final class CodecProbeInstrumentation extends Instrumentation {
    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try (OpusCodec.Encoder encoder = new OpusCodec.Encoder(); OpusCodec.Decoder decoder = new OpusCodec.Decoder()) {
            long energy = 0;
            int totalBytes = 0;
            for (int frame = 0; frame < 50; frame++) {
                short[] pcm = new short[320];
                for (int i = 0; i < pcm.length; i++)
                    pcm[i] = (short) (Math.sin((frame * 320 + i) * 2 * Math.PI * 440 / 16000) * 10000);
                byte[] encoded = encoder.encode(pcm);
                if (encoded.length == 0 || encoded.length > 1275) throw new AssertionError("Opus packet size");
                short[] decoded = decoder.decode(encoded);
                if (decoded.length != 320) throw new AssertionError("Opus sample count");
                for (short sample : decoded) energy += Math.abs((int) sample);
                totalBytes += encoded.length;
            }
            if (energy < 100000) throw new AssertionError("Decoded audio is silent");
            result.putString("codec", "PASS: 50 real native Opus frames");
            result.putInt("encodedBytes", totalBytes);
            result.putLong("decodedEnergy", energy);
            finish(Activity.RESULT_OK, result);
        } catch (Throwable error) {
            result.putString("codec", "FAIL: " + error);
            finish(Activity.RESULT_CANCELED, result);
        }
    }
}
