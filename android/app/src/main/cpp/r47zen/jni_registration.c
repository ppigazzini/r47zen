#include "jni_bridge.h"
#include "host_services.h"

#include <string.h>

jint JNI_OnLoad(JavaVM *vm, void *reserved) {
  (void)reserved;
  g_jvm = vm;

  JNIEnv *env;
  if ((*vm)->GetEnv(vm, (void **)&env, JNI_VERSION_1_6) != JNI_OK) {
    return JNI_ERR;
  }

  if (register_calculator_engine_natives(env) != JNI_OK) {
    return JNI_ERR;
  }

  return JNI_VERSION_1_6;
}

int register_calculator_engine_natives(JNIEnv *env) {
  /*
   * Register every CalculatorEngine external here, so a name or signature that
   * drifts from its Kotlin declaration fails JNI_OnLoad instead of the first
   * call. The implementations carry the JNI names of
   * io.github.ppigazzini.r47zen.CalculatorEngine, so each one is also found from
   * its Kotlin method by name.
   */
  static const JNINativeMethod methods[] = {
      {"runCalculatorNative", "(Ljava/lang/String;)V",
       (void *)
           Java_io_github_ppigazzini_r47zen_CalculatorEngine_runCalculatorNative},
      {"stopCalculatorNative", "(Z)V",
       (void *)
           Java_io_github_ppigazzini_r47zen_CalculatorEngine_stopCalculatorNative},
      {"setAutoSaveNative", "(Z)V",
       (void *)
           Java_io_github_ppigazzini_r47zen_CalculatorEngine_setAutoSaveNative},
      {"isReadyNative", "()Z",
       (void *)Java_io_github_ppigazzini_r47zen_CalculatorEngine_isReadyNative},
      {"onHostStoppedNative", "(Z)V",
       (void *)
           Java_io_github_ppigazzini_r47zen_CalculatorEngine_onHostStoppedNative},
      {"sendKey", "(I)V",
       (void *)Java_io_github_ppigazzini_r47zen_CalculatorEngine_sendKey},
      {"cancelKey", "()V",
       (void *)Java_io_github_ppigazzini_r47zen_CalculatorEngine_cancelKey},
      {"releaseKeyIfDown", "()V",
       (void *)
           Java_io_github_ppigazzini_r47zen_CalculatorEngine_releaseKeyIfDown},
      {"sendPcKeyNative", "(IIZ)V",
       (void *)
           Java_io_github_ppigazzini_r47zen_CalculatorEngine_sendPcKeyNative},
      {"graphGestureNative", "(FFF)V",
       (void *)
           Java_io_github_ppigazzini_r47zen_CalculatorEngine_graphGestureNative},
      {"resetGraphNative", "()V",
       (void *)
           Java_io_github_ppigazzini_r47zen_CalculatorEngine_resetGraphNative},
      {"pasteNumberNative", "(Ljava/lang/String;)Z",
       (void *)
           Java_io_github_ppigazzini_r47zen_CalculatorEngine_pasteNumberNative},
      {"saveStateNative", "(I)Z",
       (void *)
           Java_io_github_ppigazzini_r47zen_CalculatorEngine_saveStateNative},
      {"copyRegistersNative", "(I)Z",
       (void *)
           Java_io_github_ppigazzini_r47zen_CalculatorEngine_copyRegistersNative},
      {"describeDisplayNative", "()Z",
       (void *)
           Java_io_github_ppigazzini_r47zen_CalculatorEngine_describeDisplayNative},
      {"getPackedDisplayGeneration", "()I",
       (void *)
           Java_io_github_ppigazzini_r47zen_CalculatorEngine_getPackedDisplayGeneration},
      {"getPackedDisplayBuffer", "([B)Z",
       (void *)
           Java_io_github_ppigazzini_r47zen_CalculatorEngine_getPackedDisplayBuffer},
      {"getKeypadSnapshotGeneration", "()I",
       (void *)
           Java_io_github_ppigazzini_r47zen_CalculatorEngine_getKeypadSnapshotGeneration},
      {"copyKeypadSnapshotNative", "(I[I[Ljava/lang/String;)Z",
       (void *)
           Java_io_github_ppigazzini_r47zen_CalculatorEngine_copyKeypadSnapshotNative},
      {"onFileSelectedNative", "(ILjava/lang/String;)Z",
       (void *)
           Java_io_github_ppigazzini_r47zen_CalculatorEngine_onFileSelectedNative},
      {"onFileCanceledNative", "()V",
       (void *)
           Java_io_github_ppigazzini_r47zen_CalculatorEngine_onFileCanceledNative},
  };

  jclass clazz = (*env)->FindClass(env, CALCULATOR_ENGINE_CLASS);
  if (!jni_result_ok(env, clazz, "FindClass(CalculatorEngine)")) {
    LOGE("Failed to find %s for RegisterNatives", CALCULATOR_ENGINE_CLASS);
    return JNI_ERR;
  }

  if ((*env)->RegisterNatives(env, clazz, methods,
                              sizeof(methods) / sizeof(methods[0])) != 0 ||
      jni_check_and_clear_exception(env, "RegisterNatives(CalculatorEngine)")) {
    LOGE("RegisterNatives failed for %s", CALCULATOR_ENGINE_CLASS);
    (*env)->DeleteLocalRef(env, clazz);
    return JNI_ERR;
  }

  bool bound = r47_upcalls_bind(env, clazz);
  (*env)->DeleteLocalRef(env, clazz);
  return bound ? JNI_OK : JNI_ERR;
}
