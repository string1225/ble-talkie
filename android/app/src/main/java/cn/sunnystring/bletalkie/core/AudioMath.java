package cn.sunnystring.bletalkie.core;

public final class AudioMath {
    private AudioMath() {}
    public static short[] mix(short[][] sources, int samples) {
        short[] output = new short[samples];
        for (int i = 0; i < samples; i++) {
            int sum = 0;
            for (short[] source : sources) if (source != null && i < source.length) sum += source[i];
            output[i] = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, sum));
        }
        return output;
    }
}
