#ifndef JNI_BRIDGE_H
#define JNI_BRIDGE_H

#include "c47.h"
#include "keypad_fixture_bridge.h"
#include <jni.h>
#include <pthread.h>
#include <stdbool.h>
#include <stdio.h>
#include <stdint.h>
#include <time.h>

#if !defined(LOGI) || !defined(LOGE) || !defined(LOGD)
#if defined(HOST_TOOL_BUILD)
#define LOG_TAG "R47Native"
#define LOGI(...)                                                              \
    do {                                                                         \
        fprintf(stderr, "I/%s: ", LOG_TAG);                                       \
        fprintf(stderr, __VA_ARGS__);                                              \
        fputc('\n', stderr);                                                      \
    } while (0)
#define LOGE(...)                                                              \
    do {                                                                         \
        fprintf(stderr, "E/%s: ", LOG_TAG);                                       \
        fprintf(stderr, __VA_ARGS__);                                              \
        fputc('\n', stderr);                                                      \
    } while (0)
#define LOGD(...)                                                              \
    do {                                                                         \
        fprintf(stderr, "D/%s: ", LOG_TAG);                                       \
        fprintf(stderr, __VA_ARGS__);                                              \
        fputc('\n', stderr);                                                      \
    } while (0)
#else
#include <android/log.h>

#define LOG_TAG "R47Native"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#endif
#endif

#define MAIN_ACTIVITY_CLASS "io/github/ppigazzini/r47/MainActivity"

extern JavaVM *g_jvm;
extern jobject g_mainActivityObj;
extern jmethodID g_requestFileId;
extern jmethodID g_playToneId;
extern jmethodID g_stopToneId;
extern jmethodID g_processCoreTasksId;

typedef struct {
    JNIEnv *env;
    bool attached_here;
} jni_env_scope_t;

static inline bool jni_check_and_clear_exception(JNIEnv *env,
                                                                                                 const char *context) {
    if (!env || !(*env)->ExceptionCheck(env)) {
        return false;
    }

    LOGE("%s: pending Java exception", context);
    (*env)->ExceptionDescribe(env);
    (*env)->ExceptionClear(env);
    return true;
}

static inline bool jni_result_ok(JNIEnv *env, const void *result,
                                                                 const char *context) {
    if (result == NULL) {
        LOGE("%s returned NULL", context);
    }
    if (jni_check_and_clear_exception(env, context)) {
        return false;
    }
    return result != NULL;
}

static inline bool jni_acquire_env(jni_env_scope_t *scope,
                                                                     const char *context) {
    if (scope == NULL) {
        LOGE("%s: scope is NULL", context);
        return false;
    }

    scope->env = NULL;
    scope->attached_here = false;
    if (g_jvm == NULL) {
        LOGE("%s: JVM reference is NULL", context);
        return false;
    }

    jint result = (*g_jvm)->GetEnv(g_jvm, (void **)&scope->env, JNI_VERSION_1_6);
    if (result == JNI_OK && scope->env != NULL) {
        return true;
    }

    if (result != JNI_EDETACHED) {
        LOGE("%s: GetEnv failed (%d)", context, result);
        return false;
    }

    jint attach_result = (*g_jvm)->AttachCurrentThread(g_jvm, &scope->env, NULL);
    if (attach_result != JNI_OK || scope->env == NULL) {
        LOGE("%s: AttachCurrentThread failed (%d)", context, attach_result);
        scope->env = NULL;
        return false;
    }

    scope->attached_here = true;
    return true;
}

static inline void jni_release_env(jni_env_scope_t *scope,
                                                                     const char *context) {
    if (scope == NULL) {
        return;
    }

    if (scope->attached_here && g_jvm != NULL) {
        jint detach_result = (*g_jvm)->DetachCurrentThread(g_jvm);
        if (detach_result != JNI_OK) {
            LOGE("%s: DetachCurrentThread failed (%d)", context, detach_result);
        }
    }

    scope->env = NULL;
    scope->attached_here = false;
}

static inline jstring jni_new_string_utf(JNIEnv *env, const char *value,
                                                                                 const char *fallback,
                                                                                 const char *context) {
    jstring result = (*env)->NewStringUTF(env, value ? value : "");
    if (result != NULL && !jni_check_and_clear_exception(env, context)) {
        return result;
    }

    if (fallback == NULL) {
        return NULL;
    }

    LOGE("%s: falling back to a default Java string", context);
    result = (*env)->NewStringUTF(env, fallback);
    if (result != NULL &&
            !jni_check_and_clear_exception(env, "jni_new_string_utf fallback")) {
        return result;
    }

    return NULL;
}

extern pthread_mutex_t fileMutex;
extern pthread_cond_t fileCond;
extern pthread_mutex_t screenMutex;
extern int fileDescriptor;
extern bool fileReady;
extern bool fileCancelled;
extern bool isCoreBlockingForIo;

extern gboolean ui_is_active;

extern uint32_t nextTimerRefresh;
extern uint32_t nextScreenRefresh;

extern GdkEvent pressEvent;
extern GdkEvent releaseEvent;

extern void set_android_base_path(const char *path);
extern void init_lcd_buffers(void);
extern void setupUI(void);
extern void lcd_clear_buf(void);
extern void lcd_refresh(void);
extern void refreshScreen(uint16_t reason);

extern void JNICALL Java_com_example_r47_MainActivity_setLcdColors(
    JNIEnv *env, jobject thiz, jint text, jint bg);

void onUIActivity(void);
gint64 g_get_monotonic_time(void);
gint64 g_get_real_time(void);
uint32_t sys_current_ms(void);
void processCoreTasksNative(void);
void yieldToAndroidWithMs(int ms);
void yieldToAndroid(void);
int requestAndroidFile(int isSave, const char *defaultName, int fileType);
void r47_set_file_request_override(int fd, int isSave, int fileType);
void r47_clear_file_request_override(void);
void triggerQuit(void);
int register_main_activity_natives(JNIEnv *env);
void releaseNativeActivityReferences(JNIEnv *env);

JNIEXPORT void JNICALL
Java_com_example_r47_MainActivity_updateNativeActivityRef(JNIEnv *env,
                                                                 jobject thiz);
JNIEXPORT void JNICALL
Java_com_example_r47_MainActivity_releaseNativeRuntime(JNIEnv *env,
                                                              jobject thiz);
JNIEXPORT void JNICALL Java_com_example_r47_MainActivity_nativePreInit(
    JNIEnv *env, jobject thiz, jstring path_obj);
JNIEXPORT void JNICALL Java_com_example_r47_MainActivity_initNative(
    JNIEnv *env, jobject thiz, jstring pathObj, jint slotId);
JNIEXPORT void JNICALL Java_com_example_r47_MainActivity_tick(
    JNIEnv *env, jobject thiz);
JNIEXPORT void JNICALL Java_com_example_r47_MainActivity_sendKey(
    JNIEnv *env, jobject thiz, jint keyCode);
JNIEXPORT void JNICALL Java_com_example_r47_MainActivity_sendSimKeyNative(
    JNIEnv *env, jobject thiz, jstring keyId, jboolean isFn,
    jboolean isRelease);
JNIEXPORT void JNICALL
Java_com_example_r47_MainActivity_sendSimMenuNative(JNIEnv *env,
                                                           jobject thiz,
                                                           jint menuId);
JNIEXPORT void JNICALL
Java_com_example_r47_MainActivity_sendSimFuncNative(JNIEnv *env,
                                                           jobject thiz,
                                                           jint funcId);
JNIEXPORT void JNICALL
Java_com_example_r47_MainActivity_saveStateNative(JNIEnv *env,
                                                         jobject thiz);
JNIEXPORT void JNICALL
Java_com_example_r47_MainActivity_loadStateNative(JNIEnv *env,
                                                         jobject thiz);
JNIEXPORT void JNICALL
Java_com_example_r47_MainActivity_forceRefreshNative(JNIEnv *env,
                                                            jobject thiz);
JNIEXPORT void JNICALL Java_com_example_r47_MainActivity_setSlotNative(
    JNIEnv *env, jobject thiz, jint slot);
JNIEXPORT jstring JNICALL
Java_com_example_r47_MainActivity_getXRegisterNative(JNIEnv *env,
                                                            jobject thiz);
JNIEXPORT void JNICALL
Java_com_example_r47_MainActivity_getPackedDisplayBuffer(JNIEnv *env,
                                                                jobject thiz,
                                                                jbyteArray buffer);
JNIEXPORT jstring JNICALL
Java_com_example_r47_MainActivity_getButtonLabelNative(JNIEnv *env,
                                                              jobject thiz,
                                                              jint keyCode,
                                                              jint type,
                                                              jboolean isDynamic);
JNIEXPORT jstring JNICALL
Java_com_example_r47_MainActivity_getSoftkeyLabelNative(JNIEnv *env,
                                                               jobject thiz,
                                                               jint fnKeyIndex);
JNIEXPORT jintArray JNICALL
Java_com_example_r47_MainActivity_getKeyboardStateNative(JNIEnv *env,
                                                                jobject thiz);
JNIEXPORT jintArray JNICALL
Java_com_example_r47_MainActivity_getKeypadMetaNative(JNIEnv *env,
                                                             jobject thiz,
                                                                                                                         jint mainKeyDynamicMode);
JNIEXPORT jobjectArray JNICALL
Java_com_example_r47_MainActivity_getKeypadLabelsNative(JNIEnv *env,
                                                               jobject thiz,
                                                                                                                             jint mainKeyDynamicMode);
JNIEXPORT void JNICALL
Java_com_example_r47_MainActivity_onFileSelectedNative(JNIEnv *env,
                                                              jobject thiz,
                                                              jint fd);
JNIEXPORT void JNICALL
Java_com_example_r47_MainActivity_onFileCancelledNative(JNIEnv *env,
                                                               jobject thiz);

#endif
