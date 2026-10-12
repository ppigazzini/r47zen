// The display JNI surface: the keypad snapshot and the packed LCD, each a
// generation read plus a copy that never blocks the UI thread on the core.
// keypad_scene.c resolves the scene these calls copy out.
#include "jni_bridge.h"

#include "hal/lcd.h"

#include <stdatomic.h>
#include <stdbool.h>

static bool writeExportedKeypadLabelsToArray(
    JNIEnv *env, jobjectArray labels,
    char nativeLabels[R47_KEYPAD_KEY_COUNT * R47_KEYPAD_LABELS_PER_KEY]
                     [R47_KEYPAD_LABEL_CAPACITY]) {
  jstring empty = jni_new_string_utf(
      env, "", NULL, "copyKeypadSnapshotNative empty NewStringUTF");
  if (empty == NULL) {
    return false;
  }

  bool success = true;
  for (int index = 0; index < R47_KEYPAD_KEY_COUNT * R47_KEYPAD_LABELS_PER_KEY;
       index++) {
    jstring value = empty;
    if (nativeLabels[index][0] != 0) {
      value = jni_new_string_utf(env, nativeLabels[index], "",
                                 "copyKeypadSnapshotNative label NewStringUTF");
      if (value == NULL) {
        success = false;
        break;
      }
    }

    (*env)->SetObjectArrayElement(env, labels, index, value);
    if (jni_check_and_clear_exception(
            env, "copyKeypadSnapshotNative SetObjectArrayElement")) {
      success = false;
      if (value != empty) {
        (*env)->DeleteLocalRef(env, value);
      }
      break;
    }

    if (value != empty) {
      (*env)->DeleteLocalRef(env, value);
    }
  }

  (*env)->DeleteLocalRef(env, empty);
  return success;
}

JNIEXPORT jint JNICALL
Java_io_github_ppigazzini_r47zen_MainActivity_getKeypadSnapshotGeneration(
    JNIEnv *env, jobject thiz) {
  (void)env;
  (void)thiz;
  extern _Atomic uint32_t keypadSnapshotGeneration;

  return (jint)atomic_load_explicit(&keypadSnapshotGeneration,
                                    memory_order_relaxed);
}

JNIEXPORT jboolean JNICALL
Java_io_github_ppigazzini_r47zen_MainActivity_copyKeypadSnapshotNative(
    JNIEnv *env, jobject thiz, jint mainKeyDynamicMode, jintArray metaBuffer,
    jobjectArray labelsBuffer) {
  (void)thiz;
  if (metaBuffer == NULL || labelsBuffer == NULL) {
    return JNI_FALSE;
  }

  if ((*env)->GetArrayLength(env, metaBuffer) < R47_KEYPAD_META_LENGTH ||
      (*env)->GetArrayLength(env, labelsBuffer) <
          (R47_KEYPAD_KEY_COUNT * R47_KEYPAD_LABELS_PER_KEY)) {
    return JNI_FALSE;
  }

  jint fill[R47_KEYPAD_META_LENGTH];
  char labels[R47_KEYPAD_KEY_COUNT * R47_KEYPAD_LABELS_PER_KEY]
             [R47_KEYPAD_LABEL_CAPACITY];
  if (!r47_try_copy_app_keypad_snapshot((int32_t *)fill, labels,
                                        (int32_t)mainKeyDynamicMode)) {
    return JNI_FALSE;
  }

  (*env)->SetIntArrayRegion(env, metaBuffer, 0, R47_KEYPAD_META_LENGTH, fill);
  if (jni_check_and_clear_exception(
          env, "SetIntArrayRegion(copyKeypadSnapshotNative)")) {
    return JNI_FALSE;
  }

  return writeExportedKeypadLabelsToArray(env, labelsBuffer, labels)
             ? JNI_TRUE
             : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_io_github_ppigazzini_r47zen_MainActivity_getPackedDisplayGeneration(
    JNIEnv *env, jobject thiz) {
  (void)env;
  (void)thiz;
  extern _Atomic uint32_t packedDisplayGeneration;

  return (jint)atomic_load_explicit(&packedDisplayGeneration,
                                    memory_order_relaxed);
}

// The UI-thread copy behind getPackedDisplayBuffer, and the one the TSan
// harness races against the core: the ready gate's acquire load publishes the
// packedDisplayBuffer pointer that init_lcd_buffers writes on the core thread.
bool r47_try_take_packed_display(uint8_t *dst) {
  if (!r47_runtime_ready()) {
    return false;
  }
  return lcd_try_take_packed_snapshot(dst);
}

// Copy straight into the Java array: between the critical get and release this
// does a trylock, a memcpy and a flag clear, never a JNI call or a blocking
// wait, which is what a critical region allows.
JNIEXPORT jboolean JNICALL
Java_io_github_ppigazzini_r47zen_MainActivity_getPackedDisplayBuffer(
    JNIEnv *env, jobject thiz, jbyteArray buffer) {
  (void)thiz;
  const jsize packedBytes = (jsize)(SCREEN_HEIGHT * LCD_ROW_SIZE_BYTES);
  if ((*env)->GetArrayLength(env, buffer) < packedBytes) {
    return JNI_FALSE;
  }

  jbyte *dst = (jbyte *)(*env)->GetPrimitiveArrayCritical(env, buffer, NULL);
  if (!jni_result_ok(env, dst,
                     "GetPrimitiveArrayCritical(getPackedDisplayBuffer)")) {
    return JNI_FALSE;
  }
  const bool taken = r47_try_take_packed_display((uint8_t *)dst);
  (*env)->ReleasePrimitiveArrayCritical(env, buffer, dst,
                                        taken ? 0 : JNI_ABORT);
  return taken ? JNI_TRUE : JNI_FALSE;
}
