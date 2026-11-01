package cn.sunnystring.bletalkie.audio;

import android.content.*;
import android.media.*;
import android.os.*;
import java.util.List;
import java.util.function.Consumer;

public final class AudioRoute implements AutoCloseable {
    private final Context context;
    private final AudioManager manager;
    private final Handler main = new Handler(Looper.getMainLooper());
    private AudioManager.OnCommunicationDeviceChangedListener listener;
    private BroadcastReceiver scoReceiver;
    private AudioFocusRequest focus;
    private boolean active, delivered;
    private long attempt;

    public AudioRoute(Context context) {
        this.context = context;
        manager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
    }
    public void open(Runnable ready, Consumer<String> status, Consumer<String> failure) {
        if (active) return;
        final long currentAttempt = ++attempt;
        active = true;
        delivered = false;
        manager.setMode(AudioManager.MODE_IN_COMMUNICATION);
        focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setOnAudioFocusChangeListener(change -> {
                if (currentAttempt == attempt && active && (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT))
                    fail(failure, "通话被系统音频打断，已暂停麦克风");
            }, main).build();
        if (manager.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            fail(failure, "当前音频正被其他通话占用"); return;
        }
        if (Build.VERSION.SDK_INT >= 31) {
            List<AudioDeviceInfo> devices = manager.getAvailableCommunicationDevices();
            AudioDeviceInfo selected = null;
            for (AudioDeviceInfo device : devices) {
                if (device.getType() == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || device.getType() == AudioDeviceInfo.TYPE_BLE_HEADSET) {
                    selected = device; break;
                }
            }
            if (selected == null) {
                for (AudioDeviceInfo device : devices) {
                    if (device.getType() == AudioDeviceInfo.TYPE_WIRED_HEADSET || device.getType() == AudioDeviceInfo.TYPE_USB_HEADSET) {
                        selected = device; break;
                    }
                }
            }
            if (selected == null) {
                status.accept("使用手机麦克风与扬声器");
                for (AudioDeviceInfo device : devices) if (device.getType() == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) selected = device;
            }
            final AudioDeviceInfo target = selected;
            if (target == null) { fail(failure, "没有可用的通话音频设备"); return; }
            listener = device -> {
                if (currentAttempt != attempt) return;
                if (active && device != null && device.getId() == target.getId()) {
                    status.accept("通话设备：" + device.getProductName());
                    deliver(ready);
                } else if (active && delivered) {
                    fail(failure, "通话设备已变化，已暂停麦克风，请确认耳机后重新开始");
                }
            };
            manager.addOnCommunicationDeviceChangedListener(context.getMainExecutor(), listener);
            if (!manager.setCommunicationDevice(target)) { fail(failure, "系统未接受耳机通话路由"); return; }
            AudioDeviceInfo current = manager.getCommunicationDevice();
            if (current != null && current.getId() == target.getId()) deliver(ready);
            main.postDelayed(() -> {
                if (currentAttempt == attempt && active && !delivered) fail(failure, "耳机通话通道建立超时，请重连耳机后再试");
            }, 4000);
        } else {
            boolean bluetooth = false;
            for (AudioDeviceInfo device : manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS))
                if (device.getType() == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || device.getType() == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP) bluetooth = true;
            if (!bluetooth) { manager.setSpeakerphoneOn(true); status.accept("使用手机麦克风与扬声器"); deliver(ready); return; }
            scoReceiver = new BroadcastReceiver() {
                @Override public void onReceive(Context c, Intent intent) {
                    if (currentAttempt != attempt) return;
                    int state = intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1);
                    if (state == AudioManager.SCO_AUDIO_STATE_CONNECTED && active) {
                        status.accept("蓝牙耳机通话通道已连接"); deliver(ready);
                    } else if (state == AudioManager.SCO_AUDIO_STATE_DISCONNECTED && active && delivered)
                        fail(failure, "蓝牙通话已断开，麦克风已暂停，请重连耳机后开始");
                }
            };
            context.registerReceiver(scoReceiver, new IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED));
            manager.startBluetoothSco();
            manager.setBluetoothScoOn(true);
            main.postDelayed(() -> { if (currentAttempt == attempt && active && !delivered) fail(failure, "蓝牙通话连接超时"); }, 4000);
        }
    }
    private void deliver(Runnable ready) { if (active && !delivered) { delivered = true; ready.run(); } }
    private void fail(Consumer<String> failure, String message) { close(); failure.accept(message); }
    @Override public void close() {
        if (!active) return;
        ++attempt;
        active = false;
        main.removeCallbacksAndMessages(null);
        if (Build.VERSION.SDK_INT >= 31) {
            if (listener != null) manager.removeOnCommunicationDeviceChangedListener(listener);
            listener = null;
            manager.clearCommunicationDevice();
        } else {
            manager.stopBluetoothSco(); manager.setBluetoothScoOn(false); manager.setSpeakerphoneOn(false);
        }
        if (scoReceiver != null) { context.unregisterReceiver(scoReceiver); scoReceiver = null; }
        if (focus != null) { manager.abandonAudioFocusRequest(focus); focus = null; }
        manager.setMode(AudioManager.MODE_NORMAL);
    }
}
