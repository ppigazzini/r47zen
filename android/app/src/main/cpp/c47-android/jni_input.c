#include "jni_bridge.h"

#include <stdio.h>
#include <string.h>

extern void btnFnPressed(GtkWidget *notUsed, GdkEvent *event, gpointer data);
extern void btnFnReleased(GtkWidget *notUsed, GdkEvent *event, gpointer data);
extern void btnPressed(GtkWidget *notUsed, GdkEvent *event, gpointer data);
extern void btnReleased(GtkWidget *notUsed, GdkEvent *event, gpointer data);

static char currentPressedKeyStr[4] = {0};
static int currentPressedKeyCode = 0;

void r47_send_sim_function(int funcId) {
  pthread_mutex_lock(&screenMutex);
  extern void runFunction(int16_t id);
  runFunction((int16_t)funcId);
  pthread_mutex_unlock(&screenMutex);
}

void r47_send_sim_menu(int menuId) {
  pthread_mutex_lock(&screenMutex);
  extern void showSoftmenu(int16_t id);
  showSoftmenu((int16_t)menuId);
  refreshScreen(1);
  pthread_mutex_unlock(&screenMutex);
}

void r47_send_sim_key(const char *keyId, bool isFn, bool isRelease) {
  if (!ram || isCoreBlockingForIo || keyId == NULL) {
    return;
  }

  pthread_mutex_lock(&screenMutex);
  if (isFn) {
    if (isRelease) {
      extern void btnFnClickedR(void *w, void *data);
      btnFnClickedR(NULL, (void *)keyId);
    } else {
      extern void btnFnClickedP(void *w, void *data);
      btnFnClickedP(NULL, (void *)keyId);
    }
  } else {
    if (isRelease) {
      extern void btnClickedR(void *w, void *data);
      btnClickedR(NULL, (void *)keyId);
    } else {
      extern void btnClickedP(void *w, void *data);
      btnClickedP(NULL, (void *)keyId);
    }
  }
  pthread_mutex_unlock(&screenMutex);
}

JNIEXPORT void JNICALL
Java_com_example_r47_MainActivity_sendSimFuncNative(
    JNIEnv *env, jobject thiz, jint funcId) {
  (void)env;
  (void)thiz;
  r47_send_sim_function((int)funcId);
}

JNIEXPORT void JNICALL
Java_com_example_r47_MainActivity_sendSimMenuNative(
    JNIEnv *env, jobject thiz, jint menuId) {
  (void)env;
  (void)thiz;
  r47_send_sim_menu((int)menuId);
}

JNIEXPORT void JNICALL
Java_com_example_r47_MainActivity_sendSimKeyNative(
    JNIEnv *env, jobject thiz, jstring keyId, jboolean isFn,
    jboolean isRelease) {
  (void)thiz;
  if (!ram || isCoreBlockingForIo) {
    return;
  }

  const char *nativeKeyId = (*env)->GetStringUTFChars(env, keyId, 0);
    if (!nativeKeyId ||
      jni_check_and_clear_exception(env,
                      "sendSimKeyNative GetStringUTFChars")) {
    return;
  }

  r47_send_sim_key(nativeKeyId, isFn, isRelease);
  (*env)->ReleaseStringUTFChars(env, keyId, nativeKeyId);
}

JNIEXPORT void JNICALL Java_com_example_r47_MainActivity_sendKey(
  JNIEnv *env, jobject thiz, jint keyCode) {
  (void)env;
  (void)thiz;
  if (!ram) {
    return;
  }

  onUIActivity();
  if (keyCode > 0) {
    LOGD("sendKey: DOWN %d", keyCode);
    currentPressedKeyCode = keyCode;
    pthread_mutex_lock(&screenMutex);
    if (keyCode >= 38 && keyCode <= 43) {
      snprintf(currentPressedKeyStr, sizeof(currentPressedKeyStr), "%c", keyCode - 38 + '1');
      btnFnPressed(NULL, &pressEvent, currentPressedKeyStr);
    } else if (keyCode >= 1 && keyCode <= 37) {
      snprintf(currentPressedKeyStr, sizeof(currentPressedKeyStr), "%02u", keyCode - 1);
      btnPressed(NULL, &pressEvent, currentPressedKeyStr);
    }
    pthread_mutex_unlock(&screenMutex);
    return;
  }

  LOGD("sendKey: UP (last=%d)", currentPressedKeyCode);
  pthread_mutex_lock(&screenMutex);
  if (currentPressedKeyCode >= 38 && currentPressedKeyCode <= 43) {
    btnFnReleased(NULL, &releaseEvent, currentPressedKeyStr);
  } else if (currentPressedKeyCode >= 1 && currentPressedKeyCode <= 37) {
    btnReleased(NULL, &releaseEvent, currentPressedKeyStr);
  }
  pthread_mutex_unlock(&screenMutex);
  currentPressedKeyCode = 0;
  currentPressedKeyStr[0] = 0;
}