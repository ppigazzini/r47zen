#include "jni_bridge.h"

#include <string.h>

#ifndef PTHREAD_MUTEX_RECURSIVE
#define PTHREAD_MUTEX_RECURSIVE PTHREAD_MUTEX_RECURSIVE_NP
#endif

void r47_initialize_native_bridge_state(void) {
  pthread_mutexattr_t attr;
  pthread_mutexattr_init(&attr);
  pthread_mutexattr_settype(&attr, PTHREAD_MUTEX_RECURSIVE);
  pthread_mutex_init(&screenMutex, &attr);
  pthread_mutexattr_destroy(&attr);

  memset(&pressEvent, 0, sizeof(GdkEvent));
  pressEvent.type = GDK_BUTTON_PRESS;
  pressEvent.button.button = 1;

  memset(&releaseEvent, 0, sizeof(GdkEvent));
  releaseEvent.type = GDK_BUTTON_RELEASE;
  releaseEvent.button.button = 1;
}

jint JNI_OnLoad(JavaVM *vm, void *reserved) {
  (void)reserved;
  g_jvm = vm;

  JNIEnv *env;
  if ((*vm)->GetEnv(vm, (void **)&env, JNI_VERSION_1_6) != JNI_OK) {
    return JNI_ERR;
  }

  r47_initialize_native_bridge_state();

  if (register_main_activity_natives(env) != JNI_OK) {
    return JNI_ERR;
  }

  return JNI_VERSION_1_6;
}

int register_main_activity_natives(JNIEnv *env) {
  static const JNINativeMethod methods[] = {
      {"updateNativeActivityRef", "()V",
        (void *)Java_com_example_r47_MainActivity_updateNativeActivityRef},
      {"releaseNativeRuntime", "()V",
        (void *)Java_com_example_r47_MainActivity_releaseNativeRuntime},
      {"nativePreInit", "(Ljava/lang/String;)V",
        (void *)Java_com_example_r47_MainActivity_nativePreInit},
      {"initNative", "(Ljava/lang/String;I)V",
        (void *)Java_com_example_r47_MainActivity_initNative},
       {"tick", "()V", (void *)Java_com_example_r47_MainActivity_tick},
       {"sendKey", "(I)V", (void *)Java_com_example_r47_MainActivity_sendKey},
      {"sendSimKeyNative", "(Ljava/lang/String;ZZ)V",
        (void *)Java_com_example_r47_MainActivity_sendSimKeyNative},
      {"sendSimMenuNative", "(I)V",
        (void *)Java_com_example_r47_MainActivity_sendSimMenuNative},
      {"sendSimFuncNative", "(I)V",
        (void *)Java_com_example_r47_MainActivity_sendSimFuncNative},
      {"saveStateNative", "()V",
        (void *)Java_com_example_r47_MainActivity_saveStateNative},
      {"loadStateNative", "()V",
        (void *)Java_com_example_r47_MainActivity_loadStateNative},
      {"forceRefreshNative", "()V",
        (void *)Java_com_example_r47_MainActivity_forceRefreshNative},
      {"setSlotNative", "(I)V",
        (void *)Java_com_example_r47_MainActivity_setSlotNative},
      {"getXRegisterNative", "()Ljava/lang/String;",
        (void *)Java_com_example_r47_MainActivity_getXRegisterNative},
      {"getPackedDisplayBuffer", "([B)V",
        (void *)Java_com_example_r47_MainActivity_getPackedDisplayBuffer},
      {"setLcdColors", "(II)V",
        (void *)Java_com_example_r47_MainActivity_setLcdColors},
      {"getButtonLabelNative", "(IIZ)Ljava/lang/String;",
        (void *)Java_com_example_r47_MainActivity_getButtonLabelNative},
      {"getSoftkeyLabelNative", "(I)Ljava/lang/String;",
        (void *)Java_com_example_r47_MainActivity_getSoftkeyLabelNative},
      {"getKeyboardStateNative", "()[I",
        (void *)Java_com_example_r47_MainActivity_getKeyboardStateNative},
      {"getKeypadMetaNative", "(I)[I",
        (void *)Java_com_example_r47_MainActivity_getKeypadMetaNative},
      {"getKeypadLabelsNative", "(I)[Ljava/lang/String;",
        (void *)Java_com_example_r47_MainActivity_getKeypadLabelsNative},
      {"onFileSelectedNative", "(I)V",
        (void *)Java_com_example_r47_MainActivity_onFileSelectedNative},
      {"onFileCancelledNative", "()V",
        (void *)Java_com_example_r47_MainActivity_onFileCancelledNative},
  };

  jclass clazz = (*env)->FindClass(env, MAIN_ACTIVITY_CLASS);
  if (!jni_result_ok(env, clazz, "FindClass(MainActivity)")) {
    LOGE("Failed to find %s for RegisterNatives", MAIN_ACTIVITY_CLASS);
    return JNI_ERR;
  }

  if ((*env)->RegisterNatives(env, clazz, methods,
                              sizeof(methods) / sizeof(methods[0])) != 0 ||
      jni_check_and_clear_exception(env, "RegisterNatives(MainActivity)")) {
    LOGE("RegisterNatives failed for %s", MAIN_ACTIVITY_CLASS);
    (*env)->DeleteLocalRef(env, clazz);
    return JNI_ERR;
  }

  (*env)->DeleteLocalRef(env, clazz);
  return JNI_OK;
}
