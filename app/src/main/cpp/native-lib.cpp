#include <jni.h>
#include <android/log.h>
#include <pthread.h>
#include <unistd.h>
#include <cstdio>
#include <cstdlib>
#include <cstring>

// Dideklarasikan manual agar tidak perlu header Node.
namespace node {
int Start(int argc, char* argv[]);
}

static int pfd[2];
static pthread_t reader;
static FILE* logFile = nullptr;

static void* readLoop(void*) {
    char buf[2048];
    ssize_t n;
    while ((n = read(pfd[0], buf, sizeof(buf) - 1)) > 0) {
        buf[n] = 0;
        __android_log_write(ANDROID_LOG_INFO, "NIZX-NODE", buf);
        if (logFile) {
            fwrite(buf, 1, (size_t)n, logFile);
            fflush(logFile);
        }
    }
    return nullptr;
}

// Alihkan stdout/stderr Node ke logcat dan file native.log.
static void redirectOutput(const char* path) {
    logFile = fopen(path, "a");
    setvbuf(stdout, nullptr, _IONBF, 0);
    setvbuf(stderr, nullptr, _IONBF, 0);
    if (pipe(pfd) == -1) return;
    dup2(pfd[1], 1);
    dup2(pfd[1], 2);
    if (pthread_create(&reader, nullptr, readLoop, nullptr) == 0) pthread_detach(reader);
}

extern "C" JNIEXPORT jint JNICALL
Java_id_nizx_wabotpoc_NodeBridge_start(JNIEnv* env, jobject, jobjectArray arguments, jstring logPath) {
    const char* lp = env->GetStringUTFChars(logPath, nullptr);
    redirectOutput(lp);
    env->ReleaseStringUTFChars(logPath, lp);

    jsize argc = env->GetArrayLength(arguments);
    size_t total = 0;
    for (jsize i = 0; i < argc; i++) {
        jstring s = (jstring)env->GetObjectArrayElement(arguments, i);
        const char* c = env->GetStringUTFChars(s, nullptr);
        total += strlen(c) + 1;
        env->ReleaseStringUTFChars(s, c);
    }
    // Node (libuv) mewajibkan argumen berada di memori yang berurutan.
    char* buffer = (char*)calloc(total + 1, 1);
    char** argv = (char**)calloc((size_t)argc + 1, sizeof(char*));
    char* pos = buffer;
    for (jsize i = 0; i < argc; i++) {
        jstring s = (jstring)env->GetObjectArrayElement(arguments, i);
        const char* c = env->GetStringUTFChars(s, nullptr);
        size_t len = strlen(c);
        memcpy(pos, c, len);
        argv[i] = pos;
        pos += len + 1;
        env->ReleaseStringUTFChars(s, c);
    }
    return (jint)node::Start((int)argc, argv);
}
