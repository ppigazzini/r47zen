#include "c47.h"
#include "jni_bridge.h"

// The app keeps no beep volume of its own, so the VOL softkeys show no value: NOVAL,
// as GTK's getBeepVolume answers, where a number would read as a volume on a 0 to 11
// scale.
uint16_t getBeepVolume(void) { return (uint16_t)NOVAL; }

static bool tonesSinceRest = false; // calculator thread only

// A note takes its time, as on the hardware: a program's notes are spaced in
// program time, and a TONE loop cannot outrun the audio queue.
void _Buzz(uint32_t frequency, uint32_t ms_delay) {
  if (frequency > 0) {
    tonesSinceRest = true;
    r47_upcall_play_tone((int)frequency, (int)ms_delay);
  }
  sys_delay(ms_delay);
}

// The calculator is at rest, so a note still queued belongs to a run that is over.
void r47_tones_at_rest(void) {
  if (tonesSinceRest) {
    tonesSinceRest = false;
    r47_upcall_tones_clear();
  }
}

void audioTone(uint32_t frequency) { _Buzz(frequency, 250); }

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
