#include <jni.h>
#include <string>

#include <unistd.h>
#include <fcntl.h>
#include <sys/socket.h>
#include <sys/epoll.h>
#include <sys/eventfd.h>
#include <sys/resource.h>
#include <dirent.h>
#include <pthread.h>
#include <atomic>
#include <vector>
#include <cerrno>
#include <cstring>
#include <cstdlib>

#include <android/log.h>

#define LOG_TAG "CrashDemo"

namespace {

// 保存泄漏的 fd，保持强持有，确保这些 fd 一直不被关闭
std::vector<int> g_leaked_fds;
// 控制 looper 泄漏线程的退出（本 demo 中恒为 false，线程永不退出）
std::atomic<bool> g_looper_quit{false};

// 统计当前进程已打开的 fd 数量（通过 /proc/self/fd 目录项数）
int CountOpenFds() {
    DIR* d = opendir("/proc/self/fd");
    if (d == nullptr) {
        return -1;
    }
    int count = 0;
    while (readdir(d) != nullptr) {
        ++count;
    }
    closedir(d);
    // opendir 本身占用了 1 个 fd（即 /proc/self/fd 目录），需要减掉
    return count > 0 ? count - 1 : 0;
}

// 判断 fd 是否已耗尽（open 失败且 errno 为 EMFILE/ENFILE）
bool IsFdExhausted() {
    int fd = open("/dev/null", O_RDONLY);
    if (fd >= 0) {
        close(fd);
        return false;
    }
    return (errno == EMFILE || errno == ENFILE);
}

// 模拟一个 Looper 线程：初始化时创建 epoll + eventfd 两个 fd，
// 随后挂起不退出，导致这两个 fd 一直被占用（模拟 HandlerThread/Looper 未退出）
void* LooperThreadRoutine(void*) {
    int epfd = epoll_create(1);
    int evfd = eventfd(0, EFD_NONBLOCK);
    // 挂起，模拟 Looper 一直运行、线程未退出
    while (!g_looper_quit.load(std::memory_order_relaxed)) {
        usleep(200 * 1000);
    }
    // 注意：这里故意不 close(epfd)/close(evfd)，用于复现 looper 线程未退出导致的 fd 泄漏
    return nullptr;
}

// 最终触发崩溃的线程：模拟新建线程（如 Looper）初始化时需要创建 epoll fd，
// 此时进程 fd 已耗尽，epoll_create 失败 -> abort()
// 崩溃堆栈定位在"创建线程"后的初始化阶段
void* FinalCrashThreadRoutine(void*) {
    int epfd = epoll_create(1);
    if (epfd < 0) {
        __android_log_print(ANDROID_LOG_FATAL, LOG_TAG,
                            "[fd-leak] new thread init failed: epoll_create errno=%d (%s), current fd=%d",
                            errno, strerror(errno), CountOpenFds());
        abort();
    }
    return nullptr;
}

// 泄漏工作线程：缓慢、持续地泄漏多种类型的 fd，直到 fd 耗尽
void* LeakWorkerRoutine(void*) {
    struct rlimit rlim{};
    if (getrlimit(RLIMIT_NOFILE, &rlim) != 0) {
        rlim.rlim_cur = 1024;
        rlim.rlim_max = 1024;
    }

    // Android 默认 soft limit 常为 1024 甚至更高，按 30ms/个 的速率会泄漏很久、难以观察。
    // 这里主动把 soft limit 调低到演示友好的 512，保证几十秒内就能触发崩溃。
    struct rlimit new_lim = rlim;
    rlim_t demo_soft = 512;
    if (rlim.rlim_max < demo_soft) {
        demo_soft = rlim.rlim_max;
    }
    new_lim.rlim_cur = demo_soft;
    if (setrlimit(RLIMIT_NOFILE, &new_lim) == 0) {
        rlim.rlim_cur = demo_soft;
    }

    __android_log_print(ANDROID_LOG_INFO, LOG_TAG,
                        "[fd-leak] start leaking: RLIMIT_NOFILE soft=%ld hard=%ld, current fd=%d",
                        (long)rlim.rlim_cur, (long)rlim.rlim_max, CountOpenFds());

    int round = 0;
    // 循环泄漏多种类型的 fd，直到耗尽
    while (!IsFdExhausted()) {
        switch (round % 3) {
            case 0: {
                // 类型 1：文件流未关闭（对应 Java FileInputStream 未 close）
                int fd = open("/dev/null", O_RDONLY);
                if (fd >= 0) {
                    g_leaked_fds.push_back(fd);
                }
                break;
            }
            case 1: {
                // 类型 2：socket 未关闭（对应 Java Socket 未 close）
                int fd = socket(AF_INET, SOCK_STREAM, 0);
                if (fd >= 0) {
                    g_leaked_fds.push_back(fd);
                }
                break;
            }
            case 2: {
                // 类型 3：looper 线程未退出（每个线程泄漏 epoll + eventfd 两个 fd）
                pthread_t t;
                if (pthread_create(&t, nullptr, LooperThreadRoutine, nullptr) == 0) {
                    pthread_detach(t);
                }
                break;
            }
        }
        ++round;
        // 每 50 轮打印一次进度，方便在 logcat 中观察 fd 数量持续增长
        if (round % 50 == 0) {
            __android_log_print(ANDROID_LOG_INFO, LOG_TAG,
                                "[fd-leak] progress: round=%d, current fd=%d/%ld",
                                round, CountOpenFds(), (long)rlim.rlim_cur);
        }
        usleep(30 * 1000); // 30ms，模拟缓慢持续泄漏
    }

    int used = CountOpenFds();
    __android_log_print(ANDROID_LOG_WARN, LOG_TAG,
                        "[fd-leak] fd exhausted: used=%d/%ld, leaked file/socket fd count=%zu, now create a new thread to trigger crash",
                        used, (long)rlim.rlim_cur, g_leaked_fds.size());

    // 触发崩溃：fd 耗尽后创建新线程，新线程初始化（epoll_create）失败 -> abort
    pthread_t crash_thread;
    int rc = pthread_create(&crash_thread, nullptr, FinalCrashThreadRoutine, nullptr);
    if (rc != 0) {
        __android_log_print(ANDROID_LOG_FATAL, LOG_TAG,
                            "[fd-leak] pthread_create failed: rc=%d (%s)", rc, strerror(rc));
        abort();
    }
    pthread_join(crash_thread, nullptr);
    return nullptr;
}

} // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_crashdemo_MainActivity_stringFromJNI(
        JNIEnv* env,
        jobject /* this */) {
    std::string hello = "Hello from C++";
    return env->NewStringUTF(hello.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_crashdemo_MainActivity_startFdLeakAndCrash(
        JNIEnv* /* env */,
        jobject /* this */) {
    // 在独立线程中执行泄漏流程，避免阻塞调用线程（UI）
    pthread_t worker;
    if (pthread_create(&worker, nullptr, LeakWorkerRoutine, nullptr) == 0) {
        pthread_detach(worker);
    }
}

// ======================================================================
// Native Crash：堆缓冲区溢出，覆盖对象指针字段（heap buffer overflow）
//
// 崩溃点：UseNode 中解引用被 'A' 覆盖的指针字段 n->data -> SIGSEGV。
// 仅从 backtrace 无法看出根因：堆栈停在 UseNode 的一次"看似正常"的
// 指针解引用处，看不出 n->data 为什么变成了 0x4141414141414141。
//
// 但结合 tombstone 可以定位根因：
//   1. fault addr = 0x4141414141414141（即 'AAAA'），说明解引用的是个被
//      0x41 填充的"假指针"
//   2. tombstone 的 "memory near" 区域显示对象 a 的堆内存被 'A'(0x41)
//      填充，其中紧随 buf 之后的 data 指针字段被覆盖
// 由此可以判断：对象 a 的 buf 字段被越界写入，'A'(0x41) 覆盖了紧随其后的
// data 指针字段，导致后续解引用 data 时跳转到非法地址 0x41414141...。
// ======================================================================

struct Node {
    char buf[16];
    void* data;  // 指针字段，紧跟在 buf 之后
};

__attribute__((noinline))
static void UseNode(Node* n) {
    // 模拟正常业务逻辑：读取 data 指向的内容
    volatile char x = *(volatile char*)n->data;
    (void)x;
}

__attribute__((noinline))
static void TriggerHeapOverflow() {
    Node* a = (Node*)malloc(sizeof(Node));
    Node* b = (Node*)malloc(sizeof(Node));
    if (a == nullptr || b == nullptr) {
        return;
    }
    a->data = b;  // data 原本指向一个合法对象 b

    // 越界写：a->buf 只有 16 字节，却写入 24 字节，覆盖紧随其后的 data 指针字段。
    // 用 volatile 指针访问，避免编译器优化掉越界写。
    volatile char* p = a->buf;
    for (int i = 0; i < 24; ++i) {
        p[i] = 'A';
    }

    // 使用 a->data（已被覆盖为 0x4141414141414141）-> SIGSEGV
    UseNode(a);
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_crashdemo_MainActivity_triggerNativeCrash(
        JNIEnv* /* env */,
        jobject /* this */) {
    TriggerHeapOverflow();
    // 正常情况不会执行到这里（UseNode 解引用时已经崩溃）
    __android_log_print(ANDROID_LOG_INFO, LOG_TAG, "should not reach here");
}