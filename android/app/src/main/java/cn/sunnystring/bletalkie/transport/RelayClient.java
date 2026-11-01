package cn.sunnystring.bletalkie.transport;

import android.os.SystemClock;
import cn.sunnystring.bletalkie.core.Json;
import okhttp3.*;
import okio.ByteString;
import org.json.*;
import java.io.IOException;
import java.util.concurrent.*;

public final class RelayClient implements AutoCloseable {
    public static final String BASE_URL = "https://home.sunny-string.cn/ble-talkie";
    public interface Listener {
        void onRelayControl(long session, JSONObject message);
        void onRelayAudio(long session, byte[] bytes);
        void onRelayDisconnected(long session, String reason);
    }
    public static final class ApiException extends IOException {
        public final int status;
        public final String code;
        ApiException(int status, String code, String message) { super(message); this.status = status; this.code = code; }
    }
    private final OkHttpClient http = new OkHttpClient.Builder().connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS).callTimeout(12, TimeUnit.SECONDS).build();
    private final OkHttpClient sockets = http.newBuilder().callTimeout(0, TimeUnit.SECONDS).readTimeout(0, TimeUnit.SECONDS)
        .pingInterval(15, TimeUnit.SECONDS).build();
    private final ScheduledExecutorService writer = Executors.newSingleThreadScheduledExecutor();
    private final ArrayBlockingQueue<Media> media = new ArrayBlockingQueue<>(8);
    private final Listener listener;
    private volatile WebSocket current;
    private volatile boolean ready;
    private volatile long sessionId;
    private volatile String adminChallenge = "";
    public RelayClient(Listener listener) {
        this.listener = listener;
        writer.scheduleAtFixedRate(() -> {
            Media frame;
            while ((frame = media.poll()) != null) {
                WebSocket socket = current;
                if (socket != null && ready && socket.queueSize() < 32768 && SystemClock.elapsedRealtime() - frame.queued < 200)
                    socket.send(ByteString.of(frame.bytes));
            }
        }, 0, 5, TimeUnit.MILLISECONDS);
    }
    public JSONObject request(String method, String path, JSONObject body, String bearer) throws IOException {
        RequestBody content = body == null ? null : RequestBody.create(body.toString(), MediaType.get("application/json; charset=utf-8"));
        Request.Builder request = new Request.Builder().url(BASE_URL + path).method(method, content);
        if (bearer != null && !bearer.isEmpty()) request.header("Authorization", "Bearer " + bearer);
        try (Response response = http.newCall(request.build()).execute()) {
            String text = response.body() != null ? response.body().string() : "{}";
            JSONObject json;
            try { json = new JSONObject(text); }
            catch (JSONException invalid) { throw new IOException("服务器返回了不支持的响应 (" + response.code() + ")"); }
            if (!response.isSuccessful()) throw new ApiException(response.code(), json.optString("error"), json.optString("message", "服务器请求失败"));
            return json;
        }
    }
    public synchronized void connect(String room, String member, String token, long generation) {
        disconnect();
        final long session = ++sessionId;
        WebSocket socket = sockets.newWebSocket(new Request.Builder().url(BASE_URL.replace("https://", "wss://") + "/ws").build(), new WebSocketListener() {
            @Override public void onOpen(WebSocket webSocket, Response response) {
                synchronized (RelayClient.this) { if (current != webSocket || session != sessionId) { webSocket.cancel(); return; } }
                webSocket.send(Json.object("type", "auth", "roomId", room, "memberId", member, "token", token, "generation", generation).toString());
            }
            @Override public void onMessage(WebSocket webSocket, String text) {
                try {
                    JSONObject message = new JSONObject(text);
                    synchronized (RelayClient.this) {
                        if (current != webSocket || sessionId != session) return;
                        if ("authenticated".equals(message.optString("type"))) { ready = true; adminChallenge = message.optString("adminChallenge"); }
                    }
                    listener.onRelayControl(session, message);
                } catch (JSONException invalid) { webSocket.cancel(); }
            }
            @Override public void onMessage(WebSocket webSocket, ByteString bytes) {
                if (current == webSocket && sessionId == session && ready && bytes.size() <= 1333) listener.onRelayAudio(session, bytes.toByteArray());
            }
            @Override public void onClosed(WebSocket webSocket, int code, String reason) { lost(webSocket, session, "网络语音连接已断开"); }
            @Override public void onFailure(WebSocket webSocket, Throwable error, Response response) { lost(webSocket, session, "暂时无法连接 139，正在等待可用路径"); }
        });
        current = socket;
    }
    private void lost(WebSocket socket, long session, String reason) {
        synchronized (this) { if (current != socket || sessionId != session) return; current = null; ready = false; adminChallenge = ""; media.clear(); }
        listener.onRelayDisconnected(session, reason);
    }
    public long session() { return sessionId; }
    public String adminChallenge() { return adminChallenge; }
    public boolean isReady() { return ready; }
    public boolean isConnecting() { return current != null; }
    public void control(JSONObject json) { WebSocket socket = current; if (socket != null && socket.queueSize() < 32768) socket.send(json.toString()); }
    public void audio(byte[] bytes) {
        if (!ready) return;
        Media frame = new Media(bytes);
        if (!media.offer(frame)) { media.poll(); media.offer(frame); }
    }
    public synchronized void disconnect() {
        ++sessionId; WebSocket socket = current; current = null; ready = false; adminChallenge = ""; media.clear();
        if (socket != null) { socket.close(1000, "path changed"); socket.cancel(); }
    }
    @Override public void close() {
        disconnect(); writer.shutdownNow(); http.dispatcher().executorService().shutdown(); http.connectionPool().evictAll();
        sockets.dispatcher().executorService().shutdown(); sockets.connectionPool().evictAll();
    }
    private static final class Media {
        final byte[] bytes; final long queued = SystemClock.elapsedRealtime();
        Media(byte[] bytes) { this.bytes = bytes; }
    }
}
