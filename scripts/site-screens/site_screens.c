// Render one calculator screen for the Pages site as an SVG.
//
// Usage: r47-site-screens <scene> <output.svg>
//
// Boot the core headless through the Android bridge, drive it into the named
// scene the way a user would where that is simple (typed digits through
// r47_paste_number, functions through r47_send_sim_function), redraw, and write
// the 400 x 240 LCD as SVG. scripts/site-screens/render_site_screens.sh builds
// this file against the staged core and runs every scene scenes.txt lists.
//
// The SVG is this repo's own text, not upstream's: site_screen_svg.c writes one
// background rect and one path of the lit pixels, nothing else. The core runs in
// this process, so an upstream commit can still make it write anything;
// scripts/site-screens/check_screens_artifact.sh refuses a file that strays from
// this shape before the Pages site publishes it.

#include "keypad_fixture_bridge.h"
#include "screen.h"
#include "site_screen_svg.h"

#include <limits.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

// Take every core declaration from the headers, as the workload harness does: a
// private copy of an upstream prototype stops this file compiling when upstream
// changes the signature.
#include "jni_bridge.h"

// Type a number and close its entry, as the next key would: runFunction, which
// r47_send_sim_function calls, acts on X without closing the entry first.
static bool type_number(const char *tokens) {
  if (!r47_paste_number(tokens)) {
    fprintf(stderr, "ERROR: the core refused the number %s\n", tokens);
    return false;
  }
  pthread_mutex_lock(&screenMutex);
  if (calcMode == CM_NIM) {
    closeNim();
  }
  pthread_mutex_unlock(&screenMutex);
  return true;
}

static void press(int16_t item) { r47_send_sim_function(item); }

// The RPN stack holding four kinds of number: pi, a square root, a complex
// number, and 20! as an exact long integer.
static bool scene_stack(void) {
  press(ITM_CONSTpi);
  if (!type_number("2")) {
    return false;
  }
  press(ITM_SQUAREROOTX);
  if (!type_number("3j4")) {
    return false;
  }
  if (!type_number("20")) {
    return false;
  }
  press(ITM_XFACT);
  return true;
}

// A 3 x 3 matrix in the matrix editor.
static bool scene_matrix(void) {
  static const char *const cells[3][3] = {
      {"2", "-1", "0"},
      {"-1", "2", "-1"},
      {"0", "-1", "2"},
  };
  real34Matrix_t matrix;

  if (!realMatrixInit(&matrix, 3, 3)) {
    fprintf(stderr, "ERROR: the core could not allocate a 3 x 3 matrix\n");
    return false;
  }
  for (uint16_t row = 0; row < 3; ++row) {
    for (uint16_t col = 0; col < 3; ++col) {
      stringToReal34(cells[row][col], &matrix.matrixElements[row * 3 + col]);
    }
  }
  pthread_mutex_lock(&screenMutex);
  convertReal34MatrixToReal34MatrixRegister(&matrix, REGISTER_X);
  realMatrixFree(&matrix);
  fnEditMatrix(NOPARAM);
  pthread_mutex_unlock(&screenMutex);
  return true;
}

// Set the graph window, in the registers the user sees and in the floats the
// grapher draws with, as graph_crash_harness.c does.
static void set_window(double lx, double ux, double ly, double uy) {
  convertDoubleToReal34Register(lx, RESERVED_VARIABLE_LX);
  convertDoubleToReal34Register(ux, RESERVED_VARIABLE_UX);
  convertDoubleToReal34Register(ly, RESERVED_VARIABLE_LY);
  convertDoubleToReal34Register(uy, RESERVED_VARIABLE_UY);
  r47_graph_bound_from_float((float)lx, x_min);
  r47_graph_bound_from_float((float)ux, x_max);
  r47_graph_bound_from_float((float)ly, y_min);
  r47_graph_bound_from_float((float)uy, y_max);
}

// A function graphed by the equation grapher, the way showSoftmenu(-MNU_GRAPHS)
// sets it up: a new equation, its one variable resolved, then a plot.
static bool scene_plot(void) {
  press(ITM_RAD);
  pthread_mutex_lock(&screenMutex);
  fnEqNew(NOPARAM);
  setEquation(currentFormula, "x*sin(x)");
  currentSolverStatus = (currentSolverStatus & ~SOLVER_STATUS_EQUATION_MODE) |
                        SOLVER_STATUS_EQUATION_GRAPHER;
  parseEquation(currentFormula, EQUATION_PARSER_MVAR, aimBuffer, tmpString);
  currentSolverVariable = findOrAllocateNamedVariable(
      (char *)getNthString((uint8_t *)tmpString, 0));
  graphVariabl1 = currentSolverVariable;
  calcMode = CM_GRAPH;
  set_window(-10.0, 10.0, -10.0, 10.0);
  PLOT_ZMY = zoomOverride;
  fnEqSolvGraph(EQ_PLOT_LU);
  pthread_mutex_unlock(&screenMutex);
  return true;
}

typedef struct {
  const char *name;
  bool (*drive)(void);
} site_scene_t;

static const site_scene_t kScenes[] = {
    {"stack", scene_stack},
    {"matrix", scene_matrix},
    {"plot", scene_plot},
};

static bool lcd_pixel_on(uint32_t x, uint32_t y) {
  return lcd_buffer_pixel_on(x, y);
}

int main(int argc, char **argv) {
  if (argc != 3) {
    fprintf(stderr, "Usage: %s <scene> <output.svg>\n", argv[0]);
    return 2;
  }
  const site_scene_t *scene = NULL;
  for (size_t i = 0; i < sizeof(kScenes) / sizeof(kScenes[0]); ++i) {
    if (strcmp(kScenes[i].name, argv[1]) == 0) {
      scene = &kScenes[i];
    }
  }
  if (scene == NULL) {
    fprintf(stderr, "ERROR: no scene named %s\n", argv[1]);
    return 2;
  }

  // The core works in its runtime's DATA folder once it boots (workInDataDir in
  // hal/io.c), so a relative output path is resolved before then.
  char output_path[PATH_MAX];
  char cwd[PATH_MAX];
  if (argv[2][0] == '/') {
    cwd[0] = '\0';
  } else if (getcwd(cwd, sizeof(cwd)) == NULL) {
    fprintf(stderr, "ERROR: cannot read the working directory\n");
    return 1;
  }
  int written = snprintf(output_path, sizeof(output_path), "%s%s%s", cwd,
                         cwd[0] == '\0' ? "" : "/", argv[2]);
  if (written < 0 || (size_t)written >= sizeof(output_path)) {
    fprintf(stderr, "ERROR: the output path %s is too long\n", argv[2]);
    return 1;
  }

  char runtime_dir[] = "/tmp/r47-site-screens-XXXXXX";
  if (mkdtemp(runtime_dir) == NULL) {
    fprintf(stderr, "ERROR: cannot create a runtime directory\n");
    return 1;
  }
  r47_initialize_native_bridge_state();
  r47_native_preinit_path(runtime_dir);
  r47_init_runtime();

  if (!scene->drive()) {
    return 1;
  }
  if (lastErrorCode != ERROR_NONE) {
    fprintf(stderr, "ERROR: the %s scene left calculator error %u\n",
            scene->name, (unsigned int)lastErrorCode);
    return 1;
  }
  r47_force_refresh();
  return site_screen_write_svg(output_path, SCREEN_WIDTH, SCREEN_HEIGHT,
                               lcd_pixel_on)
             ? 0
             : 1;
}
