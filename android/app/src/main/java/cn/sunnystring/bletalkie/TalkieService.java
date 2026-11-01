package cn.sunnystring.bletalkie;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.*;
import cn.sunnystring.bletalkie.team.TeamController;

/** Keeps gateway forwarding alive independently of the screen, with explicit microphone activation. */
public final class TalkieService extends Service {
    private static final String CHANNEL = "talkie_connection", KEEP_ALIVE = "keep_alive", STOP_CALL = "stop_call", LEAVE = "leave_team";
    private static final int NOTIFICATION = 4107;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final LocalBinder binder = new LocalBinder();
    private TeamController controller;
    private boolean foreground;
    private int foregroundTypes;
    private PowerManager.WakeLock wakeLock;
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            if (controller != null && foreground) {
                if (!controller.needsForeground()) {
                    stopForeground(STOP_FOREGROUND_REMOVE); foreground = false;
                    if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
                    stopSelf();
                } else {
                    int desired = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE;
                    if (controller.isCallActive() || controller.isCallStarting()) desired |= microphoneType();
                    if (desired != foregroundTypes) promote(desired);
                }
            }
            main.postDelayed(this, 1000);
        }
    };
    public final class LocalBinder extends Binder {
        public TeamController controller() { return controller; }
        public TalkieService service() { return TalkieService.this; }
    }
    @Override public void onCreate() {
        super.onCreate();
        NotificationManager notifications = getSystemService(NotificationManager.class);
        notifications.createNotificationChannel(new NotificationChannel(CHANNEL, "对讲与队伍连接", NotificationManager.IMPORTANCE_LOW));
        controller = new TeamController(this);
        PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "bletalkie:gateway");
        wakeLock.setReferenceCounted(false);
        main.postDelayed(refresh, 1000);
    }
    @Override public IBinder onBind(Intent intent) { return binder; }
    public void ensureTeamForeground() {
        startForegroundService(new Intent(this, TalkieService.class).setAction(KEEP_ALIVE));
        promote(ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
    }
    public void startCall() {
        if (!controller.hasTeam()) { controller.onStatus("请先加入队伍"); return; }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { controller.onStatus("需要麦克风权限才能开始通话"); return; }
        ensureTeamForeground();
        promote(ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE | microphoneType());
        controller.startCall();
    }
    public void stopCall() {
        controller.stopCall();
        if (foreground) promote(ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
    }
    private void promote(int types) {
        try {
            startForeground(NOTIFICATION, notification((types & microphoneType()) != 0 || controller.isCallActive()), types);
            foreground = true; foregroundTypes = types;
            if (!wakeLock.isHeld()) wakeLock.acquire(60 * 60 * 1000L);
        } catch (SecurityException | IllegalStateException error) { controller.onStatus("无法在后台保持连接，请从 App 前台重新开始"); }
    }
    private Notification notification(boolean microphone) {
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, TalkieService.class).setAction(STOP_CALL), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent leave = PendingIntent.getService(this, 2, new Intent(this, TalkieService.class).setAction(LEAVE), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("蓝牙对讲").setContentText(microphone ? "全双工通话中，麦克风正在使用" : "队伍连接与蓝牙网关正在运行")
            .setContentIntent(open).setOngoing(true).setCategory(Notification.CATEGORY_SERVICE)
            .addAction(new Notification.Action.Builder(null, "结束通话", stop).build())
            .addAction(new Notification.Action.Builder(null, "退出队伍", leave).build()).build();
    }
    private static int microphoneType() { return Build.VERSION.SDK_INT >= 30 ? ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE : 0; }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? "" : intent.getAction();
        if (STOP_CALL.equals(action)) stopCall();
        else if (LEAVE.equals(action)) { stopCall(); controller.leaveTeam(); }
        else if (!foreground) promote(ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        return START_NOT_STICKY;
    }
    @Override public void onDestroy() {
        main.removeCallbacksAndMessages(null);
        controller.close();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        super.onDestroy();
    }
}
