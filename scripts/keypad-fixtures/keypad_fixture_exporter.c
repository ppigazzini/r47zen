#include "keypad_fixture_bridge.h"

#include <errno.h>
#include <limits.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

extern void showSoftmenu(int16_t id);

typedef struct {
  const char *name;
  const char *description;
  bool is_dynamic;
  bool (*apply)(void);
} scenario_definition_t;

typedef struct {
  int32_t meta[R47_KEYPAD_META_LENGTH];
  char labels[R47_KEYPAD_KEY_COUNT * R47_KEYPAD_LABELS_PER_KEY]
             [R47_KEYPAD_LABEL_CAPACITY];
} scenario_capture_t;

static void usage(const char *argv0) {
  fprintf(stderr,
          "Usage: %s --output-dir <dir> --upstream-commit <sha>\n",
          argv0);
}

static bool ensure_directory(const char *path) {
  struct stat st;
  if (stat(path, &st) == 0) {
    return S_ISDIR(st.st_mode);
  }
  if (mkdir(path, 0777) == 0) {
    return true;
  }
  return errno == EEXIST;
}

static void json_write_string(FILE *stream, const char *value) {
  const unsigned char *cursor = (const unsigned char *)(value ? value : "");
  fputc('"', stream);
  while (*cursor != 0) {
    switch (*cursor) {
    case '\\':
      fputs("\\\\", stream);
      break;
    case '"':
      fputs("\\\"", stream);
      break;
    case '\n':
      fputs("\\n", stream);
      break;
    case '\r':
      fputs("\\r", stream);
      break;
    case '\t':
      fputs("\\t", stream);
      break;
    default:
      if (*cursor < 0x20) {
        fprintf(stream, "\\u%04x", (unsigned int)*cursor);
      } else {
        fputc(*cursor, stream);
      }
      break;
    }
    cursor++;
  }
  fputc('"', stream);
}

static bool write_scene_file(const char *output_dir,
                             const scenario_definition_t *scenario,
                             const scenario_capture_t *capture) {
  char file_path[PATH_MAX];
  FILE *stream;

  snprintf(file_path, sizeof(file_path), "%s/%s.json", output_dir,
           scenario->name);
  stream = fopen(file_path, "w");
  if (stream == NULL) {
    fprintf(stderr, "ERROR: Failed to open %s for writing\n", file_path);
    return false;
  }

  fprintf(stream, "{\n");
  fprintf(stream, "  \"name\": ");
  json_write_string(stream, scenario->name);
  fprintf(stream, ",\n  \"description\": ");
  json_write_string(stream, scenario->description);
  fprintf(stream, ",\n  \"dynamic\": %s,\n", scenario->is_dynamic ? "true" : "false");
  fprintf(stream, "  \"sceneContractVersion\": %d,\n",
          capture->meta[R47_KEYPAD_META_CONTRACT_VERSION]);
  fprintf(stream, "  \"meta\": [");
  for (int index = 0; index < R47_KEYPAD_META_LENGTH; index++) {
    fprintf(stream, "%s%d", index == 0 ? "" : ", ", capture->meta[index]);
  }
  fprintf(stream, "],\n  \"labels\": [\n");
  for (int index = 0; index < R47_KEYPAD_KEY_COUNT * R47_KEYPAD_LABELS_PER_KEY;
       index++) {
    fprintf(stream, "    ");
    json_write_string(stream, capture->labels[index]);
    fprintf(stream, "%s\n",
            index + 1 ==
                    R47_KEYPAD_KEY_COUNT * R47_KEYPAD_LABELS_PER_KEY
                ? ""
                : ",");
  }
  fprintf(stream, "  ]\n}\n");
  fclose(stream);
  return true;
}

static bool write_manifest(const char *output_dir, const char *upstream_commit,
                           const scenario_definition_t *scenarios,
                           size_t scenario_count) {
  char file_path[PATH_MAX];
  FILE *stream;

  snprintf(file_path, sizeof(file_path), "%s/manifest.json", output_dir);
  stream = fopen(file_path, "w");
  if (stream == NULL) {
    fprintf(stderr, "ERROR: Failed to open %s for writing\n", file_path);
    return false;
  }

  fprintf(stream, "{\n");
  fprintf(stream, "  \"upstreamCommit\": ");
  json_write_string(stream, upstream_commit);
  fprintf(stream,
          ",\n  \"sceneContractVersion\": %d,\n"
          "  \"metaLength\": %d,\n"
          "  \"keyCount\": %d,\n"
          "  \"labelsPerKey\": %d,\n"
          "  \"scenarios\": [\n",
          R47_KEYPAD_SCENE_CONTRACT_VERSION, R47_KEYPAD_META_LENGTH,
          R47_KEYPAD_KEY_COUNT, R47_KEYPAD_LABELS_PER_KEY);

  for (size_t index = 0; index < scenario_count; index++) {
    fprintf(stream, "    {\"name\": ");
    json_write_string(stream, scenarios[index].name);
    fprintf(stream, ", \"file\": ");
    char file_name[128];
    snprintf(file_name, sizeof(file_name), "%s.json", scenarios[index].name);
    json_write_string(stream, file_name);
    fprintf(stream, ", \"description\": ");
    json_write_string(stream, scenarios[index].description);
    fprintf(stream, "}%s\n", index + 1 == scenario_count ? "" : ",");
  }

  fprintf(stream, "  ]\n}\n");
  fclose(stream);
  return true;
}

static bool refresh_after_state_change(void) {
  r47_force_refresh();
  return true;
}

static bool apply_default_keypad(void) {
  return true;
}

static bool apply_shift_f_preview(void) {
  shiftF = true;
  shiftG = false;
  return refresh_after_state_change();
}

static bool apply_shift_g_preview(void) {
  shiftF = false;
  shiftG = true;
  return refresh_after_state_change();
}

static bool apply_alpha_upper(void) {
  shiftF = false;
  shiftG = false;
  catalog = CATALOG_NONE;
  tam.mode = 0;
  tam.alpha = false;
  previousCalcMode = CM_NORMAL;
  calcMode = CM_AIM;
  alphaCase = AC_UPPER;
  showSoftmenu(-MNU_ALPHA);
  return refresh_after_state_change();
}

static bool apply_alpha_lower(void) {
  shiftF = false;
  shiftG = false;
  catalog = CATALOG_NONE;
  tam.mode = 0;
  tam.alpha = false;
  previousCalcMode = CM_NORMAL;
  calcMode = CM_AIM;
  alphaCase = AC_LOWER;
  showSoftmenu(-MNU_ALPHA);
  return refresh_after_state_change();
}

static bool apply_alpha_prog_transition(void) {
  shiftF = false;
  shiftG = false;
  catalog = CATALOG_NONE;
  tam.mode = 0;
  tam.alpha = false;
  previousCalcMode = CM_AIM;
  calcMode = CM_ASSIGN;
  alphaCase = AC_UPPER;
  showSoftmenu(-MNU_ALPHA);
  return refresh_after_state_change();
}

static bool apply_catalog_state(void) {
  shiftF = false;
  shiftG = false;
  tam.mode = 0;
  tam.alpha = false;
  previousCalcMode = CM_NORMAL;
  calcMode = CM_NORMAL;
  catalog = CATALOG_FCNS;
  showSoftmenu(-MNU_ALPHA);
  return refresh_after_state_change();
}

static bool apply_dotted_row_state(void) {
  int32_t meta[R47_KEYPAD_META_LENGTH];

  shiftF = false;
  shiftG = false;
  catalog = CATALOG_NONE;
  tam.mode = 0;
  tam.alpha = false;
  previousCalcMode = CM_NORMAL;
  calcMode = CM_NORMAL;

  showSoftmenu(-MNU_BITS);
  r47_force_refresh();
  r47_get_keypad_meta(meta, true);
  if (meta[R47_KEYPAD_META_SOFTMENU_DOTTED_ROW] >= 0) {
    return true;
  }

  fprintf(stderr,
          "ERROR: MNU_BITS did not expose a dotted-row softmenu state\n");
  return false;
}

static bool apply_static_single_scene(void) {
  showSoftmenu(-MNU_HOME);
  return refresh_after_state_change();
}

static bool apply_tam_scene(void) {
  shiftF = false;
  shiftG = false;
  catalog = CATALOG_NONE;
  previousCalcMode = CM_NORMAL;
  calcMode = CM_NORMAL;
  tam.alpha = false;
  tam.mode = TM_VALUE;
  showSoftmenu(-MNU_HOME);
  return refresh_after_state_change();
}

static bool capture_scenario(const scenario_definition_t *scenario,
                             scenario_capture_t *capture) {
  r47_init_runtime(0);
  if (!scenario->apply()) {
    return false;
  }

  r47_get_keypad_meta(capture->meta, scenario->is_dynamic);
  r47_get_keypad_labels(capture->labels, scenario->is_dynamic);
  if (capture->meta[R47_KEYPAD_META_CONTRACT_VERSION] !=
      R47_KEYPAD_SCENE_CONTRACT_VERSION) {
    fprintf(stderr,
            "ERROR: Scenario %s exported scene contract %d but expected %d\n",
            scenario->name, capture->meta[R47_KEYPAD_META_CONTRACT_VERSION],
            R47_KEYPAD_SCENE_CONTRACT_VERSION);
    return false;
  }
  return true;
}

static bool create_runtime_directory(char *buffer, size_t buffer_size) {
  const char *tmp_root = getenv("TMPDIR");
  if (tmp_root == NULL || tmp_root[0] == 0) {
    tmp_root = "/tmp";
  }

  snprintf(buffer, buffer_size, "%s/r47-keypad-fixtures-XXXXXX", tmp_root);
  return mkdtemp(buffer) != NULL;
}

int main(int argc, char **argv) {
  const char *output_dir = NULL;
  const char *upstream_commit = NULL;
  char runtime_dir[PATH_MAX];
  scenario_capture_t capture;

  const scenario_definition_t scenarios[] = {
      {"default-keypad", "Default keypad scene", true, apply_default_keypad},
      {"shift-f-preview", "F-shift preview row", true, apply_shift_f_preview},
      {"shift-g-preview", "G-shift preview row", true, apply_shift_g_preview},
      {"alpha-upper", "Uppercase alpha keypad", true, apply_alpha_upper},
      {"alpha-lower", "Lowercase alpha keypad", true, apply_alpha_lower},
      {"alpha-prog-transition", "Alphabetic transition with PROG-facing layout", true,
       apply_alpha_prog_transition},
      {"catalog-state", "Catalog-driven alpha scene", true, apply_catalog_state},
      {"dotted-row-state", "Softmenu scene with dotted-row indicator", true,
       apply_dotted_row_state},
      {"static-single-scene", "Static-single top-label layout scene", true,
       apply_static_single_scene},
      {"tam-scene", "TAM layout-class scene", true, apply_tam_scene},
  };

  for (int index = 1; index < argc; index++) {
    if (strcmp(argv[index], "--output-dir") == 0 && index + 1 < argc) {
      output_dir = argv[++index];
      continue;
    }
    if (strcmp(argv[index], "--upstream-commit") == 0 && index + 1 < argc) {
      upstream_commit = argv[++index];
      continue;
    }
    usage(argv[0]);
    return 1;
  }

  if (output_dir == NULL || upstream_commit == NULL) {
    usage(argv[0]);
    return 1;
  }

  if (!ensure_directory(output_dir)) {
    fprintf(stderr, "ERROR: Failed to create output directory %s\n", output_dir);
    return 1;
  }

  if (!create_runtime_directory(runtime_dir, sizeof(runtime_dir))) {
    fprintf(stderr, "ERROR: Failed to create runtime directory\n");
    return 1;
  }

  r47_initialize_native_bridge_state();
  r47_native_preinit_path(runtime_dir);

  for (size_t index = 0; index < sizeof(scenarios) / sizeof(scenarios[0]);
       index++) {
    if (!capture_scenario(&scenarios[index], &capture)) {
      return 1;
    }
    if (!write_scene_file(output_dir, &scenarios[index], &capture)) {
      return 1;
    }
  }

  if (!write_manifest(output_dir, upstream_commit, scenarios,
                      sizeof(scenarios) / sizeof(scenarios[0]))) {
    return 1;
  }

  return 0;
}
