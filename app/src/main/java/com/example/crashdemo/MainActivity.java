package com.example.crashdemo;

import androidx.appcompat.app.AppCompatActivity;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.widget.Toast;

import com.example.crashdemo.databinding.ActivityMainBinding;
import com.tencent.bugly.library.Bugly;
import com.tencent.bugly.library.BuglyAppVersionMode;
import com.tencent.bugly.library.BuglyBuilder;
import com.tencent.bugly.library.BuglyLogLevel;

public class MainActivity extends AppCompatActivity {

    // Used to load the 'crashdemo' library on application startup.
    static {
        System.loadLibrary("crashdemo");
    }

    private ActivityMainBinding binding;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        initBugly(this.getApplicationContext());

        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        binding.btnCrash.setOnClickListener(v -> {
            Toast.makeText(this, "开始泄漏 fd，稍后将因 fd 耗尽在创建线程时崩溃", Toast.LENGTH_SHORT).show();
            startFdLeakAndCrash();
        });

        binding.btnAnr.setOnClickListener(v -> {
            Toast.makeText(this, "已启动前台服务并注入洪水消息，约 20s 后触发 Service 超时 ANR", Toast.LENGTH_LONG).show();
            triggerForegroundServiceTimeoutAnr();
        });

        binding.btnNativeCrash.setOnClickListener(v -> {
            Toast.makeText(this, "触发堆越界写 Native Crash，请查看 tombstone", Toast.LENGTH_SHORT).show();
            triggerNativeCrash();
        });
    }

    /**
     * A native method that is implemented by the 'crashdemo' native library,
     * which is packaged with this application.
     */
    public native String stringFromJNI();

    /**
     * 触发 fd 缓慢泄漏直至耗尽，并在创建新线程时崩溃。
     */
    public native void startFdLeakAndCrash();

    /**
     * 触发堆越界写 Native Crash：崩溃堆栈停在 Use() 解引用处、看不出原因，
     * 需从 tombstone 的 fault addr（= 0x4141...）与堆内存的 'A' 填充反推是
     * 越界写覆盖了紧随 buf 之后的 data 指针字段。
     */
    public native void triggerNativeCrash();

    /**
     * 场景2：主线程 MessageQueue 被持续插入的消息堆积，导致 ForegroundService 创建超时 ANR。
     *
     * <h3>原理</h3>
     * <ul>
     *   <li>首条洪水消息塞到队头，执行时立即 {@code sendMessageAtFrontOfQueue} 下一条；</li>
     *   <li>每条忙等 ~1s，洪水持续超过前台服务 ANR 超时（SERVICE_TIMEOUT = 20s）；</li>
     *   <li>{@code startForegroundService} 的回调（{@code H.CREATE_SERVICE}）是普通 Handler 消息，
     *       被洪水挤在队尾无法执行 → AMS 判定 Service 超时 → ANR；</li>
     *   <li>ANR trace 中主线程在 {@code Handler.handleCallback} 执行某条洪水消息。</li>
     * </ul>
     */
    private void triggerForegroundServiceTimeoutAnr() {
        // 先触发前台服务创建，其 H.CREATE_SERVICE 消息会进入主线程队列尾部
        Intent serviceIntent = new Intent(this, ForegroundService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }

        final Handler mainHandler = new Handler(Looper.getMainLooper());
        // 洪水持续 25s，超过前台服务 ANR 超时（SERVICE_TIMEOUT = 20s）
        final long FLOOD_DURATION_MS = 25_000;
        final long floodStart = SystemClock.uptimeMillis();

        Runnable floodMessage = new Runnable() {
            @Override
            public void run() {
                long busyStart = SystemClock.uptimeMillis();
                // 每条洪水消息忙等 ~1s，占用主线程
                while (SystemClock.uptimeMillis() - busyStart < 1000) {
                    // 空转占用 CPU
                }
                Log.i("ANR", "洪水消息执行完成，已运行 " + (SystemClock.uptimeMillis() - floodStart) + "ms");
                // 洪水未结束则继续把下一条塞到队头，压住 CREATE_SERVICE
                if (SystemClock.uptimeMillis() - floodStart < FLOOD_DURATION_MS) {
                    mainHandler.postAtFrontOfQueue(this);
                } else {
                    Log.i("ANR", "洪水结束");
                }
            }
        };
        mainHandler.postAtFrontOfQueue(floodMessage);
    }


    private void initBugly(Context context) {
        // 1. 初始化参数预构建，必需设置初始化参数
        String appID = "5511654fcf"; // 【必需设置】在Bugly 专业版 注册产品的appID
        String appKey = "bfc8a9c4-5f87-440a-aad9-22c7be3adda2"; // 【必需设置】在Bugly 专业版 注册产品的appKey
        BuglyBuilder builder = new BuglyBuilder(appID, appKey);
        // 专业版：BuglyBuilder.ServerHostTypeBuglyPro
        // 海外版：BuglyBuilder.ServerHostTypeBuglyOversea
        builder.setServerHostType(BuglyBuilder.ServerHostTypeBuglyPro); // 设置数据上报通道为专业版，4.4.2.6开始支持切换数据上报通道，同一个SDK可以同时支持专业版和海外版

        // 2. 基本初始化参数，推荐设置初始化参数
        builder.uniqueId = "123456"; // 【推荐设置】设置设备唯一ID，必须保证唯一性，不设置则由Bugly生成唯一ID，影响设备异常率的统计以及联网设备数的统计,建议sp保存复用;
        builder.userId = "654321"; // 【推荐设置】设置用户ID，影响用户异常率的统计,建议sp保存复用，同一进程生命周期里面，暂不支持多次设置;
        builder.deviceModel = Build.MODEL; // 【推荐设置】设置设备类型，设置机型后，Bugly SDK不再读取系统的机型
        builder.appVersion = "1.0.0"; // 【推荐设置】设置App版本号，不设置则从packageManager中读取。建议按应用的规范，主动设置，需要跟上传符号表的应用版本参数保持一致。
        builder.buildNumber = "builderNum"; // 【推荐设置】设置App版本的构建号，用于Java堆栈翻译关联版本，跟上传符号表的构建号参数保持一致。
        builder.appVersionType = BuglyAppVersionMode.DEBUG; // 【推荐设置】设置构建类型

        // 3. 更多初始化参数，按需设置初始化参数
        builder.appChannel = "appChannel"; // 设置App的渠道
        builder.logLevel = BuglyLogLevel.LEVEL_VERBOS; // 设置日志打印级别，级别可从BuglyLogLevel中获取
        builder.enableAllThreadStackCrash = true; // 设置Crash时是否抓取全部线程堆栈，默认开启
        builder.enableAllThreadStackAnr = true; // 设置Anr时是否抓取全部线程堆栈，默认开启
        builder.enableCrashProtect = true; // 设置性能监控时开启Crash保护模式，默认开启
        builder.debugMode = true; // 设置debug模式，可在调试阶段开启
        builder.initAppState = BuglyBuilder.APP_STATE_FOREGROUND; // 自4.4.3.7版本起支持。该参数为非必选项，可在初始化 Bugly SDK 时指定应用的前后台状态。若未指定，SDK将在初始化时通过 getRunningAppProcesses 判断应用的前后台状态；若已指定，SDK将直接采用指定状态，不再调用 getRunningAppProcesses 进行判断。

        // 4. 设置回调方法，按需设置初始化参数
        builder.setEnableRecordAnrMainStack(true); // 设置ANR监控是否记录主线程堆栈， 默认false

        // 5. 初始化，必需调用
        Bugly.init(context, builder);
    }
}