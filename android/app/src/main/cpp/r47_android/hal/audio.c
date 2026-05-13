#include "c47.h"
#include "jni_bridge.h"

#include <unistd.h>

void beep(uint16_t freq, uint16_t duration) {}
void beep_tone(uint16_t freq, uint16_t duration) {}
void stop_beep(void) {}

uint16_t getBeepVolume(void) { return 80; }

void _Buzz(uint32_t frequency, uint32_t ms_delay) {
	if (!g_mainActivityObj || !g_jvm || !g_playToneId) {
		return;
	}

	if (frequency > 0) {
		jni_env_scope_t scope;
		if (!jni_acquire_env(&scope, "_Buzz")) {
			return;
		}

		(*scope.env)->CallVoidMethod(scope.env, g_mainActivityObj, g_playToneId,
																 (jint)frequency, (jint)ms_delay);
		jni_check_and_clear_exception(scope.env, "_Buzz CallVoidMethod(playTone)");
		jni_release_env(&scope, "_Buzz");
	}

	usleep((ms_delay + 10) * 1000);
}

void audioTone(uint32_t frequency) { _Buzz(frequency, 200); }

void fnSetVolume(uint16_t volume) { (void)volume; }
void fnGetVolume(uint16_t volume) { (void)volume; }
void fnVolumeUp(uint16_t unusedButMandatoryParameter) {
	(void)unusedButMandatoryParameter;
}
void fnVolumeDown(uint16_t unusedButMandatoryParameter) {
	(void)unusedButMandatoryParameter;
}
void fnBuzz(uint16_t unusedButMandatoryParameter) {
	(void)unusedButMandatoryParameter;
}
void fnPlay(uint16_t regist) { (void)regist; }
void squeak(void) { _Buzz(1000, 10); }
