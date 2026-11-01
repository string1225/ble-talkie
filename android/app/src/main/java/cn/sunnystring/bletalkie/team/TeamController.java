package cn.sunnystring.bletalkie.team;

import android.content.*;
import android.net.*;
import android.os.*;
import cn.sunnystring.bletalkie.audio.*;
import cn.sunnystring.bletalkie.core.*;
import cn.sunnystring.bletalkie.transport.*;
import org.json.*;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Serializes admission/control state while media flows independently on bounded transport queues. */
public final class TeamController implements AutoCloseable, BluetoothTransport.Listener, RelayClient.Listener {
    private final Context context;
    private final SharedPreferences preferences;
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final BluetoothTransport bluetooth;
    private final RelayClient relay;
    private final AudioEngine audio = new AudioEngine();
    private final AudioRoute audioRoute;
    private final PacketHistory history = new PacketHistory(512);
    private final AtomicInteger foregroundJobs = new AtomicInteger(), enrollmentJobs = new AtomicInteger();
    private final Map<String, BluetoothTransport.Peer> leaves = new ConcurrentHashMap<>();
    private final LinkedHashMap<String, NearbyRequest> pendingBluetooth = new LinkedHashMap<>();
    private final LinkedHashMap<String, JSONObject> pendingRemote = new LinkedHashMap<>();
    private final LinkedHashMap<String, String> nearby = new LinkedHashMap<>();
    private final LinkedHashMap<String, JSONObject> deferredDeletes = new LinkedHashMap<>();
    private final LinkedHashMap<String, JSONObject> ownerCommands = new LinkedHashMap<>();
    private final String selfId;
    private volatile String nickname, stateJson = "{}", status = "创建队伍，或加入附近队友", audioStatus = "尚未开始通话";
    private volatile TeamState team;
    private volatile Set<String> admitted = Collections.emptySet();
    private volatile BluetoothTransport.Peer gateway;
    private BluetoothTransport.Peer enrollingPeer;
    private volatile boolean closed, callActive, callStarting, muted, bluetoothJoining;
    private volatile long enrollmentEpoch;
    private volatile long sentPackets, receivedPackets;
    private long generation, streamEpoch, nextNetworkAttempt, nextBluetoothAttempt, lastRequestPoll;
    private long nextDeleteAttempt;
    private int sequence, retries;
    private String preferredGateway = "", joinRequest = "", joinToken = "";
    private String gatewayChallenge = "";
    private final ConnectivityManager connectivity;
    private final ConnectivityManager.NetworkCallback networkCallback = new ConnectivityManager.NetworkCallback() {
        @Override public void onAvailable(Network network) { enqueue(() -> { nextNetworkAttempt = 0; tick(); }); }
        @Override public void onLost(Network network) { enqueue(() -> { if (!hasInternet()) relay.disconnect(); broadcastRoster(); publish(); }); }
    };

    public TeamController(Context context) {
        this.context = context.getApplicationContext();
        preferences = context.getSharedPreferences("talkie_private", Context.MODE_PRIVATE);
        String savedId = preferences.getString("memberId", "");
        selfId = savedId.isEmpty() ? UUID.randomUUID().toString() : savedId;
        if (savedId.isEmpty()) preferences.edit().putString("memberId", selfId).apply();
        nickname = preferences.getString("nickname", "队员 " + selfId.substring(0, 4));
        generation = preferences.getLong("generation", 0);
        audioRoute = new AudioRoute(this.context);
        bluetooth = new BluetoothTransport(this.context, this);
        relay = new RelayClient(this);
        connectivity = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        try { connectivity.registerDefaultNetworkCallback(networkCallback); } catch (SecurityException ignored) {}
        enqueue(() -> {
            String saved = preferences.getString("team", "");
            if (!saved.isEmpty()) try { team = TeamState.load(new JSONObject(saved)); preferredGateway = preferences.getString("gatewayAddress", ""); }
            catch (JSONException | IllegalArgumentException invalid) { preferences.edit().remove("team").apply(); }
            try {
                JSONArray deletes = new JSONArray(preferences.getString("deferredDeletes", "[]"));
                for (int i = 0; i < deletes.length() && i < 32; i++) {
                    JSONObject operation = deletes.getJSONObject(i);
                    deferredDeletes.put(operation.getString("path"), operation);
                }
            } catch (JSONException invalid) { preferences.edit().remove("deferredDeletes").apply(); }
            if (team != null) { bluetooth.listen(); status = "正在恢复队伍连接"; }
            publish();
        });
        worker.scheduleAtFixedRate(this::safeTick, 1, 2, TimeUnit.SECONDS);
    }
    private String me() { return selfId; }
    public JSONObject getSnapshot() {
        try { return new JSONObject(stateJson); } catch (JSONException impossible) { return new JSONObject(); }
    }
    public void setNickname(String value) {
        enqueue(() -> {
            String clean = cleanNickname(value);
            if (clean.isEmpty()) { status = "请输入昵称"; publish(); return; }
            nickname = clean; preferences.edit().putString("nickname", nickname).apply();
            if (team != null && team.members.containsKey(me())) team.members.get(me()).nickname = nickname;
            publish();
        });
    }
    public void createTeam() {
        enroll(() -> {
            if (team != null) { status = "请先退出当前队伍"; publish(); return; }
            cancelEnrollment(); ++enrollmentEpoch;
            team = TeamState.local(me(), nickname); preferredGateway = "";
            preferences.edit().remove("gatewayAddress").apply();
            bluetooth.listen(); status = "本地队伍已创建；附近队友可通过蓝牙申请加入";
            persist(); publish(); nextNetworkAttempt = 0; tick();
        });
    }
    public void joinTeam(String code) {
        enroll(() -> {
            if (team != null) { status = "请先退出当前队伍，再加入其他队伍"; publish(); return; }
            if (!hasInternet()) { status = "此手机没有网络，请通过“附近找人”连接队友"; publish(); return; }
            cancelEnrollment(); ++enrollmentEpoch;
            try {
                status = "正在提交入队申请"; publish();
                JSONObject request = relay.request("POST", "/api/join", Json.object("code", code.trim(), "memberId", me(), "nickname", nickname), null);
                joinRequest = request.getString("requestId"); joinToken = request.getString("requestToken");
                status = "入队申请已发送，等待队长确认";
            } catch (Exception error) { status = friendly(error); }
            publish();
        });
    }
    public void scanNearby() { enqueue(() -> { nearby.clear(); bluetooth.discover(); publish(); }); }
    public void enableListening() { enqueue(() -> { bluetooth.listen(); publish(); }); }
    public void becomeGateway() {
        enqueue(() -> {
            if (team == null) { status = "请先加入队伍"; publish(); return; }
            if (!hasInternet()) { status = "这台手机需要有网络，才能为队友连接远程队伍"; publish(); return; }
            ++enrollmentEpoch; bluetooth.cancelConnect(); enrollingPeer = null; bluetooth.closePendingOutgoing(); bluetoothJoining = false;
            BluetoothTransport.Peer old = gateway; gateway = null; gatewayChallenge = ""; ownerCommands.clear();
            preferredGateway = ""; preferences.edit().remove("gatewayAddress").apply();
            if (old != null) old.close("已选择此手机作为网关");
            bluetooth.listen(); nextNetworkAttempt = 0;
            status = "已切换为本机网关，请点击“可被发现”让队友连接"; publish(); tick();
        });
    }
    public void connectBluetooth(String address) {
        enroll(() -> {
            if (team != null && !leaves.isEmpty()) {
                for (BluetoothTransport.Peer peer : leaves.values()) peer.close("本机正在切换网关，请重新选择队友连接");
                leaves.clear();
            }
            cancelNearbyEnrollments();
            if (team == null) { cancelEnrollment(); ++enrollmentEpoch; bluetoothJoining = true; }
            else { bluetooth.cancelConnect(); enrollingPeer = null; bluetooth.closePendingOutgoing(); bluetoothJoining = true; }
            preferredGateway = address; nextBluetoothAttempt = SystemClock.elapsedRealtime() + 25000;
            status = "正在通过蓝牙连接队友"; publish(); bluetooth.connect(address);
        });
    }
    public void approve(String key, boolean accept) {
        enqueue(() -> {
            NearbyRequest request = pendingBluetooth.get(key);
            if (request != null) {
                if (!accept) { pendingBluetooth.remove(key); request.peer.control(Json.object("type", "rejected", "message", "队长拒绝了入队申请")); request.peer.close("入队申请已拒绝"); }
                else approveNearby(request);
            } else if (team != null && team.ownerId.equals(me())) {
                if (gateway != null && !hasInternet()) { sendOwnerCommand(accept ? "approve" : "reject", key); publish(); return; }
                try { relay.request("POST", "/api/rooms/" + team.roomId + "/requests/" + key, Json.object("accept", accept), team.ownerToken); pendingRemote.remove(key); }
                catch (IOException error) { status = friendly(error); }
            }
            persist(); publish();
        });
    }
    public void removeMember(String id) {
        enqueue(() -> {
            if (team == null || !team.ownerId.equals(me()) || id.equals(me())) return;
            if (gateway != null && !hasInternet()) { sendOwnerCommand("remove", id); publish(); return; }
            departMember(id, true);
        });
    }
    public void leaveTeam() { main.post(this::stopCall); enqueue(() -> clearTeam(true, "已退出队伍")); }
    public void retryConnection() { enqueue(() -> { nextNetworkAttempt = 0; nextBluetoothAttempt = 0; tick(); }); }
    public void setMuted(boolean value) { muted = value; audio.setMuted(value); enqueue(this::publish); }
    public boolean hasTeam() { return team != null; }
    public boolean needsForeground() { return team != null || foregroundJobs.get() > 0 || isJoining(); }
    private boolean isJoining() { return enrollmentJobs.get() > 0 || bluetoothJoining || !joinRequest.isEmpty(); }
    public boolean isCallActive() { return callActive; }
    public boolean isCallStarting() { return callStarting; }
    public void startCall() {
        if (team == null) { enqueue(() -> { status = "请先创建或加入队伍"; publish(); }); return; }
        if (callActive || callStarting) return;
        callStarting = true; audioStatus = "正在建立通话音频通道";
        enqueue(this::publish);
        streamEpoch = new SecureRandom().nextLong(); sequence = 0;
        audioRoute.open(() -> {
            audio.start(this::captured, value -> { audioStatus = value; enqueue(this::publish); }, this::audioFailed);
            callStarting = false; callActive = audio.isRunning(); audio.setMuted(muted); enqueue(this::publish);
        }, value -> { audioStatus = value; enqueue(this::publish); }, this::audioFailed);
    }
    private void audioFailed(String message) { main.post(() -> { stopCall(); audioStatus = message; enqueue(this::publish); }); }
    public void stopCall() { audio.close(); audioRoute.close(); callActive = false; callStarting = false; audioStatus = "通话已结束"; enqueue(this::publish); }
    private void captured(byte[] opus) {
        TeamState active = team;
        if (active == null) return;
        AudioPacket packet = new AudioPacket(UUID.fromString(active.roomId), UUID.fromString(me()), streamEpoch, sequence++, SystemClock.elapsedRealtime(), opus);
        sentPackets++; route(packet, RoutePlanner.Ingress.LOCAL, null);
    }
    private void route(AudioPacket packet, RoutePlanner.Ingress ingress, String origin) {
        TeamState active = team;
        if (active == null || !packet.roomId.toString().equals(active.roomId)) return;
        String sender = packet.senderId.toString();
        if (ingress != RoutePlanner.Ingress.LOCAL && (sender.equals(me()) || !admitted.contains(sender) || !history.accept(packet))) return;
        BluetoothTransport.Peer upstream = gateway;
        RoutePlanner.Targets targets = RoutePlanner.plan(ingress, origin, new HashSet<>(leaves.keySet()), upstream == null ? null : upstream.memberId, relay.isReady());
        if (targets.play) { receivedPackets++; audio.receive(packet); }
        byte[] bytes = packet.encode();
        for (String id : targets.peers) {
            BluetoothTransport.Peer peer = upstream != null && id.equals(upstream.memberId) ? upstream : leaves.get(id);
            if (peer != null) peer.audio(bytes);
        }
        if (targets.relay) relay.audio(bytes);
    }
    @Override public void onAudio(BluetoothTransport.Peer peer, byte[] bytes) {
        try {
            AudioPacket packet = AudioPacket.decode(bytes);
            if (gateway == peer && peer.memberId != null) route(packet, RoutePlanner.Ingress.BLUETOOTH_GATEWAY, peer.memberId);
            else if (peer.memberId != null && leaves.get(peer.memberId) == peer && packet.senderId.toString().equals(peer.memberId))
                route(packet, RoutePlanner.Ingress.BLUETOOTH_LEAF, peer.memberId);
        } catch (IllegalArgumentException ignored) {}
    }
    @Override public void onRelayAudio(long session, byte[] bytes) { if (session == relay.session()) try { route(AudioPacket.decode(bytes), RoutePlanner.Ingress.RELAY, null); } catch (IllegalArgumentException ignored) {} }
    @Override public void onPeer(BluetoothTransport.Peer peer) {
        final long epoch = enrollmentEpoch;
        enqueue(() -> {
            if (epoch != enrollmentEpoch || !peer.isOpen()) { peer.close("连接操作已取消"); return; }
            if (peer.outgoing) {
                enrollingPeer = peer;
                peer.generation = nextGeneration();
                TeamState active = team;
                peer.control(Json.object("type", "hello", "version", 1, "memberId", me(), "nickname", nickname, "generation", peer.generation,
                    "roomId", active == null ? "" : active.roomId, "token", active == null ? "" : active.memberToken));
            } else if (team == null || gateway != null) peer.close("请先由此手机创建队伍或作为联网网关");
        });
    }
    @Override public void onControl(BluetoothTransport.Peer peer, JSONObject message) {
        final long epoch = enrollmentEpoch;
        enqueue(() -> {
            if (epoch != enrollmentEpoch) { peer.close("队伍已变更"); return; }
            try {
                String type = message.optString("type");
                if (type.equals("ping")) { peer.control(Json.object("type", "pong")); return; }
                if (type.equals("pong")) return;
                if (type.equals("hello") && !peer.outgoing) handleHello(peer, message);
                else if (type.equals("welcome") && peer.outgoing) handleWelcome(peer, message);
                else if (type.equals("roster") && gateway == peer && team != null) {
                    JSONObject room = message.getJSONObject("room");
                    if (team.roomId.equals(room.optString("roomId"))) applyRoster(room);
                } else if (type.equals("gateway_session") && gateway == peer && team != null && team.ownerId.equals(me())) {
                    if (message.optLong("generation") == peer.generation) gatewayChallenge = message.optString("adminChallenge");
                } else if (type.equals("join_request") && gateway == peer && team != null && team.ownerId.equals(me())) pendingRemote.put(message.optString("requestId"), message);
                else if (type.equals("owner_result") && gateway == peer) ownerResult(message);
                else if (type.equals("owner_command") && team != null && peer.memberId != null && peer.memberId.equals(team.ownerId) && leaves.get(peer.memberId) == peer) {
                    if (relay.isReady()) relay.control(message);
                    else peer.control(Json.object("type", "owner_result", "commandId", message.optString("commandId"), "action", message.optString("action"), "status", "error", "error", "gateway_offline", "message", "网关尚未连接服务器"));
                } else if (type.equals("leave") && peer.memberId != null && leaves.get(peer.memberId) == peer) departMember(peer.memberId, false);
                else if ((type.equals("removed") || type.equals("room_closed")) && gateway == peer) clearTeam(false, "队伍已结束或你已被移出", type.equals("room_closed"));
                else if (type.equals("rejected")) { status = message.optString("message", "入队申请已拒绝"); peer.close(status); }
                persist(); publish();
            } catch (Exception error) { peer.close("队伍协议不兼容"); }
        });
    }
    private void handleHello(BluetoothTransport.Peer peer, JSONObject message) throws Exception {
        if (team == null || gateway != null || message.optInt("version") != 1) { peer.close("当前手机不可作为网关"); return; }
        String id = message.getString("memberId"); UUID.fromString(id);
        long remoteGeneration = message.getLong("generation");
        if (id.equals(me()) || remoteGeneration < 1) { peer.close("成员身份无效"); return; }
        NearbyRequest request = new NearbyRequest(peer, id, cleanNickname(message.getString("nickname")), message.optString("token"), remoteGeneration);
        if (request.name.isEmpty()) { peer.close("昵称无效"); return; }
        if (team.members.containsKey(id) && !team.roomId.equals(message.optString("roomId"))) { peer.close("已有成员必须提供当前队伍的凭据"); return; }
        pendingBluetooth.put(peer.key, request);
        if (team.roomId.equals(message.optString("roomId")) && team.members.containsKey(id) && !request.token.isEmpty()) {
            String known = team.delegatedTokens.get(id);
            if (known != null && equal(known, request.token)) finishNearby(request, request.token);
            else if (relay.isReady()) {
                request.verifying = true;
                relay.control(Json.object("type", "attach", "memberId", id, "token", request.token, "generation", request.generation));
            } else { pendingBluetooth.remove(peer.key); peer.close("需要联网验证已有成员的凭据"); }
        } else if (!team.ownerId.equals(me())) {
            if (!hasInternet() || team.inviteCode.isEmpty()) { pendingBluetooth.remove(peer.key); peer.close("当前网关需要联网向队长申请"); return; }
            JSONObject join = relay.request("POST", "/api/join", Json.object("code", team.inviteCode, "memberId", id, "nickname", request.name), null);
            request.requestId = join.getString("requestId"); request.requestToken = join.getString("requestToken");
            peer.control(Json.object("type", "pending", "message", "正在等待远程队长确认"));
        } else peer.control(Json.object("type", "pending", "message", "等待队长接受附近入队申请"));
    }
    private void approveNearby(NearbyRequest request) {
        if (team == null || !team.ownerId.equals(me())) return;
        if (!team.members.containsKey(request.id) && team.members.size() >= 4) { status = "队伍已满，最多 4 人"; return; }
        try {
            String token = team.delegatedTokens.get(request.id);
            if (token == null) token = TeamState.token();
            if (relay.isReady()) relay.request("POST", "/api/rooms/" + team.roomId + "/members", Json.object("id", request.id, "nickname", request.name, "token", token), team.ownerToken);
            else team.unsyncedMembers.add(request.id);
            finishNearby(request, token);
        } catch (IOException error) { status = friendly(error); request.peer.control(Json.object("type", "rejected", "message", status)); request.peer.close(status); }
    }
    private void finishNearby(NearbyRequest request, String token) {
        if (team == null || !request.peer.isOpen()) return;
        request.peer.memberId = request.id; request.peer.nickname = request.name; request.peer.generation = request.generation;
        BluetoothTransport.Peer previous = leaves.put(request.id, request.peer);
        if (previous != null && previous != request.peer) previous.close("成员已建立新的蓝牙连接");
        TeamState.Member member = new TeamState.Member(request.id, request.name); member.online = true; member.via = me(); member.generation = request.generation;
        team.members.put(request.id, member); team.delegatedTokens.put(request.id, token); pendingBluetooth.remove(request.peer.key);
        JSONObject welcome = team.publicRoom();
        try { welcome.put("type", "welcome").put("memberToken", token).put("memberId", request.id).put("gatewayId", me()).put("gatewayNickname", nickname).put("adminChallenge", relay.adminChallenge()); }
        catch (JSONException impossible) { throw new IllegalStateException(impossible); }
        request.peer.control(welcome);
        if (relay.isReady()) attach(request.peer);
        status = request.name + " 已通过蓝牙加入"; broadcastRoster(); persist(); publish();
    }
    private void handleWelcome(BluetoothTransport.Peer peer, JSONObject message) throws Exception {
        if (!peer.isOpen() || enrollingPeer != peer) return;
        if (!me().equals(message.getString("memberId"))) { peer.close("入队身份不匹配"); return; }
        TeamState next = TeamState.load(message);
        if (team != null && !team.roomId.equals(next.roomId)) { peer.close("此手机不在当前队伍，请先退出再加入"); return; }
        if (team != null) {
            if (!equal(team.memberToken, next.memberToken)) { peer.close("成员凭据发生未授权变化"); return; }
            TeamState merged = new TeamState(next.roomId, next.ownerId, next.memberToken, team.ownerToken);
            merged.inviteCode = next.inviteCode; merged.members.putAll(next.members);
            merged.delegatedTokens.putAll(team.delegatedTokens); merged.unsyncedMembers.addAll(team.unsyncedMembers);
            next = merged;
        }
        String gatewayId = message.getString("gatewayId");
        if (!next.members.containsKey(me()) || !next.members.containsKey(gatewayId)) { peer.close("队伍成员列表无效"); return; }
        if (gateway != null && gateway != peer) gateway.close("已更换蓝牙网关");
        cancelRemoteEnrollment(); bluetoothJoining = false; enrollingPeer = null;
        leaves.clear(); team = next; gateway = peer; peer.memberId = gatewayId; peer.nickname = message.optString("gatewayNickname", "队友");
        gatewayChallenge = message.optString("adminChallenge"); ownerCommands.clear();
        relay.disconnect(); preferences.edit().putString("gatewayAddress", preferredGateway).apply();
        status = "已通过 " + peer.nickname + " 的蓝牙连接队伍"; persist(); publish();
    }
    @Override public void onClosed(BluetoothTransport.Peer peer, String reason) {
        final long epoch = enrollmentEpoch;
        enqueue(() -> {
            if (epoch != enrollmentEpoch) return;
            NearbyRequest abandoned = pendingBluetooth.remove(peer.key);
            withdrawNearby(abandoned);
            if (enrollingPeer == peer) { enrollingPeer = null; bluetoothJoining = false; status = reason; }
            if (gateway == peer) { gateway = null; gatewayChallenge = ""; ownerCommands.clear(); status = "蓝牙网关已断开，正在查找可用路径"; nextNetworkAttempt = 0; nextBluetoothAttempt = SystemClock.elapsedRealtime() + 4000; }
            if (peer.memberId != null && leaves.remove(peer.memberId, peer)) {
                if (relay.isReady()) relay.control(Json.object("type", "detach", "memberId", peer.memberId));
                if (team != null && team.members.containsKey(peer.memberId)) team.members.get(peer.memberId).online = false;
                status = reason; broadcastRoster();
            }
            persist(); publish();
        });
    }
    @Override public void onDevice(String address, String name) { enqueue(() -> { nearby.put(address, name); publish(); }); }
    @Override public void onStatus(String message) { enqueue(() -> { if (message.startsWith("蓝牙连接失败")) bluetoothJoining = false; status = message; publish(); }); }
    @Override public void onRelayControl(long session, JSONObject message) {
        enqueue(() -> {
            if (session != relay.session() || team == null) return;
            String type = message.optString("type");
            if (type.equals("authenticated")) {
                retries = 0; status = "已连接 139 语音中继";
                JSONObject room = message.optJSONObject("room"); if (room != null) applyRoster(room);
                for (BluetoothTransport.Peer peer : leaves.values()) attach(peer);
            } else if (type.equals("roster")) {
                JSONObject room = message.optJSONObject("room"); if (room != null) applyRoster(room);
            } else if (type.equals("attached")) {
                String id = message.optString("memberId");
                NearbyRequest found = null;
                for (NearbyRequest request : pendingBluetooth.values()) if (request.verifying && request.id.equals(id)) { found = request; break; }
                if (found != null) finishNearby(found, found.token);
                BluetoothTransport.Peer owner = leaves.get(team.ownerId);
                if (owner != null && id.equals(team.ownerId)) owner.control(Json.object("type", "gateway_session", "adminChallenge", relay.adminChallenge(), "generation", owner.generation));
            } else if (type.equals("join_request")) {
                if (team.ownerId.equals(me())) pendingRemote.put(message.optString("requestId"), message);
                else { BluetoothTransport.Peer owner = leaves.get(team.ownerId); if (owner != null) owner.control(message); }
            } else if (type.equals("owner_result")) {
                BluetoothTransport.Peer owner = leaves.get(team.ownerId); if (owner != null) owner.control(message);
            }
            else if (type.equals("room_closed") || (type.equals("member_removed") && me().equals(message.optString("memberId")))) { clearTeam(false, "队伍已结束或成员授权已撤销", type.equals("room_closed")); return; }
            else if (type.equals("error")) {
                status = "中继：" + message.optString("message", message.optString("code"));
                for (NearbyRequest request : new ArrayList<>(pendingBluetooth.values())) if (request.verifying) request.peer.close("成员验证失败");
            }
            persist(); publish();
        });
    }
    private void applyRoster(JSONObject snapshot) {
        if (team == null || !team.roomId.equals(snapshot.optString("roomId"))) return;
        team.updateRoster(snapshot);
        if (!team.members.containsKey(me())) { clearTeam(false, "你已不在队伍中"); return; }
        for (Map.Entry<String, BluetoothTransport.Peer> entry : leaves.entrySet()) {
            TeamState.Member member = team.members.get(entry.getKey());
            if (member == null) { entry.getValue().control(Json.object("type", "removed")); entry.getValue().close("成员已被移除"); team.delegatedTokens.remove(entry.getKey()); }
            else if (!member.via.isEmpty() && !member.via.equals(me()) && member.generation > entry.getValue().generation) entry.getValue().close("队员已切换到新的路径");
        }
        team.delegatedTokens.keySet().removeIf(id -> !team.members.containsKey(id) && !team.unsyncedMembers.contains(id));
        Set<UUID> activeIds = new HashSet<>();
        for (String id : team.members.keySet()) activeIds.add(UUID.fromString(id));
        audio.retainMembers(activeIds);
        broadcastRoster();
    }
    private void attach(BluetoothTransport.Peer peer) {
        TeamState active = team;
        if (active == null || peer.memberId == null) return;
        String token = active.delegatedTokens.get(peer.memberId);
        if (token != null) relay.control(Json.object("type", "attach", "memberId", peer.memberId, "token", token, "generation", peer.generation));
    }
    @Override public void onRelayDisconnected(long session, String reason) {
        enqueue(() -> {
            if (session != relay.session()) return;
            status = reason;
            long delay = Math.min(30000, 1000L << Math.min(retries++, 5));
            nextNetworkAttempt = SystemClock.elapsedRealtime() + delay;
            broadcastRoster(); publish();
        });
    }
    private void safeTick() {
        foregroundJobs.incrementAndGet();
        try { tick(); } catch (Exception error) { status = friendly(error); publish(); }
        finally { foregroundJobs.decrementAndGet(); }
    }
    private void tick() {
        if (closed) return;
        if (hasInternet() && !deferredDeletes.isEmpty()) flushDeletes();
        if (!joinRequest.isEmpty() && hasInternet()) pollJoin();
        if (team == null) return;
        long now = SystemClock.elapsedRealtime();
        ownerCommands.entrySet().removeIf(entry -> now - entry.getValue().optLong("sentAt") > 10000);
        if (gateway != null && team.ownerId.equals(me()) && now - lastRequestPoll > 4000 && !gatewayChallenge.isEmpty()) {
            lastRequestPoll = now; sendOwnerCommand("requests", "");
        }
        if (gateway == null && !preferredGateway.isEmpty() && now >= nextBluetoothAttempt && leaves.isEmpty() && bluetooth.available()) {
            nextBluetoothAttempt = now + 25000; bluetooth.connect(preferredGateway);
        }
        if (gateway == null && !relay.isConnecting() && now >= nextNetworkAttempt && hasInternet()) connectNetwork();
        if (gateway == null && relay.isReady() && !bluetoothJoining) {
            TeamState.Member ownRoute = team.members.get(me());
            if (ownRoute != null && !ownRoute.via.equals(me())) { relay.disconnect(); nextNetworkAttempt = 0; status = "正在恢复本机通话路径"; }
        }
        if (gateway == null && relay.isReady() && now - lastRequestPoll > 4000) {
            lastRequestPoll = now;
            if (team.ownerId.equals(me())) {
                try {
                    JSONArray requests = relay.request("GET", "/api/rooms/" + team.roomId + "/requests", null, team.ownerToken).optJSONArray("requests");
                    pendingRemote.clear();
                    if (requests != null) for (int i = 0; i < requests.length(); i++) { JSONObject request = requests.optJSONObject(i); if (request != null) pendingRemote.put(request.optString("requestId"), request); }
                } catch (IOException error) { status = friendly(error); }
            }
            for (NearbyRequest request : new ArrayList<>(pendingBluetooth.values())) if (!request.requestId.isEmpty()) pollNearbyJoin(request);
        }
        publish();
    }
    private void connectNetwork() {
        TeamState active = team;
        if (active == null) return;
        nextNetworkAttempt = SystemClock.elapsedRealtime() + 5000;
        try {
            if (active.ownerId.equals(me())) {
                Map<String, String> names = new HashMap<>();
                for (TeamState.Member member : active.members.values()) names.put(member.id, member.nickname);
                JSONObject response = relay.request("POST", "/api/rooms", Json.object("roomId", active.roomId, "ownerId", me(), "nickname", nickname,
                    "ownerToken", active.ownerToken, "memberToken", active.memberToken), null);
                Set<String> serverIds = new HashSet<>();
                JSONArray serverMembers = response.optJSONArray("members");
                if (serverMembers != null) for (int i = 0; i < serverMembers.length(); i++) {
                    JSONObject member = serverMembers.optJSONObject(i); if (member != null) serverIds.add(member.optString("id"));
                }
                for (Map.Entry<String, String> entry : new ArrayList<>(active.delegatedTokens.entrySet())) {
                    String id = entry.getKey();
                    if (!serverIds.contains(id) && active.unsyncedMembers.contains(id)) {
                        try {
                            response = relay.request("POST", "/api/rooms/" + active.roomId + "/members",
                                Json.object("id", id, "nickname", names.getOrDefault(id, "队员"), "token", entry.getValue()), active.ownerToken);
                            active.unsyncedMembers.remove(id); serverIds.add(id);
                        } catch (RelayClient.ApiException conflict) {
                            if (conflict.status != 409) throw conflict;
                            active.unsyncedMembers.remove(id); active.delegatedTokens.remove(id);
                            BluetoothTransport.Peer peer = leaves.remove(id);
                            if (peer != null) { peer.control(Json.object("type", "removed")); peer.close("联网队伍已满或成员授权冲突"); }
                        }
                    } else if (!serverIds.contains(id)) {
                        active.delegatedTokens.remove(id);
                        BluetoothTransport.Peer peer = leaves.remove(id);
                        if (peer != null) { peer.control(Json.object("type", "removed")); peer.close("成员授权已撤销"); }
                    } else active.unsyncedMembers.remove(id);
                }
                active.updateRoster(response);
            }
            persist(); status = "正在连接 139 语音中继"; publish();
            relay.connect(active.roomId, me(), active.memberToken, nextGeneration());
        } catch (IOException error) { status = friendly(error); nextNetworkAttempt = SystemClock.elapsedRealtime() + 5000; publish(); }
    }
    private void pollJoin() {
        if (team != null) { cancelRemoteEnrollment(); return; }
        try {
            JSONObject result = relay.request("GET", "/api/join/" + joinRequest, null, joinToken);
            if (result.optString("status").equals("approved")) {
                TeamState joined = TeamState.load(result);
                if (!joined.members.containsKey(me())) throw new JSONException("Missing approved identity");
                team = joined; joinRequest = ""; joinToken = ""; preferredGateway = "";
                preferences.edit().remove("gatewayAddress").apply(); bluetooth.listen(); status = "已获准入队，正在连接语音"; nextNetworkAttempt = 0; persist();
            } else if (result.optString("status").equals("rejected")) { joinRequest = ""; joinToken = ""; status = "队长拒绝了入队申请"; }
        } catch (Exception error) {
            if (error instanceof RelayClient.ApiException && ((RelayClient.ApiException) error).status == 404) { joinRequest = ""; joinToken = ""; }
            status = friendly(error);
        }
        publish();
    }
    private void pollNearbyJoin(NearbyRequest request) {
        try {
            JSONObject result = relay.request("GET", "/api/join/" + request.requestId, null, request.requestToken);
            if (result.optString("status").equals("approved")) { team.updateRoster(result); finishNearby(request, result.getString("memberToken")); }
            else if (result.optString("status").equals("rejected")) { request.peer.control(Json.object("type", "rejected", "message", "队长拒绝了入队申请")); request.peer.close("申请已拒绝"); }
        } catch (Exception error) {
            if (error instanceof RelayClient.ApiException && (((RelayClient.ApiException) error).status == 404 || ((RelayClient.ApiException) error).status == 403))
                request.peer.finish(Json.object("type", "rejected", "message", "入队申请已过期，请重新连接申请"), "入队申请已过期");
            status = friendly(error);
        }
    }
    private void broadcastRoster() { if (team != null) { JSONObject json = Json.object("type", "roster", "room", publicRoom()); for (BluetoothTransport.Peer peer : leaves.values()) peer.control(json); } }
    private JSONObject publicRoom() {
        JSONObject room = team.publicRoom();
        JSONArray members = room.optJSONArray("members");
        if (members != null) for (int i = 0; i < members.length(); i++) {
            JSONObject member = members.optJSONObject(i); if (member == null) continue;
            String id = member.optString("id");
            try { member.put("online", id.equals(me()) || leaves.containsKey(id) || (member.optBoolean("online") && (gateway != null || relay.isReady()))); }
            catch (JSONException impossible) { throw new IllegalStateException(impossible); }
        }
        return room;
    }
    private void publish() {
        TeamState active = team;
        JSONArray members = new JSONArray(), pending = new JSONArray(), devices = new JSONArray();
        Set<String> ids = new HashSet<>();
        if (active != null) {
            for (TeamState.Member member : active.members.values()) {
                ids.add(member.id);
                boolean local = member.id.equals(me()), bt = leaves.containsKey(member.id), viaGateway = gateway != null;
                boolean online = local || bt || (member.online && (viaGateway || relay.isReady()));
                String path = local ? "本机" : !online ? "离线" : bt ? "蓝牙直连" : viaGateway ? "经 " + gateway.nickname + " 的蓝牙" : "139 网络中继";
                members.put(Json.object("id", member.id, "nickname", member.nickname, "online", online, "route", path, "owner", member.id.equals(active.ownerId)));
            }
        }
        admitted = Collections.unmodifiableSet(ids);
        Set<UUID> audioMembers = new HashSet<>();
        for (String id : ids) audioMembers.add(UUID.fromString(id));
        audio.retainMembers(audioMembers);
        for (NearbyRequest request : pendingBluetooth.values()) if (request.peer.isOpen()) pending.put(Json.object("key", request.peer.key, "memberId", request.id, "nickname", request.name, "kind", "bluetooth", "canApprove", active != null && active.ownerId.equals(me()) && !request.verifying));
        for (Map.Entry<String, JSONObject> entry : pendingRemote.entrySet()) pending.put(Json.object("key", entry.getKey(), "memberId", entry.getValue().optString("memberId"), "nickname", entry.getValue().optString("nickname"), "kind", "network", "canApprove", active != null && active.ownerId.equals(me())));
        for (Map.Entry<String, String> entry : nearby.entrySet()) devices.put(Json.object("address", entry.getKey(), "name", entry.getValue()));
        stateJson = Json.object("selfId", me(), "nickname", nickname, "hasRoom", active != null, "roomId", active == null ? "" : active.roomId,
            "ownerId", active == null ? "" : active.ownerId, "owner", active != null && active.ownerId.equals(me()), "inviteCode", active == null ? "" : active.inviteCode,
            "connectedNetwork", relay.isReady(), "gatewayNickname", gateway == null ? "" : gateway.nickname, "callActive", callActive, "callStarting", callStarting, "muted", muted,
            "status", status, "audioStatus", audioStatus, "members", members, "pending", pending, "nearby", devices, "sentPackets", sentPackets, "receivedPackets", receivedPackets,
            "decodedFrames", audio.decodedFrames(), "decodedEnergy", audio.decodedEnergy(), "decodedSources", audio.decodedSources(),
            "joining", isJoining(), "canGateway", active != null && hasInternet()).toString();
    }
    private void persist() { if (team != null) preferences.edit().putString("team", team.saved().toString()).apply(); }
    private void clearTeam(boolean notify, String message) {
        clearTeam(notify, message, notify && team != null && team.ownerId.equals(me()));
    }
    private void clearTeam(boolean notify, String message, boolean roomEnded) {
        main.post(this::stopCall);
        TeamState active = team;
        BluetoothTransport.Peer oldGateway = gateway;
        if (notify && active != null && active.ownerId.equals(me()) && oldGateway != null && !hasInternet()) sendOwnerCommand("close", "");
        ++enrollmentEpoch; cancelRemoteEnrollment(); bluetoothJoining = false; enrollingPeer = null; bluetooth.cancelConnect();
        cancelNearbyEnrollments();
        if (active != null && notify) {
            deferDelete("/api/rooms/" + active.roomId + (active.ownerId.equals(me()) ? "" : "/members/" + me()), active.ownerId.equals(me()) ? active.ownerToken : active.memberToken);
        }
        if (hasInternet()) flushDeletes();
        team = null; gateway = null; gatewayChallenge = ""; ownerCommands.clear(); leaves.clear(); pendingBluetooth.clear(); pendingRemote.clear(); joinRequest = ""; joinToken = "";
        preferredGateway = ""; relay.disconnect();
        if (roomEnded) bluetooth.finishAll(Json.object("type", "room_closed"), "队伍已结束");
        else if (oldGateway != null && notify) { oldGateway.finish(Json.object("type", "leave"), "已退出队伍"); bluetooth.disconnectAllExcept(oldGateway); }
        else bluetooth.disconnectAll();
        bluetooth.stopListening(); history.clear(); admitted = Collections.emptySet();
        preferences.edit().remove("team").remove("gatewayAddress").apply(); main.post(this::stopCall); status = message; publish();
    }
    private void departMember(String id, boolean ownerAction) {
        if (team == null || id.equals(me())) return;
        String token = team.ownerId.equals(me()) ? team.ownerToken : team.delegatedTokens.get(id);
        if (token != null) deferDelete("/api/rooms/" + team.roomId + "/members/" + id, token);
        if (hasInternet()) flushDeletes();
        team.members.remove(id); team.delegatedTokens.remove(id); team.unsyncedMembers.remove(id);
        BluetoothTransport.Peer peer = leaves.remove(id);
        if (peer != null) { if (ownerAction) peer.finish(Json.object("type", "removed"), "已移出队伍"); else peer.close("已退出队伍"); }
        broadcastRoster(); persist(); publish();
    }
    private void cancelRemoteEnrollment() {
        if (!joinRequest.isEmpty() && !joinToken.isEmpty()) deferDelete("/api/join/" + joinRequest, joinToken);
        joinRequest = ""; joinToken = "";
    }
    private void cancelEnrollment() {
        cancelRemoteEnrollment(); bluetoothJoining = false; enrollingPeer = null;
        cancelNearbyEnrollments(); bluetooth.cancelConnect(); bluetooth.disconnectAll();
    }
    private void withdrawNearby(NearbyRequest request) {
        if (request != null && !request.requestId.isEmpty() && !request.requestToken.isEmpty())
            deferDelete("/api/join/" + request.requestId, request.requestToken);
    }
    private void cancelNearbyEnrollments() {
        for (NearbyRequest request : new ArrayList<>(pendingBluetooth.values())) {
            withdrawNearby(request); request.peer.close("入队连接已取消");
        }
        pendingBluetooth.clear();
    }
    private void sendOwnerCommand(String action, String target) {
        if (team == null || !team.ownerId.equals(me()) || gateway == null || gatewayChallenge.isEmpty()) { status = "网关尚未连上中继，暂时不能处理队伍管理"; return; }
        if (ownerCommands.size() >= 16) { status = "正在等待网关完成操作，请稍候"; return; }
        String id = UUID.randomUUID().toString(); long issued = System.currentTimeMillis();
        String signature = AdminSignature.sign(team.ownerToken, team.roomId, id, action, target, gateway.generation, issued, gatewayChallenge);
        JSONObject command = Json.object("type", "owner_command", "commandId", id, "roomId", team.roomId, "action", action, "targetId", target,
            "generation", gateway.generation, "issuedAt", issued, "challenge", gatewayChallenge, "signature", signature);
        ownerCommands.put(id, Json.object("action", action, "targetId", target, "sentAt", SystemClock.elapsedRealtime()));
        gateway.control(command);
    }
    private void ownerResult(JSONObject message) {
        if (team == null || !team.ownerId.equals(me())) return;
        JSONObject command = ownerCommands.remove(message.optString("commandId"));
        if (command == null || !command.optString("action").equals(message.optString("action"))) return;
        if (!message.optString("status").equals("ok")) { status = "网关操作未完成：" + message.optString("message", message.optString("error")); return; }
        JSONObject result = message.optJSONObject("result");
        String action = command.optString("action"), target = command.optString("targetId");
        if (action.equals("requests")) {
            pendingRemote.clear(); JSONArray requests = result == null ? null : result.optJSONArray("requests");
            if (requests != null) for (int i = 0; i < requests.length(); i++) { JSONObject request = requests.optJSONObject(i); if (request != null) pendingRemote.put(request.optString("requestId"), request); }
        } else if (action.equals("approve") || action.equals("reject")) pendingRemote.remove(target);
        else if (action.equals("remove")) { team.members.remove(target); team.delegatedTokens.remove(target); team.unsyncedMembers.remove(target); }
        else if (action.equals("close")) clearTeam(false, "队伍已结束", true);
        persist(); publish();
    }
    private void deferDelete(String path, String token) {
        if (deferredDeletes.size() >= 32 && !deferredDeletes.containsKey(path)) {
            status = "有较多离线退队操作等待同步，请先联网"; return;
        }
        deferredDeletes.put(path, Json.object("path", path, "token", token)); nextDeleteAttempt = 0; saveDeletes();
    }
    private void saveDeletes() { preferences.edit().putString("deferredDeletes", new JSONArray(deferredDeletes.values()).toString()).apply(); }
    private void flushDeletes() {
        if (SystemClock.elapsedRealtime() < nextDeleteAttempt) return;
        for (Map.Entry<String, JSONObject> entry : new ArrayList<>(deferredDeletes.entrySet())) {
            try { relay.request("DELETE", entry.getKey(), null, entry.getValue().optString("token")); }
            catch (RelayClient.ApiException error) { if (error.status != 404 && error.status != 403) { nextDeleteAttempt = SystemClock.elapsedRealtime() + 15000; break; } }
            catch (IOException unavailable) { nextDeleteAttempt = SystemClock.elapsedRealtime() + 15000; break; }
            deferredDeletes.remove(entry.getKey()); saveDeletes();
        }
    }
    private boolean hasInternet() {
        if (connectivity == null) return false;
        Network network = connectivity.getActiveNetwork();
        NetworkCapabilities capabilities = network == null ? null : connectivity.getNetworkCapabilities(network);
        return capabilities != null && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }
    private long nextGeneration() {
        generation = generation >= Long.MAX_VALUE - 1 ? 1 : generation + 1;
        preferences.edit().putLong("generation", generation).apply(); return generation;
    }
    private void enroll(Runnable action) {
        enrollmentJobs.incrementAndGet();
        enqueue(() -> { try { action.run(); } finally { enrollmentJobs.decrementAndGet(); publish(); } });
    }
    private void enqueue(Runnable action) {
        if (closed) return;
        foregroundJobs.incrementAndGet();
        try { worker.execute(() -> { try { action.run(); } finally { foregroundJobs.decrementAndGet(); } }); }
        catch (RejectedExecutionException ignored) { foregroundJobs.decrementAndGet(); }
    }
    private static String cleanNickname(String input) {
        if (input == null) return "";
        StringBuilder clean = new StringBuilder();
        input.trim().codePoints().filter(c -> !Character.isISOControl(c) && Character.getType(c) != Character.FORMAT).limit(32).forEach(clean::appendCodePoint);
        return clean.toString().trim();
    }
    private static boolean equal(String a, String b) { return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8)); }
    private static String friendly(Exception error) {
        if (error instanceof RelayClient.ApiException) {
            String code = ((RelayClient.ApiException) error).code;
            if (code.equals("room_full")) return "队伍已满，最多 4 人";
            if (code.contains("not_found") || code.contains("expired")) return "队伍或申请已过期，请重新获取组队码";
        }
        return error instanceof IOException ? "网络暂不可用，蓝牙连接仍可继续" : "操作未完成，请检查队伍与连接状态";
    }
    @Override public void close() {
        closed = true; try { connectivity.unregisterNetworkCallback(networkCallback); } catch (Exception ignored) {}
        relay.close(); bluetooth.close(); worker.shutdownNow(); main.post(() -> { audio.close(); audioRoute.close(); });
    }
    private static final class NearbyRequest {
        final BluetoothTransport.Peer peer;
        final String id, name, token;
        final long generation;
        boolean verifying;
        String requestId = "", requestToken = "";
        NearbyRequest(BluetoothTransport.Peer peer, String id, String name, String token, long generation) { this.peer = peer; this.id = id; this.name = name; this.token = token; this.generation = generation; }
    }
}
