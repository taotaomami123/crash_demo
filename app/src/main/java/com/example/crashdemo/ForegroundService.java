package com.example.crashdemo;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

/**
 * 用于演示「前台服务创建超时 ANR」的前台服务。
 *
 * 正常情况下，startForegroundService 后，主线程会尽快执行 H.CREATE_SERVICE，
 * 触发本服务的 onCreate() 并在其中调用 startForeground()。
 * 但当主线程消息队列被洪水消息持续占满时，H.CREATE_SERVICE 被压到队尾无法执行，
 * 服务迟迟无法创建 → AMS 判定前台服务超时 → ANR。
 */
public class ForegroundService extends Service {

    private static final String TAG = "ANR";
    private static final String CHANNEL_ID = "crashdemo_fgs";

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "ForegroundService.onCreate() 被调用");
        startForegroundCompat();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i(TAG, "ForegroundService.onStartCommand()");
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void startForegroundCompat() {
        createNotificationChannel();
        Notification notification = buildNotification();
        startForeground(1, notification);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "CrashDemo Foreground Service",
                    NotificationManager.IMPORTANCE_LOW);
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                nm.createNotificationChannel(channel);
            }
        }
    }

    private Notification buildNotification() {
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }
        return builder
                .setSmallIcon(android.R.drawable.ic_menu_info_details)
                .setContentTitle("CrashDemo 前台服务")
                .setContentText("用于演示 Service 创建超时 ANR")
                .build();
    }
}
