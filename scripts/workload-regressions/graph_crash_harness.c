// Host reproduction harness for the reported fast-pan graph crash.
// Replicates: EQN -> exp(x) -> graph/draw -> zoom in (no curve in view) ->
// pan the x-window left/right very fast (re-solving every batch) for a long
// run, the way the Android gesture flush drives fnEqSolvGraph(EQ_PLOT_LU).
// Built with AddressSanitizer so any native fault prints a stack trace.

#include "keypad_fixture_bridge.h"
#include "screen.h"

#include <math.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>

extern void r47_initialize_native_bridge_state(void);
extern void r47_native_preinit_path(const char *path);
extern void r47_init_runtime(int slotId);

extern void fnEqNew(uint16_t param);
extern void setEquation(uint16_t equationId, const char *equationString);
extern void parseEquation(uint16_t equationId, uint16_t parseMode, char *buffer,
                          char *mvarBuffer);
extern void fnEqSolvGraph(uint16_t func);
extern void convertDoubleToReal34Register(double value, calcRegister_t regist);

// currentFormula, currentSolverVariable, graphVariabl1, currentSolverStatus,
// calcMode, lastErrorCode, aimBuffer, tmpString, getNthString and
// findOrAllocateNamedVariable are declared by the included core headers.
extern float x_min, x_max, y_min, y_max;
extern int8_t PLOT_ZMY;
extern int32_t numberOfFreeMemoryRegions;

static void set_window(double lx, double ux, double ly, double uy) {
  convertDoubleToReal34Register(lx, RESERVED_VARIABLE_LX);
  convertDoubleToReal34Register(ux, RESERVED_VARIABLE_UX);
  convertDoubleToReal34Register(ly, RESERVED_VARIABLE_LY);
  convertDoubleToReal34Register(uy, RESERVED_VARIABLE_UY);
  x_min = (float)lx;
  x_max = (float)ux;
  y_min = (float)ly;
  y_max = (float)uy;
}

int main(void) {
  const char *runtime_dir = "/tmp/r47-graph-crash-harness";
  mkdir(runtime_dir, 0700);

  r47_initialize_native_bridge_state();
  r47_native_preinit_path(runtime_dir);
  r47_init_runtime(0);

  // EQN -> new equation "exp(x)".
  fnEqNew(NOPARAM);
  setEquation(currentFormula, "exp(x)");

  // Resolve the graphed variable the way showSoftmenu(-MNU_GRAPHS) does:
  // parse the equation's variable list and take the single variable.
  currentSolverStatus =
      (currentSolverStatus & ~SOLVER_STATUS_EQUATION_MODE) |
      SOLVER_STATUS_EQUATION_GRAPHER;
  parseEquation(currentFormula, EQUATION_PARSER_MVAR, aimBuffer, tmpString);
  currentSolverVariable =
      findOrAllocateNamedVariable((char *)getNthString((uint8_t *)tmpString, 0));
  graphVariabl1 = currentSolverVariable;
  calcMode = CM_GRAPH;

  fprintf(stderr, "setup: currentFormula=%u currentSolverVariable=%u\n",
          currentFormula, currentSolverVariable);

  // Initial draw of a zoomed-in window where exp(x) is not in the y-range
  // (no curve visible), matching the repro.
  PLOT_ZMY = zoomOverride;
  set_window(-0.001, 0.001, 100.0, 100.001);
  fnEqSolvGraph(EQ_PLOT_LU);
  fprintf(stderr, "initial draw done, lastErrorCode=%u\n", lastErrorCode);

  // Fast pan: oscillate the x-window left/right while a slow drift sweeps the
  // center across a wide range (including the exp() float-overflow region near
  // x=88), re-solving every iteration like the gesture flush.
  const double span = 0.002;     // zoomed-in x-span
  double center = 0.0;
  double drift = 0.0008;         // sweeps center across the run
  const long iterations = 3000000;
  for (long i = 0; i < iterations; i++) {
    double dir = (i & 1) ? -1.0 : 1.0;          // left/right/left/right
    center += dir * 0.45 * span + drift;        // oscillate + sweep right
    if (center > 130.0) {                       // bounce the sweep back
      center = 130.0;
      drift = -drift;
    } else if (center < -130.0) {
      center = -130.0;
      drift = -drift;
    }
    const double lx = center - span * 0.5;
    const double ux = center + span * 0.5;
    set_window(lx, ux, 100.0, 100.001);
    PLOT_ZMY = zoomOverride;
    if (getenv("R47_NO_SOLVE") == NULL) {
      fnEqSolvGraph(EQ_PLOT_LU);
    }

    if ((i % 200) == 0) {
      fprintf(stderr, "REGIONS iter=%ld freeRegions=%d center=%.4f err=%u\n", i,
              numberOfFreeMemoryRegions, center, lastErrorCode);
    }
  }

  fprintf(stderr, "DONE: completed %ld re-solves with no crash\n", iterations);
  return 0;
}
