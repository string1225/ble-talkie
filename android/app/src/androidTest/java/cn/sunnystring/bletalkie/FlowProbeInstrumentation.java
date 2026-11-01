package cn.sunnystring.bletalkie;

import android.app.*;
import android.content.*;
import android.os.*;
import cn.sunnystring.bletalkie.audio.OpusCodec;
import cn.sunnystring.bletalkie.core.*;
import cn.sunnystring.bletalkie.team.TeamController;
import cn.sunnystring.bletalkie.transport.RelayClient;
import org.json.*;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

/** Separate test APK. Synthetic tones and members only; does not claim a Bluetooth radio test. */
public final class FlowProbeInstrumentation extends Instrumentation {
    private TalkieService service;
    private TeamController controller;
    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }
    @Override public void onStart() {
        Bundle results = new Bundle();
        Activity activity = null;
        final CountDownLatch bound = new CountDownLatch(1);
        ServiceConnection connection = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, IBinder binder) {
                TalkieService.LocalBinder local = (TalkieService.LocalBinder) binder;
                service = local.service(); controller = local.controller(); bound.countDown();
            }
            @Override public void onServiceDisconnected(ComponentName name) {}
        };
        RelayClient api = new RelayClient(new Sink());
        final CountDownLatch remoteAuthenticated = new CountDownLatch(1), proxyAttached = new CountDownLatch(1);
        RelayClient remote = new RelayClient(new Sink() {
            @Override public void onRelayControl(long session, JSONObject message) {
                if (message.optString("type").equals("authenticated")) remoteAuthenticated.countDown();
                if (message.optString("type").equals("attached")) proxyAttached.countDown();
            }
        });
        try {
            Intent open = new Intent(getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity = startActivitySync(open);
            waitForIdleSync();
            runOnMainSync(() -> getTargetContext().bindService(new Intent(getTargetContext(), TalkieService.class), connection, Context.BIND_AUTO_CREATE));
            if (!bound.await(10, TimeUnit.SECONDS)) throw new AssertionError("Service binding timeout");
            await(() -> controller.getSnapshot().has("nickname"), 5000, "Controller initialization");
            if (controller.hasTeam()) { controller.leaveTeam(); await(() -> !controller.hasTeam(), 18000, "Previous test room cleanup"); }
            runOnMainSync(() -> { service.ensureTeamForeground(); controller.setNickname("模拟器 A"); controller.createTeam(); });
            await(() -> controller.getSnapshot().optBoolean("connectedNetwork"), 40000, "Public relay connection");
            JSONObject saved = new JSONObject(getTargetContext().getSharedPreferences("talkie_private", Context.MODE_PRIVATE).getString("team", "{}"));
            String roomId = saved.getString("roomId"), code = saved.getString("inviteCode"), ownerToken = saved.getString("ownerToken");
            String b = UUID.randomUUID().toString(), c = UUID.randomUUID().toString();
            String bToken = enroll(api, roomId, code, ownerToken, b, "模拟网关 B");
            String cToken = enroll(api, roomId, code, ownerToken, c, "代理成员 C");
            remote.connect(roomId, b, bToken, 1);
            if (!remoteAuthenticated.await(10, TimeUnit.SECONDS)) throw new AssertionError("Remote member authentication");
            remote.control(Json.object("type", "attach", "memberId", c, "token", cToken, "generation", 1));
            if (!proxyAttached.await(10, TimeUnit.SECONDS)) throw new AssertionError("Delegated member attachment");
            await(() -> controller.getSnapshot().optJSONArray("members") != null && controller.getSnapshot().optJSONArray("members").length() == 3, 8000, "Three-member roster");
            byte[][] bFrames = new byte[100][], cFrames = new byte[100][];
            try (OpusCodec.Encoder bEncoder = new OpusCodec.Encoder(); OpusCodec.Encoder cEncoder = new OpusCodec.Encoder()) {
                for (int sequence = 0; sequence < 100; sequence++) {
                    bFrames[sequence] = bEncoder.encode(tone(sequence, 440));
                    cFrames[sequence] = cEncoder.encode(tone(sequence, 660));
                }
            }
            runOnMainSync(() -> service.startCall());
            await(() -> controller.isCallActive(), 10000, "Foreground duplex audio start");
            UUID room = UUID.fromString(roomId), bId = UUID.fromString(b), cId = UUID.fromString(c);
            long sendStarted = SystemClock.elapsedRealtime(), nextSend = SystemClock.elapsedRealtimeNanos();
            for (int sequence = 0; sequence < 100; sequence++) {
                long remaining;
                while ((remaining = nextSend - SystemClock.elapsedRealtimeNanos()) > 0)
                    java.util.concurrent.locks.LockSupport.parkNanos(remaining);
                remote.audio(new AudioPacket(room, bId, 1001, sequence, SystemClock.elapsedRealtime(), bFrames[sequence]).encode());
                remote.audio(new AudioPacket(room, cId, 1002, sequence, SystemClock.elapsedRealtime(), cFrames[sequence]).encode());
                nextSend += 20_000_000L;
            }
            results.putLong("syntheticSendMillis", SystemClock.elapsedRealtime() - sendStarted);
            await(() -> controller.getSnapshot().optLong("decodedFrames") >= 180 && controller.getSnapshot().optLong("decodedEnergy") > 100000, 10000, "Two decoded Opus sources (at least 90% of 200 frames)");
            JSONObject state = controller.getSnapshot();
            if (state.optInt("decodedSources") != 2) throw new AssertionError("Expected exactly two independent decoders");
            if (state.optLong("sentPackets") < 20) throw new AssertionError("Microphone did not emit frames");
            results.putLong("sentPackets", state.optLong("sentPackets"));
            results.putLong("receivedPackets", state.optLong("receivedPackets"));
            results.putLong("decodedFrames", state.optLong("decodedFrames"));
            results.putInt("decodedSources", state.optInt("decodedSources"));
            runOnMainSync(() -> service.stopCall());
            remote.disconnect();
            controller.leaveTeam();
            await(() -> !controller.hasTeam(), 18000, "Online room shutdown");
            boolean revoked = false;
            try { api.request("GET", "/api/rooms/" + roomId + "/requests", null, ownerToken); }
            catch (RelayClient.ApiException error) { revoked = error.status == 404; }
            if (!revoked) throw new AssertionError("Online leave did not revoke the room");
            results.putString("nativeRelayFlow", "PASS: public WSS, delegated source, native capture/mixing and online revocation");
            finish(Activity.RESULT_OK, results);
        } catch (Throwable error) {
            if (controller != null) {
                JSONObject state = controller.getSnapshot();
                results.putLong("receivedPackets", state.optLong("receivedPackets"));
                results.putLong("decodedFrames", state.optLong("decodedFrames"));
            }
            results.putString("nativeRelayFlow", "FAIL: " + error.getClass().getSimpleName() + ": " + error.getMessage());
            finish(Activity.RESULT_CANCELED, results);
        } finally {
            remote.close(); api.close();
            if (controller != null) controller.leaveTeam();
            try { runOnMainSync(() -> getTargetContext().unbindService(connection)); } catch (Exception ignored) {}
            Activity opened = activity;
            if (opened != null) runOnMainSync(opened::finish);
        }
    }
    private String enroll(RelayClient api, String room, String code, String owner, String member, String name) throws Exception {
        JSONObject request = api.request("POST", "/api/join", Json.object("code", code, "memberId", member, "nickname", name), null);
        String id = request.getString("requestId");
        api.request("POST", "/api/rooms/" + room + "/requests/" + id, Json.object("accept", true), owner);
        return api.request("GET", "/api/join/" + id, null, request.getString("requestToken")).getString("memberToken");
    }
    private static short[] tone(int frame, int frequency) {
        short[] pcm = new short[320];
        for (int i = 0; i < pcm.length; i++) pcm[i] = (short) (Math.sin((frame * 320 + i) * 2 * Math.PI * frequency / 16000) * 8000);
        return pcm;
    }
    private static void await(BooleanSupplier check, long timeout, String description) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + timeout;
        while (!check.getAsBoolean()) { if (SystemClock.elapsedRealtime() >= deadline) throw new AssertionError(description + " timed out"); Thread.sleep(100); }
    }
    private static class Sink implements RelayClient.Listener {
        @Override public void onRelayControl(long session, JSONObject message) {}
        @Override public void onRelayAudio(long session, byte[] bytes) {}
        @Override public void onRelayDisconnected(long session, String reason) {}
    }
}
