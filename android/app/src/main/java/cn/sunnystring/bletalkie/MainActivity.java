package cn.sunnystring.bletalkie;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputFilter;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.function.Consumer;

import cn.sunnystring.bletalkie.team.TeamController;
import cn.sunnystring.bletalkie.ui.InviteCode;
import cn.sunnystring.bletalkie.ui.UiKit;

/** Native controls only: admission, media and connection state live in the bound service. */
public final class MainActivity extends Activity {
    private static final int PERMISSION_REQUEST = 41;
    private static final int ENABLE_BLUETOOTH = 42;
    private static final int SCAN_QR = 43;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TalkieService service;
    private TeamController controller;
    private boolean started, bindingRequested, foregroundEnsured, nicknameLoaded;
    private boolean hasRoom, owner, callActive, muted;
    private String inviteCode = "", selfId = "";
    private String gatewayNickname = "";
    private String lastMembers = "", lastPending = "", lastNearby = "";
    private Runnable permissionAction, bluetoothEnableAction;
    private Consumer<TeamController> readyAction;
    private String permissionReason;
    private EditText nicknameInput, joinInput;
    private TextView statusText, networkText, roomTitle, inviteText, callHint, audioRouteText;
    private LinearLayout setupCard, roomCard, memberList, pendingCard, pendingList, nearbyList, callCard;
    private Button createButton, joinButton, callButton, muteButton, shareButton, qrButton;
    private Button leaveButton, retryButton, notificationButton, gatewayButton;

    private final Runnable poll = new Runnable() {
        @Override public void run() {
            if (!started) return;
            if (controller != null) {
                JSONObject snapshot = controller.getSnapshot();
                if (snapshot.optBoolean("hasRoom") && !foregroundEnsured) ensureForeground();
                render(snapshot);
            }
            handler.postDelayed(this, 750);
        }
    };

    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            if (!started) return;
            TalkieService.LocalBinder local = (TalkieService.LocalBinder) binder;
            service = local.service();
            controller = local.controller();
            if (controller.hasTeam()) ensureForeground();
            render(controller.getSnapshot());
            Consumer<TeamController> action = readyAction;
            readyAction = null;
            if (action != null) runController(action);
        }

        @Override public void onServiceDisconnected(ComponentName name) {
            service = null;
            controller = null;
            foregroundEnsured = false;
            statusText.setText("连接服务已暂停，正在恢复…");
        }
    };

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        buildUi();
        if (savedInstanceState != null) {
            nicknameInput.setText(savedInstanceState.getString("nicknameDraft", ""));
            nicknameLoaded = !nicknameInput.getText().toString().isEmpty();
            joinInput.setText(savedInstanceState.getString("inviteDraft", ""));
        } else handleInvite(getIntent());
    }

    @Override protected void onStart() {
        super.onStart();
        started = true;
        bindingRequested = bindService(new Intent(this, TalkieService.class), connection, BIND_AUTO_CREATE);
        if (!bindingRequested) statusText.setText("服务暂时不可用，请重新打开应用");
        handler.post(poll);
        updateNotificationButton();
    }

    @Override protected void onStop() {
        started = false;
        handler.removeCallbacks(poll);
        if (bindingRequested) unbindService(connection);
        bindingRequested = false;
        service = null;
        controller = null;
        foregroundEnsured = false;
        super.onStop();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putString("nicknameDraft", nicknameInput.getText().toString());
        state.putString("inviteDraft", joinInput.getText().toString());
        super.onSaveInstanceState(state);
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleInvite(intent);
    }

    private void buildUi() {
        LinearLayout root = UiKit.column(this);
        root.setBackgroundColor(UiKit.BACKGROUND);
        root.setFitsSystemWindows(false);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets safe = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                android.graphics.Insets ime = insets.getInsets(WindowInsets.Type.ime());
                view.setPadding(safe.left, safe.top, safe.right, Math.max(safe.bottom, ime.bottom));
            } else view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        LinearLayout content = UiKit.column(this);
        content.setPadding(dp(20), dp(24), dp(20), dp(28));
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
        root.requestApplyInsets();

        TextView eyebrow = UiKit.text(this, "SUNNY STRING  /  TALKIE", 11, UiKit.GREEN, true);
        eyebrow.setLetterSpacing(0.12f);
        content.addView(eyebrow);
        content.addView(UiKit.text(this, "蓝牙对讲", 30, UiKit.INK, true), UiKit.block(this, 9));
        content.addView(UiKit.text(this, "最多 4 人 · 蓝牙与网络接力 · 双向通话", 13, UiKit.MUTED, false), UiKit.block(this, 5));

        LinearLayout statusCard = UiKit.column(this);
        statusCard.setPadding(dp(16), dp(13), dp(16), dp(13));
        statusCard.setBackground(UiKit.shape(this, UiKit.PALE, 16));
        statusText = UiKit.text(this, "正在连接对讲服务…", 14, UiKit.INK, true);
        statusText.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        statusCard.addView(statusText);
        networkText = UiKit.text(this, "创建队伍，或向队长申请加入", 12, UiKit.MUTED, false);
        statusCard.addView(networkText, UiKit.block(this, 3));
        content.addView(statusCard, UiKit.block(this, 20));

        LinearLayout nicknameCard = UiKit.card(this);
        nicknameCard.addView(UiKit.text(this, "你的昵称", 13, UiKit.MUTED, true));
        LinearLayout nicknameRow = row();
        nicknameInput = input("让队友认出你", false);
        nicknameInput.setFilters(new InputFilter[]{new InputFilter.LengthFilter(32)});
        nicknameInput.setContentDescription("你的昵称");
        nicknameRow.addView(nicknameInput, new LinearLayout.LayoutParams(0, dp(52), 1));
        Button saveNickname = UiKit.button(this, "保存", false);
        LinearLayout.LayoutParams saveParams = new LinearLayout.LayoutParams(dp(72), dp(48));
        saveParams.leftMargin = dp(10);
        nicknameRow.addView(saveNickname, saveParams);
        saveNickname.setOnClickListener(v -> saveNickname(true));
        nicknameCard.addView(nicknameRow, UiKit.block(this, 8));
        content.addView(nicknameCard, UiKit.block(this, 16));

        setupCard = UiKit.card(this);
        setupCard.addView(UiKit.text(this, "和队友连在一起", 21, UiKit.INK, true));
        setupCard.addView(UiKit.text(this, "一人创建，其他人申请加入。队长确认后即可通话。", 13, UiKit.MUTED, false), UiKit.block(this, 7));
        createButton = UiKit.button(this, "创建队伍", true);
        createButton.setOnClickListener(v -> createTeam());
        setupCard.addView(createButton, UiKit.block(this, 20));
        TextView or = UiKit.text(this, "或加入已有队伍", 12, UiKit.MUTED, false);
        or.setGravity(Gravity.CENTER);
        setupCard.addView(or, UiKit.block(this, 19));
        joinInput = input("16 位组队码或邀请链接", true);
        joinInput.setFilters(new InputFilter[]{new InputFilter.LengthFilter(2048)});
        joinInput.setContentDescription("组队码，区分大小写，也可粘贴邀请链接");
        setupCard.addView(joinInput, UiKit.block(this, 10));
        setupCard.addView(UiKit.text(this, "组队码区分大小写；网络入队需要队长同意。", 12, UiKit.MUTED, false), UiKit.block(this, 6));
        LinearLayout joinRow = row();
        joinButton = UiKit.button(this, "申请加入", false);
        joinButton.setOnClickListener(v -> requestJoin());
        Button scanButton = UiKit.button(this, "扫码加入", false);
        scanButton.setOnClickListener(v -> startActivityForResult(new Intent(this, QrScanActivity.class), SCAN_QR));
        addEqualButtons(joinRow, joinButton, scanButton);
        setupCard.addView(joinRow, UiKit.block(this, 12));
        content.addView(setupCard, UiKit.block(this, 16));

        roomCard = UiKit.card(this);
        roomTitle = UiKit.text(this, "我的队伍 · 1/4", 21, UiKit.INK, true);
        roomCard.addView(roomTitle);
        roomCard.addView(UiKit.text(this, "队友可以同时讲话，无需按住按钮。", 13, UiKit.MUTED, false), UiKit.block(this, 6));
        memberList = UiKit.column(this);
        roomCard.addView(memberList, UiKit.block(this, 18));
        inviteText = UiKit.text(this, "联网后生成组队码", 15, UiKit.INK, true);
        inviteText.setTypeface(Typeface.MONOSPACE);
        inviteText.setTextIsSelectable(true);
        roomCard.addView(inviteText, UiKit.block(this, 17));
        LinearLayout shareRow = row();
        shareButton = UiKit.button(this, "分享邀请", false);
        shareButton.setOnClickListener(v -> shareInvite());
        qrButton = UiKit.button(this, "显示二维码", false);
        qrButton.setOnClickListener(v -> showQr());
        addEqualButtons(shareRow, shareButton, qrButton);
        roomCard.addView(shareRow, UiKit.block(this, 12));
        gatewayButton = UiKit.button(this, "让这台手机做网关", false);
        gatewayButton.setOnClickListener(v -> confirmBecomeGateway());
        roomCard.addView(gatewayButton, UiKit.block(this, 10));
        content.addView(roomCard, UiKit.block(this, 16));

        pendingCard = UiKit.card(this);
        pendingCard.addView(UiKit.text(this, "入队申请", 19, UiKit.INK, true));
        pendingList = UiKit.column(this);
        pendingCard.addView(pendingList, UiKit.block(this, 8));
        content.addView(pendingCard, UiKit.block(this, 16));
        pendingCard.setVisibility(View.GONE);

        callCard = UiKit.card(this);
        callCard.setPadding(dp(16), dp(12), dp(16), dp(14));
        callHint = UiKit.text(this, "入队后，开启麦克风与队友通话。", 13, UiKit.MUTED, false);
        callCard.addView(callHint);
        audioRouteText = UiKit.text(this, "", 12, UiKit.INK, false);
        audioRouteText.setVisibility(View.GONE);
        callCard.addView(audioRouteText, UiKit.block(this, 4));
        LinearLayout callActions = row();
        callButton = UiKit.button(this, "开始通话", true);
        callButton.setTextSize(20);
        callActions.addView(callButton, new LinearLayout.LayoutParams(0, dp(72), 1));
        callButton.setOnClickListener(v -> toggleCall());
        muteButton = UiKit.button(this, "关闭麦克风", false);
        muteButton.setTextSize(13);
        muteButton.setOnClickListener(v -> runController(c -> c.setMuted(!muted)));
        LinearLayout.LayoutParams muteParams = new LinearLayout.LayoutParams(dp(100), dp(72));
        muteParams.leftMargin = dp(10);
        callActions.addView(muteButton, muteParams);
        callCard.addView(callActions, UiKit.block(this, 10));
        LinearLayout.LayoutParams dockParams = new LinearLayout.LayoutParams(-1, -2);
        dockParams.setMargins(dp(20), dp(8), dp(20), dp(12));
        root.addView(callCard, dockParams);
        callCard.setVisibility(View.GONE);

        LinearLayout nearbyCard = UiKit.card(this);
        nearbyCard.addView(UiKit.text(this, "附近的队友", 19, UiKit.INK, true));
        nearbyCard.addView(UiKit.text(this, "没网也能用蓝牙入队。请让队长开启可被发现，并保持应用打开。", 13, UiKit.MUTED, false), UiKit.block(this, 7));
        LinearLayout nearbyButtons = row();
        Button scanNearby = UiKit.button(this, "附近找人", false);
        scanNearby.setOnClickListener(v -> withBluetooth(false, () -> runController(TeamController::scanNearby)));
        Button discoverable = UiKit.button(this, "可被发现", false);
        discoverable.setContentDescription("让附近队友通过蓝牙发现这台手机");
        discoverable.setOnClickListener(v -> discoverable());
        addEqualButtons(nearbyButtons, scanNearby, discoverable);
        nearbyCard.addView(nearbyButtons, UiKit.block(this, 16));
        nearbyList = UiKit.column(this);
        nearbyList.addView(UiKit.text(this, "点击“附近找人”搜索手机。首次连接时可能出现系统配对确认。", 12, UiKit.MUTED, false));
        nearbyCard.addView(nearbyList, UiKit.block(this, 14));
        content.addView(nearbyCard, UiKit.block(this, 16));

        LinearLayout footerButtons = row();
        retryButton = UiKit.button(this, "重新连接", false);
        retryButton.setOnClickListener(v -> runController(TeamController::retryConnection));
        leaveButton = UiKit.button(this, "退出队伍", false);
        leaveButton.setTextColor(UiKit.RED);
        leaveButton.setOnClickListener(v -> confirmLeave());
        addEqualButtons(footerButtons, retryButton, leaveButton);
        content.addView(footerButtons, UiKit.block(this, 18));
        notificationButton = UiKit.button(this, "开启后台通话通知", false);
        notificationButton.setOnClickListener(v -> {
            if (Build.VERSION.SDK_INT >= 33) requestPermissionsFor(
                    new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    "通知权限可让你在后台查看队伍和通话状态；拒绝后仍可在应用内使用。",
                    () -> { updateNotificationButton(); toast("已开启后台通知"); });
        });
        content.addView(notificationButton, UiKit.block(this, 10));
        TextView footnote = UiKit.text(this, "未开始通话时，麦克风保持关闭。\n联网队友可为蓝牙队友转发语音。", 11, UiKit.MUTED, false);
        footnote.setGravity(Gravity.CENTER);
        content.addView(footnote, UiKit.block(this, 20));
        roomCard.setVisibility(View.GONE);
        UiKit.enabled(callButton, false);
        UiKit.enabled(muteButton, false);
        footerButtons.setVisibility(View.VISIBLE);
        updateNotificationButton();
    }

    private void render(JSONObject state) {
        if (!state.has("nickname")) return;
        hasRoom = state.optBoolean("hasRoom");
        owner = state.optBoolean("owner");
        callActive = state.optBoolean("callActive");
        boolean callStarting = state.optBoolean("callStarting");
        muted = state.optBoolean("muted");
        selfId = state.optString("selfId");
        inviteCode = state.optString("inviteCode");
        if (!nicknameLoaded && !nicknameInput.hasFocus()) {
            nicknameInput.setText(state.optString("nickname"));
            nicknameLoaded = true;
        }
        statusText.setText(state.optString("status", "准备就绪"));
        String gateway = state.optString("gatewayNickname");
        gatewayNickname = gateway;
        networkText.setText(!hasRoom ? "创建队伍，或向队长申请加入"
                : !gateway.isEmpty() ? "当前路径：经「" + gateway + "」蓝牙接力"
                : state.optBoolean("connectedNetwork") ? "当前路径：139 网络中继已连接"
                : "蓝牙可继续使用 · 网络恢复后自动连接");
        setupCard.setVisibility(hasRoom ? View.GONE : View.VISIBLE);
        roomCard.setVisibility(hasRoom ? View.VISIBLE : View.GONE);
        callCard.setVisibility(hasRoom ? View.VISIBLE : View.GONE);
        UiKit.enabled(createButton, !state.optBoolean("joining"));
        UiKit.enabled(joinButton, !state.optBoolean("joining"));
        joinButton.setText(state.optBoolean("joining") ? "等待队长确认" : "申请加入");
        JSONArray members = array(state, "members");
        roomTitle.setText((owner ? "我的队伍" : "所在队伍") + " · " + members.length() + "/4");
        String memberKey = members.toString() + owner + selfId;
        if (!memberKey.equals(lastMembers)) {
            lastMembers = memberKey;
            renderMembers(members);
        }
        boolean validInvite = InviteCode.parse(inviteCode) != null;
        inviteText.setText(validInvite ? inviteCode : "联网后生成组队码；附近仍可蓝牙入队");
        inviteText.setTextSize(validInvite ? 19 : 12);
        UiKit.enabled(shareButton, validInvite);
        UiKit.enabled(qrButton, validInvite);
        UiKit.enabled(gatewayButton, state.optBoolean("canGateway"));
        JSONArray pending = array(state, "pending");
        String pendingKey = pending.toString();
        if (!pendingKey.equals(lastPending)) {
            lastPending = pendingKey;
            renderPending(pending);
        }
        JSONArray nearby = array(state, "nearby");
        String nearbyKey = nearby.toString();
        if (!nearbyKey.equals(lastNearby)) {
            lastNearby = nearbyKey;
            renderNearby(nearby);
        }
        UiKit.enabled(callButton, hasRoom && !callStarting);
        callButton.setText(callStarting ? "正在连接音频…" : callActive ? "结束通话" : "开始通话");
        callButton.setContentDescription(callActive ? "结束双向通话并关闭麦克风" : "开始双向通话");
        callHint.setText(!hasRoom ? "入队后，开启麦克风与队友通话。"
                : callActive ? (muted ? "麦克风已关闭，你仍可听见队友。" : "双向通话中 · 队友可以同时讲话。")
                : callStarting ? "正在建立麦克风与播放路径…"
                : state.optString("audioStatus", "麦克风已关闭，点击开始通话。"));
        String audioStatus = state.optString("audioStatus");
        audioRouteText.setVisibility((callActive || callStarting) && !audioStatus.isEmpty() ? View.VISIBLE : View.GONE);
        audioRouteText.setText(audioStatus);
        UiKit.enabled(muteButton, callActive);
        muteButton.setText(muted ? "打开麦克风" : "关闭麦克风");
        muteButton.setContentDescription(muted ? "取消静音，打开麦克风" : "静音，关闭麦克风");
        leaveButton.setVisibility(hasRoom || state.optBoolean("joining") ? View.VISIBLE : View.GONE);
        retryButton.setVisibility(hasRoom ? View.VISIBLE : View.GONE);
        if (!hasRoom) foregroundEnsured = false;
    }

    private void renderMembers(JSONArray members) {
        memberList.removeAllViews();
        for (int i = 0; i < Math.max(4, members.length()); i++) {
            JSONObject member = members.optJSONObject(i);
            LinearLayout memberRow = row();
            memberRow.setPadding(0, dp(11), 0, dp(11));
            TextView avatar = UiKit.text(this, member == null ? "+" : initial(member.optString("nickname")), 18,
                    member == null ? UiKit.MUTED : UiKit.GREEN, true);
            avatar.setGravity(Gravity.CENTER);
            avatar.setBackground(UiKit.shape(this, member == null ? UiKit.BACKGROUND : UiKit.PALE, 14));
            memberRow.addView(avatar, new LinearLayout.LayoutParams(dp(46), dp(46)));
            LinearLayout labels = UiKit.column(this);
            LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(0, -2, 1);
            labelParams.leftMargin = dp(12);
            if (member == null) {
                labels.addView(UiKit.text(this, "等待队友加入", 14, UiKit.MUTED, false));
                labels.addView(UiKit.text(this, "空位 " + (i + 1), 11, UiKit.MUTED, false));
            } else {
                String id = member.optString("id");
                String name = member.optString("nickname", "队友");
                String suffix = (id.equals(selfId) ? " · 你" : "") + (member.optBoolean("owner") ? " · 队长" : "");
                labels.addView(UiKit.text(this, name + suffix, 15, UiKit.INK, true));
                labels.addView(UiKit.text(this, member.optString("route", member.optBoolean("online") ? "在线" : "离线"), 12,
                        member.optBoolean("online") ? UiKit.GREEN : UiKit.MUTED, false));
            }
            memberRow.addView(labels, labelParams);
            if (member != null && owner && !member.optString("id").equals(selfId)) {
                String id = member.optString("id"), name = member.optString("nickname", "队友");
                Button remove = UiKit.button(this, "移出", false);
                remove.setTextSize(12);
                remove.setTextColor(UiKit.RED);
                remove.setContentDescription("将" + name + "移出队伍");
                memberRow.addView(remove, new LinearLayout.LayoutParams(dp(60), dp(48)));
                remove.setOnClickListener(v -> new AlertDialog.Builder(this).setTitle("移出队员？")
                        .setMessage("「" + name + "」将失去当前队伍的通话权限。")
                        .setNegativeButton("取消", null).setPositiveButton("移出", (dialog, which) -> runController(c -> c.removeMember(id))).show());
            }
            memberList.addView(memberRow);
            if (i < Math.max(4, members.length()) - 1) {
                View line = new View(this);
                line.setBackgroundColor(UiKit.LINE);
                memberList.addView(line, new LinearLayout.LayoutParams(-1, dp(1)));
            }
        }
    }

    private void renderPending(JSONArray requests) {
        pendingList.removeAllViews();
        pendingCard.setVisibility(requests.length() == 0 ? View.GONE : View.VISIBLE);
        for (int i = 0; i < requests.length(); i++) {
            JSONObject request = requests.optJSONObject(i);
            if (request == null) continue;
            LinearLayout item = UiKit.column(this);
            String name = request.optString("nickname", "队友");
            item.addView(UiKit.text(this, name, 16, UiKit.INK, true));
            item.addView(UiKit.text(this, "bluetooth".equals(request.optString("kind")) ? "通过附近蓝牙申请入队" : "通过网络申请入队", 12, UiKit.MUTED, false), UiKit.block(this, 3));
            if (request.optBoolean("canApprove")) {
                String key = request.optString("key");
                LinearLayout actions = row();
                Button reject = UiKit.button(this, "拒绝", false);
                Button accept = UiKit.button(this, "同意加入", true);
                reject.setContentDescription("拒绝" + name + "加入队伍");
                accept.setContentDescription("同意" + name + "加入队伍");
                reject.setOnClickListener(v -> runController(c -> c.approve(key, false)));
                accept.setOnClickListener(v -> runController(c -> c.approve(key, true)));
                addEqualButtons(actions, reject, accept);
                item.addView(actions, UiKit.block(this, 10));
            } else item.addView(UiKit.text(this, "等待队长确认…", 13, UiKit.GREEN, false), UiKit.block(this, 8));
            pendingList.addView(item, UiKit.block(this, 14));
        }
    }

    private void renderNearby(JSONArray devices) {
        nearbyList.removeAllViews();
        if (devices.length() == 0) {
            nearbyList.addView(UiKit.text(this, "尚未发现手机。让队友开启“可被发现”，再点“附近找人”。", 12, UiKit.MUTED, false));
            return;
        }
        for (int i = 0; i < devices.length(); i++) {
            JSONObject device = devices.optJSONObject(i);
            if (device == null) continue;
            String address = device.optString("address"), name = device.optString("name", "附近手机");
            LinearLayout deviceRow = row();
            LinearLayout labels = UiKit.column(this);
            labels.addView(UiKit.text(this, name, 14, UiKit.INK, true));
            labels.addView(UiKit.text(this, address, 11, UiKit.MUTED, false));
            deviceRow.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
            Button connect = UiKit.button(this, "连接", false);
            connect.setContentDescription("通过蓝牙配对并连接" + name);
            connect.setOnClickListener(v -> confirmBluetoothConnection(address, name));
            deviceRow.addView(connect, new LinearLayout.LayoutParams(dp(72), dp(48)));
            nearbyList.addView(deviceRow, UiKit.block(this, i == 0 ? 0 : 12));
        }
    }

    private void createTeam() {
        runController(c -> {
            if (c.hasTeam()) { toast("你已在队伍中，请先退出当前队伍"); return; }
            if (!saveNickname(false)) return;
            ensureForeground();
            hideKeyboard();
            c.createTeam();
        });
    }

    private boolean saveNickname(boolean announce) {
        String name = nicknameInput.getText().toString().trim();
        if (name.isEmpty()) {
            nicknameInput.setError("请先填写昵称");
            nicknameInput.requestFocus();
            return false;
        }
        runController(c -> c.setNickname(name));
        nicknameLoaded = true;
        if (announce) { hideKeyboard(); toast("昵称已保存"); }
        return true;
    }

    private void requestJoin() {
        String code = InviteCode.parse(joinInput.getText().toString());
        if (code == null) {
            joinInput.setError("请输入 16 位组队码，注意大小写，或粘贴本应用邀请链接");
            joinInput.requestFocus();
            return;
        }
        runController(c -> {
            if (c.hasTeam()) confirmSwitchTeam(code);
            else joinTeam(code, false);
        });
    }

    private void joinTeam(String code, boolean leaveFirst) {
        if (!saveNickname(false)) return;
        runController(c -> {
            ensureForeground();
            if (leaveFirst) { service.stopCall(); c.leaveTeam(); }
            hideKeyboard();
            c.joinTeam(code);
        });
    }

    private void confirmSwitchTeam(String code) {
        new AlertDialog.Builder(this).setTitle("加入另一支队伍？")
                .setMessage("你已在队伍中。" + (owner ? "退出会结束你创建的队伍。" : "你将退出当前队伍。") + "\n\n组队码：" + code + "\n退出后将向新队长申请加入。")
                .setNegativeButton("留在当前队伍", null)
                .setPositiveButton("退出并申请加入", (dialog, which) -> joinTeam(code, true)).show();
    }

    private void confirmLeave() {
        new AlertDialog.Builder(this).setTitle(hasRoom ? "退出队伍？" : "取消入队申请？")
                .setMessage(owner ? "你是队长，退出后当前队伍将结束。" : hasRoom ? "退出后将结束通话。你可以重新申请入队。" : "当前入队申请将被取消。")
                .setNegativeButton("取消", null).setPositiveButton(hasRoom ? "退出队伍" : "取消申请", (dialog, which) -> runController(c -> {
                    service.stopCall();
                    c.leaveTeam();
                })).show();
    }

    private void toggleCall() {
        if (callActive) { runController(c -> service.stopCall()); return; }
        requestPermissionsFor(new String[]{Manifest.permission.RECORD_AUDIO},
                "开始通话需要麦克风权限。请在应用权限中允许录音，再点击“开始通话”。",
                () -> runController(c -> {
                    if (!c.hasTeam()) { toast("请先创建或加入队伍"); return; }
                    service.startCall();
                }));
    }

    private void confirmBluetoothConnection(String address, String name) {
        if (!hasRoom) { connectBluetooth(address, false); return; }
        new AlertDialog.Builder(this).setTitle("连接「" + name + "」")
                .setMessage("你已在队伍中。可以连接当前队友恢复蓝牙路径；若要加入另一支队伍，请先退出。\n\n连接新网关会断开当前连接此手机的蓝牙队友，他们仍保留队伍身份，需要重新连接到新网关。")
                .setNegativeButton("取消", null)
                .setNeutralButton("退出并连接", (dialog, which) -> connectBluetooth(address, true))
                .setPositiveButton("连接当前队友", (dialog, which) -> connectBluetooth(address, false)).show();
    }

    private void connectBluetooth(String address, boolean leaveFirst) {
        withBluetooth(false, () -> runController(c -> {
            if (!saveNickname(false)) return;
            ensureForeground();
            if (leaveFirst) { service.stopCall(); c.leaveTeam(); }
            c.connectBluetooth(address);
        }));
    }

    private void confirmBecomeGateway() {
        String explanation = gatewayNickname.isEmpty()
                ? "这台手机将使用自己的网络，并接受附近队友的蓝牙连接。"
                : "当前经「" + gatewayNickname + "」蓝牙接力。切换后会断开这条上行蓝牙连接，改用这台手机自己的网络，并接受附近队友的蓝牙连接。";
        new AlertDialog.Builder(this).setTitle("让这台手机做网关？")
                .setMessage(explanation + "\n\n启用后，请点击“可被发现”，让队友搜索并连接这台手机。")
                .setNegativeButton("取消", null)
                .setPositiveButton("设为网关", (dialog, which) -> withBluetooth(false, () -> runController(c -> {
                    ensureForeground();
                    c.becomeGateway();
                }))).show();
    }

    private void discoverable() {
        if (!hasRoom) {
            new AlertDialog.Builder(this).setTitle("先创建一支队伍")
                    .setMessage("创建后，这台手机可接受附近队友的蓝牙入队申请。")
                    .setNegativeButton("取消", null)
                    .setPositiveButton("创建并开启发现", (dialog, which) -> requestDiscoverable(true)).show();
        } else requestDiscoverable(false);
    }

    @SuppressLint("MissingPermission")
    private void requestDiscoverable(boolean create) {
        withBluetooth(true, () -> runController(c -> {
            if (!saveNickname(false)) return;
            ensureForeground();
            if (create && !c.hasTeam()) c.createTeam();
            c.enableListening();
            Intent discover = new Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE);
            discover.putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300);
            try { startActivity(discover); }
            catch (RuntimeException unavailable) { showMessage("无法开启发现", "请在系统蓝牙设置中确认蓝牙可用，再重试。"); }
        }));
    }

    @SuppressLint("MissingPermission")
    private void withBluetooth(boolean advertise, Runnable action) {
        ArrayList<String> permissions = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 31) {
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT);
            permissions.add(Manifest.permission.BLUETOOTH_SCAN);
            if (advertise) permissions.add(Manifest.permission.BLUETOOTH_ADVERTISE);
        } else permissions.add(Manifest.permission.ACCESS_FINE_LOCATION);
        requestPermissionsFor(permissions.toArray(new String[0]),
                Build.VERSION.SDK_INT >= 31 ? "蓝牙找人和连接需要附近设备权限。请在应用权限中允许附近设备，再重试。"
                        : "此系统需要位置权限才能搜索附近蓝牙手机。请允许位置权限，并在系统中开启位置信息。",
                () -> {
                    BluetoothManager manager = getSystemService(BluetoothManager.class);
                    BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
                    if (adapter == null) { showMessage("此设备不支持蓝牙", "你仍可通过网络组队和通话。"); return; }
                    if (!adapter.isEnabled()) {
                        bluetoothEnableAction = action;
                        try { startActivityForResult(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), ENABLE_BLUETOOTH); }
                        catch (RuntimeException unavailable) { bluetoothEnableAction = null; showMessage("请开启蓝牙", "请在系统设置中开启蓝牙，再重试。"); }
                    } else action.run();
                });
    }

    private void requestPermissionsFor(String[] permissions, String reason, Runnable action) {
        ArrayList<String> missing = new ArrayList<>();
        for (String permission : permissions) if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) missing.add(permission);
        if (missing.isEmpty()) { action.run(); return; }
        if (permissionAction != null) { toast("请先完成当前权限选择"); return; }
        permissionAction = action;
        permissionReason = reason;
        requestPermissions(missing.toArray(new String[0]), PERMISSION_REQUEST);
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != PERMISSION_REQUEST) return;
        Runnable action = permissionAction;
        permissionAction = null;
        boolean granted = permissions.length > 0;
        for (String permission : permissions) granted &= checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
        updateNotificationButton();
        if (granted && action != null) action.run();
        else if (!granted) new AlertDialog.Builder(this).setTitle("权限未开启")
                .setMessage(permissionReason == null ? "请在应用权限中允许所需权限，再点击对应操作。" : permissionReason)
                .setNegativeButton("暂不", null).setPositiveButton("打开应用设置", (dialog, which) -> openAppSettings()).show();
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == ENABLE_BLUETOOTH) {
            Runnable action = bluetoothEnableAction;
            bluetoothEnableAction = null;
            if (resultCode == RESULT_OK && action != null) action.run();
            else if (resultCode != RESULT_OK) toast("蓝牙未开启。开启后可再次点击蓝牙操作。");
        } else if (requestCode == SCAN_QR && resultCode == RESULT_OK && data != null) {
            showReceivedInvite(data.getStringExtra("code"));
        }
    }

    private void handleInvite(Intent intent) {
        if (intent == null || !Intent.ACTION_VIEW.equals(intent.getAction()) || intent.getData() == null) return;
        String code = InviteCode.parse(intent.getData().toString());
        if (code == null) { toast("邀请链接无效，请重新获取组队码"); return; }
        showReceivedInvite(code);
    }

    private void showReceivedInvite(String value) {
        String code = InviteCode.parse(value);
        if (code == null) { toast("这不是有效的队伍邀请"); return; }
        joinInput.setText(code);
        joinInput.setSelection(code.length());
        runController(c -> {
            if (c.hasTeam()) confirmSwitchTeam(code);
            else {
                setupCard.setVisibility(View.VISIBLE);
                joinInput.requestFocus();
                new AlertDialog.Builder(this).setTitle("已识别队伍邀请")
                        .setMessage("组队码：" + code + "\n\n点击申请后，等待队长同意即可入队。")
                        .setNegativeButton("稍后加入", null)
                        .setPositiveButton("申请加入", (dialog, which) -> joinTeam(code, false)).show();
            }
        });
    }

    private void shareInvite() {
        if (InviteCode.parse(inviteCode) == null) return;
        Intent share = new Intent(Intent.ACTION_SEND);
        share.setType("text/plain");
        share.putExtra(Intent.EXTRA_TEXT, InviteCode.link(inviteCode));
        startActivity(Intent.createChooser(share, "邀请队友加入"));
    }

    private void showQr() {
        if (InviteCode.parse(inviteCode) == null) return;
        String code = inviteCode;
        try {
            int size = dp(256);
            BitMatrix matrix = new QRCodeWriter().encode(InviteCode.link(code), BarcodeFormat.QR_CODE, size, size);
            int[] pixels = new int[size * size];
            for (int y = 0; y < size; y++) for (int x = 0; x < size; x++) pixels[y * size + x] = matrix.get(x, y) ? Color.BLACK : Color.WHITE;
            Bitmap bitmap = Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888);
            LinearLayout body = UiKit.column(this);
            body.setGravity(Gravity.CENTER_HORIZONTAL);
            body.setPadding(dp(20), dp(8), dp(20), dp(12));
            ImageView image = new ImageView(this);
            image.setImageBitmap(bitmap);
            image.setContentDescription("队伍邀请二维码，组队码" + code);
            image.setAdjustViewBounds(true);
            body.addView(image, new LinearLayout.LayoutParams(-1, dp(256)));
            TextView label = UiKit.text(this, code, 19, UiKit.INK, true);
            label.setTypeface(Typeface.MONOSPACE);
            label.setTextIsSelectable(true);
            body.addView(label, UiKit.block(this, 12));
            body.addView(UiKit.text(this, "让队友扫码申请加入，或分享邀请链接。", 12, UiKit.MUTED, false), UiKit.block(this, 8));
            new AlertDialog.Builder(this).setTitle("邀请队友")
                    .setView(body).setNegativeButton("关闭", null)
                    .setNeutralButton("复制组队码", (dialog, which) -> {
                        ClipboardManager clipboard = getSystemService(ClipboardManager.class);
                        if (clipboard != null) clipboard.setPrimaryClip(ClipData.newPlainText("组队码", code));
                        toast("组队码已复制，注意区分大小写");
                    }).setPositiveButton("分享邀请", (dialog, which) -> shareInvite()).show();
        } catch (Exception error) { showMessage("二维码生成失败", "你可以直接分享邀请链接，或让队友填写组队码。"); }
    }

    private void runController(Consumer<TeamController> action) {
        if (controller == null || service == null) {
            readyAction = action;
            statusText.setText("正在连接对讲服务…");
            return;
        }
        try { action.accept(controller); }
        catch (RuntimeException failure) { showMessage("操作未完成", "请检查权限和连接状态，然后重试。"); }
    }

    private void ensureForeground() {
        if (service == null) return;
        foregroundEnsured = true;
        try { service.ensureTeamForeground(); }
        catch (RuntimeException unavailable) { toast("后台连接暂未开启，请保持应用在前台并重试。"); }
    }

    private void updateNotificationButton() {
        if (notificationButton == null) return;
        notificationButton.setVisibility(Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED ? View.VISIBLE : View.GONE);
    }

    private void openAppSettings() {
        startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", getPackageName(), null)));
    }

    private EditText input(String hint, boolean code) {
        EditText view = new EditText(this);
        view.setSingleLine(true);
        view.setTextSize(15);
        view.setTextColor(UiKit.INK);
        view.setHintTextColor(UiKit.MUTED);
        view.setHint(hint);
        view.setInputType(InputType.TYPE_CLASS_TEXT | (code ? InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS : InputType.TYPE_TEXT_FLAG_CAP_SENTENCES));
        view.setPadding(dp(12), dp(11), dp(12), dp(11));
        view.setBackground(UiKit.shape(this, UiKit.BACKGROUND, 12));
        view.setMinimumHeight(dp(52));
        if (code) view.setTypeface(Typeface.MONOSPACE);
        return view;
    }

    private LinearLayout row() {
        LinearLayout view = new LinearLayout(this);
        view.setOrientation(LinearLayout.HORIZONTAL);
        view.setGravity(Gravity.CENTER_VERTICAL);
        return view;
    }

    private void addEqualButtons(LinearLayout parent, Button first, Button second) {
        LinearLayout.LayoutParams firstParams = new LinearLayout.LayoutParams(0, -2, 1);
        firstParams.rightMargin = dp(5);
        parent.addView(first, firstParams);
        LinearLayout.LayoutParams secondParams = new LinearLayout.LayoutParams(0, -2, 1);
        secondParams.leftMargin = dp(5);
        parent.addView(second, secondParams);
    }

    private void hideKeyboard() {
        View focus = getCurrentFocus();
        if (focus != null) {
            InputMethodManager input = getSystemService(InputMethodManager.class);
            if (input != null) input.hideSoftInputFromWindow(focus.getWindowToken(), 0);
            focus.clearFocus();
        }
    }

    private void toast(String text) { Toast.makeText(this, text, Toast.LENGTH_SHORT).show(); }
    private void showMessage(String title, String text) { new AlertDialog.Builder(this).setTitle(title).setMessage(text).setPositiveButton("知道了", null).show(); }
    private int dp(float value) { return UiKit.dp(this, value); }
    private static JSONArray array(JSONObject state, String key) { JSONArray value = state.optJSONArray(key); return value == null ? new JSONArray() : value; }
    private static String initial(String value) { return value.isEmpty() ? "队" : value.substring(0, value.offsetByCodePoints(0, 1)); }
}
