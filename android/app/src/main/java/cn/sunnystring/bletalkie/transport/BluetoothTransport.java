package cn.sunnystring.bletalkie.transport;

import android.annotation.SuppressLint;
import android.bluetooth.*;
import android.content.*;
import android.os.*;
import cn.sunnystring.bletalkie.core.BluetoothFraming;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

@SuppressLint("MissingPermission")
public final class BluetoothTransport implements AutoCloseable {
    public static final UUID SERVICE_UUID = UUID.fromString("c47bdc9e-2fb7-4c63-a458-a8bf7e24e6ce");
    public interface Listener {
        void onPeer(Peer peer);
        void onControl(Peer peer, JSONObject message);
        void onAudio(Peer peer, byte[] message);
        void onClosed(Peer peer, String reason);
        void onDevice(String address, String name);
        void onStatus(String message);
    }
    private final Context context;
    private final Listener listener;
    private final BluetoothAdapter adapter;
    private final Set<Peer> peers = ConcurrentHashMap.newKeySet();
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
    private final AtomicBoolean connecting = new AtomicBoolean();
    private volatile long connectRun;
    private volatile BluetoothServerSocket server;
    private volatile BluetoothSocket connectingSocket;
    private volatile boolean closed;
    private boolean receiverRegistered;
    private final BroadcastReceiver discovery = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent intent) {
            if (!BluetoothDevice.ACTION_FOUND.equals(intent.getAction())) return;
            BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
            if (device != null) reportDevice(device);
        }
    };

    public BluetoothTransport(Context context, Listener listener) {
        this.context = context; this.listener = listener;
        BluetoothManager manager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        adapter = manager != null ? manager.getAdapter() : null;
        timer.scheduleAtFixedRate(() -> {
            long now = SystemClock.elapsedRealtime();
            for (Peer peer : peers) {
                if (now - peer.lastRead > 25000) peer.close("蓝牙连接无响应");
                else peer.control(cn.sunnystring.bletalkie.core.Json.object("type", "ping"));
            }
        }, 5, 5, TimeUnit.SECONDS);
    }
    public boolean available() { try { return adapter != null && adapter.isEnabled(); } catch (SecurityException denied) { return false; } }
    public synchronized void listen() {
        if (closed || server != null) return;
        try {
            if (!available()) { listener.onStatus("请开启蓝牙后让队友搜索"); return; }
            server = adapter.listenUsingRfcommWithServiceRecord("BleTalkie", SERVICE_UUID);
            final BluetoothServerSocket active = server;
            new Thread(() -> {
                try {
                    while (!closed && server == active) accept(active.accept(), false);
                } catch (IOException error) { if (!closed && server == active) listener.onStatus("蓝牙监听已停止"); }
                finally { synchronized (BluetoothTransport.this) { if (server == active) server = null; } }
            }, "talkie-bt-listen").start();
        } catch (IOException | SecurityException error) { listener.onStatus("无法开启蓝牙监听：" + error.getMessage()); }
    }
    public synchronized void stopListening() {
        BluetoothServerSocket active = server; server = null;
        if (active != null) try { active.close(); } catch (IOException ignored) {}
    }
    public void discover() {
        try {
            if (!available()) { listener.onStatus("请先开启蓝牙"); return; }
            if (!receiverRegistered) {
                IntentFilter filter = new IntentFilter(BluetoothDevice.ACTION_FOUND);
                if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(discovery, filter, Context.RECEIVER_EXPORTED);
                else context.registerReceiver(discovery, filter);
                receiverRegistered = true;
            }
            for (BluetoothDevice device : adapter.getBondedDevices()) reportDevice(device);
            adapter.cancelDiscovery();
            if (!adapter.startDiscovery()) listener.onStatus("蓝牙搜索未启动，请检查权限");
            else listener.onStatus("正在搜索附近手机；请让队长点击“可被发现”");
        } catch (SecurityException error) { listener.onStatus("需要附近设备权限才能找人"); }
    }
    private void reportDevice(BluetoothDevice device) {
        try {
            if (device.getType() == BluetoothDevice.DEVICE_TYPE_LE) return;
            BluetoothClass kind = device.getBluetoothClass();
            if (kind != null && kind.getMajorDeviceClass() != BluetoothClass.Device.Major.PHONE
                && kind.getMajorDeviceClass() != BluetoothClass.Device.Major.UNCATEGORIZED) return;
            String name = device.getName();
            listener.onDevice(device.getAddress(), name == null ? "附近手机" : name);
        } catch (SecurityException ignored) { listener.onStatus("需要附近设备权限"); }
    }
    public synchronized void connect(String address) {
        if (!connecting.compareAndSet(false, true)) { listener.onStatus("正在连接另一台手机，请稍候"); return; }
        final long run = ++connectRun;
        new Thread(() -> {
            ScheduledFuture<?> timeout = null;
            BluetoothSocket attempted = null;
            boolean accepted = false;
            try {
                if (closed || !available()) throw new IOException("蓝牙未开启");
                adapter.cancelDiscovery();
                BluetoothSocket socket = adapter.getRemoteDevice(address).createRfcommSocketToServiceRecord(SERVICE_UUID);
                attempted = socket;
                synchronized (BluetoothTransport.this) {
                    if (run != connectRun || closed) return;
                    connectingSocket = socket;
                }
                timeout = timer.schedule(() -> { try { socket.close(); } catch (IOException ignored) {} }, 20, TimeUnit.SECONDS);
                socket.connect();
                timeout.cancel(false);
                synchronized (BluetoothTransport.this) {
                    if (run != connectRun || closed) return;
                    connectingSocket = null;
                    accept(socket, true); accepted = true;
                }
            } catch (IOException | RuntimeException error) {
                if (run == connectRun && !closed) listener.onStatus("蓝牙连接失败：请确认对方已创建队伍并打开本 App");
            } finally {
                if (timeout != null) timeout.cancel(false);
                if (!accepted && attempted != null) try { attempted.close(); } catch (IOException ignored) {}
                synchronized (BluetoothTransport.this) { if (run == connectRun) { connectingSocket = null; connecting.set(false); } }
            }
        }, "talkie-bt-connect").start();
    }
    public synchronized void cancelConnect() {
        ++connectRun; connecting.set(false);
        BluetoothSocket pending = connectingSocket; connectingSocket = null;
        if (pending != null) try { pending.close(); } catch (IOException ignored) {}
    }
    private synchronized void accept(BluetoothSocket socket, boolean outgoing) throws IOException {
        if (closed || peers.size() >= 3) { socket.close(); throw new IOException("蓝牙连接数量已满"); }
        Peer peer = new Peer(socket, outgoing);
        peers.add(peer);
        peer.start(); listener.onPeer(peer);
    }
    public void disconnectAll() { disconnectAllExcept(null); }
    public void closePendingOutgoing() { for (Peer peer : peers) if (peer.outgoing && peer.memberId == null) peer.close("已取消此前的网关连接"); }
    public void disconnectAllExcept(Peer keep) { for (Peer peer : peers) if (peer != keep) peer.close("已断开蓝牙连接"); }
    public void finishAll(JSONObject message, String reason) { for (Peer peer : peers) peer.finish(message, reason); }
    @Override public void close() {
        closed = true; cancelConnect(); stopListening(); disconnectAll(); timer.shutdownNow();
        BluetoothSocket pending = connectingSocket;
        if (pending != null) try { pending.close(); } catch (IOException ignored) {}
        try { if (adapter != null) adapter.cancelDiscovery(); } catch (SecurityException ignored) {}
        if (receiverRegistered) { context.unregisterReceiver(discovery); receiverRegistered = false; }
    }
    private static final class Message {
        final int kind; final byte[] bytes; final long queued = SystemClock.elapsedRealtime();
        final String closeReason;
        Message(int kind, byte[] bytes) { this(kind, bytes, null); }
        Message(int kind, byte[] bytes, String closeReason) { this.kind = kind; this.bytes = bytes; this.closeReason = closeReason; }
    }
    public final class Peer {
        public final String key = UUID.randomUUID().toString();
        public final boolean outgoing;
        public volatile String memberId, nickname;
        public volatile long generation;
        private final BluetoothSocket socket;
        private final InputStream input;
        private final OutputStream output;
        private final AtomicBoolean open = new AtomicBoolean(true);
        private final AtomicBoolean finishing = new AtomicBoolean();
        private final BlockingQueue<Message> control = new ArrayBlockingQueue<>(16), audio = new ArrayBlockingQueue<>(8);
        private volatile long lastRead = SystemClock.elapsedRealtime();
        Peer(BluetoothSocket socket, boolean outgoing) throws IOException {
            this.socket = socket; this.outgoing = outgoing;
            input = socket.getInputStream(); output = socket.getOutputStream();
        }
        public boolean isOpen() { return open.get(); }
        public void control(JSONObject message) {
            if (open.get() && !finishing.get() && !control.offer(new Message(BluetoothFraming.CONTROL, message.toString().getBytes(StandardCharsets.UTF_8)))) close("蓝牙控制连接拥塞");
        }
        public void finish(JSONObject message, String reason) {
            if (!open.get() || !finishing.compareAndSet(false, true)) return;
            audio.clear();
            if (!control.offer(new Message(BluetoothFraming.CONTROL, message.toString().getBytes(StandardCharsets.UTF_8), reason))) close(reason);
            else timer.schedule(() -> close(reason), 1500, TimeUnit.MILLISECONDS);
        }
        public void audio(byte[] bytes) {
            if (!open.get() || finishing.get()) return;
            Message frame = new Message(BluetoothFraming.AUDIO, bytes);
            if (!audio.offer(frame)) { audio.poll(); audio.offer(frame); }
        }
        void start() {
            new Thread(() -> {
                try {
                    while (open.get()) {
                        BluetoothFraming.Frame frame = BluetoothFraming.read(input);
                        lastRead = SystemClock.elapsedRealtime();
                        if (frame.kind == BluetoothFraming.CONTROL) listener.onControl(this, new JSONObject(new String(frame.payload, StandardCharsets.UTF_8)));
                        else listener.onAudio(this, frame.payload);
                    }
                } catch (Exception error) { close("蓝牙连接已断开"); }
            }, "talkie-bt-read").start();
            new Thread(() -> {
                try {
                    while (open.get()) {
                        Message message = control.poll();
                        if (message == null) message = audio.poll(30, TimeUnit.MILLISECONDS);
                        if (message == null || (message.kind == BluetoothFraming.AUDIO && SystemClock.elapsedRealtime() - message.queued > 200)) continue;
                        BluetoothFraming.write(output, message.kind, message.bytes);
                        if (message.closeReason != null) { close(message.closeReason); break; }
                    }
                } catch (IOException | InterruptedException error) { close("蓝牙发送已停止"); }
            }, "talkie-bt-write").start();
        }
        public void close(String reason) {
            if (!open.compareAndSet(true, false)) return;
            peers.remove(this);
            try { socket.close(); } catch (IOException ignored) {}
            control.clear(); audio.clear();
            listener.onClosed(this, reason);
        }
    }
}
