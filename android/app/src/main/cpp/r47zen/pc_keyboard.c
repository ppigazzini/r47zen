// The PC keyboard as upstream's GTK simulator handles it: the R47 profile of
// keyPressedNow, keyReleasedNow, shortCutCommand, shortCutFNCommand, and the text
// entry behind them, in src/c47-gtk/gtkGui.c. hal/keys.c runs it on the calculator
// thread when it pops the key's host event, as pcKeyHandle runs, so each choice
// reads the mode the key lands in; while the calculator is busy it only pushes keys.
// The names follow gtkGui.c's, so the two read side by side.
//
// Kotlin sends the X11 keysym and modifier state GDK gives GTK: a character below
// 0x100 as its code point, any other as GDK's Unicode keysym (0x01000000 plus the
// code point), which r47_pc_key_handle turns back into the legacy keysym GTK's map
// holds. The state is the modifiers before the event, as X11 reports it.
//
// The app builds the core with PC_BUILD, so upstream's clipboard copies, captures,
// and dead key (deadKey, which the status bar marks) are the ones GTK uses. Not
// ported, for want of an Android counterpart: Ctrl+P, GTK's printer window, and
// Ctrl+H, Ctrl+M, and Ctrl+K, the screen, menu, and X register images
// src/c47-gtk/hal/lcd.c copies. test_physical_keyboard_contract.py holds the
// rows, tables, and Ctrl commands here to gtkGui.c.
#include "c47.h"
#include "host_services.h"
#include "jni_bridge.h"

// X11 keysyms, from keysymdef.h; GDK's GDK_KEY_ names have the same values.
#define GDK_KEY_space 0x0020
#define GDK_KEY_exclam 0x0021
#define GDK_KEY_quotedbl 0x0022
#define GDK_KEY_numbersign 0x0023
#define GDK_KEY_dollar 0x0024
#define GDK_KEY_percent 0x0025
#define GDK_KEY_ampersand 0x0026
#define GDK_KEY_apostrophe 0x0027
#define GDK_KEY_slash 0x002f
#define GDK_KEY_0 0x0030
#define GDK_KEY_9 0x0039
#define GDK_KEY_colon 0x003a
#define GDK_KEY_less 0x003c
#define GDK_KEY_equal 0x003d
#define GDK_KEY_greater 0x003e
#define GDK_KEY_question 0x003f
#define GDK_KEY_at 0x0040
#define GDK_KEY_A 0x0041
#define GDK_KEY_B 0x0042
#define GDK_KEY_C 0x0043
#define GDK_KEY_D 0x0044
#define GDK_KEY_E 0x0045
#define GDK_KEY_F 0x0046
#define GDK_KEY_G 0x0047
#define GDK_KEY_H 0x0048
#define GDK_KEY_I 0x0049
#define GDK_KEY_J 0x004a
#define GDK_KEY_K 0x004b
#define GDK_KEY_L 0x004c
#define GDK_KEY_M 0x004d
#define GDK_KEY_N 0x004e
#define GDK_KEY_O 0x004f
#define GDK_KEY_P 0x0050
#define GDK_KEY_Q 0x0051
#define GDK_KEY_R 0x0052
#define GDK_KEY_S 0x0053
#define GDK_KEY_T 0x0054
#define GDK_KEY_U 0x0055
#define GDK_KEY_V 0x0056
#define GDK_KEY_W 0x0057
#define GDK_KEY_X 0x0058
#define GDK_KEY_Y 0x0059
#define GDK_KEY_Z 0x005a
#define GDK_KEY_backslash 0x005c
#define GDK_KEY_asciicircum 0x005e
#define GDK_KEY_a 0x0061
#define GDK_KEY_b 0x0062
#define GDK_KEY_c 0x0063
#define GDK_KEY_d 0x0064
#define GDK_KEY_e 0x0065
#define GDK_KEY_f 0x0066
#define GDK_KEY_g 0x0067
#define GDK_KEY_i 0x0069
#define GDK_KEY_j 0x006a
#define GDK_KEY_k 0x006b
#define GDK_KEY_l 0x006c
#define GDK_KEY_m 0x006d
#define GDK_KEY_n 0x006e
#define GDK_KEY_o 0x006f
#define GDK_KEY_p 0x0070
#define GDK_KEY_q 0x0071
#define GDK_KEY_r 0x0072
#define GDK_KEY_s 0x0073
#define GDK_KEY_t 0x0074
#define GDK_KEY_u 0x0075
#define GDK_KEY_v 0x0076
#define GDK_KEY_w 0x0077
#define GDK_KEY_x 0x0078
#define GDK_KEY_y 0x0079
#define GDK_KEY_z 0x007a
#define GDK_KEY_bar 0x007c
#define GDK_KEY_twosuperior 0x00b2
#define GDK_KEY_eacute 0x00e9
#define GDK_KEY_dead_grave 0xfe50
#define GDK_KEY_dead_acute 0xfe51
#define GDK_KEY_dead_circumflex 0xfe52
#define GDK_KEY_dead_tilde 0xfe53
#define GDK_KEY_dead_macron 0xfe54
#define GDK_KEY_dead_breve 0xfe55
#define GDK_KEY_dead_abovedot 0xfe56
#define GDK_KEY_dead_diaeresis 0xfe57
#define GDK_KEY_dead_abovering 0xfe58
#define GDK_KEY_dead_cedilla 0xfe5b
#define GDK_KEY_dead_ogonek 0xfe5c
#define GDK_KEY_dead_stroke 0xfe63
#define GDK_KEY_BackSpace 0xff08
#define GDK_KEY_Tab 0xff09
#define GDK_KEY_Return 0xff0d
#define GDK_KEY_Escape 0xff1b
#define GDK_KEY_Home 0xff50
#define GDK_KEY_Left 0xff51
#define GDK_KEY_Up 0xff52
#define GDK_KEY_Right 0xff53
#define GDK_KEY_Down 0xff54
#define GDK_KEY_End 0xff57
#define GDK_KEY_Begin 0xff58
#define GDK_KEY_KP_Enter 0xff8d
#define GDK_KEY_KP_Delete 0xff9f
#define GDK_KEY_KP_Multiply 0xffaa
#define GDK_KEY_KP_Add 0xffab
#define GDK_KEY_KP_Subtract 0xffad
#define GDK_KEY_KP_Decimal 0xffae
#define GDK_KEY_KP_Divide 0xffaf
#define GDK_KEY_KP_0 0xffb0
#define GDK_KEY_KP_1 0xffb1
#define GDK_KEY_KP_2 0xffb2
#define GDK_KEY_KP_3 0xffb3
#define GDK_KEY_KP_4 0xffb4
#define GDK_KEY_KP_5 0xffb5
#define GDK_KEY_KP_6 0xffb6
#define GDK_KEY_KP_7 0xffb7
#define GDK_KEY_KP_8 0xffb8
#define GDK_KEY_KP_9 0xffb9
#define GDK_KEY_F1 0xffbe
#define GDK_KEY_F2 0xffbf
#define GDK_KEY_F3 0xffc0
#define GDK_KEY_F4 0xffc1
#define GDK_KEY_F5 0xffc2
#define GDK_KEY_F6 0xffc3
#define GDK_KEY_F7 0xffc4
#define GDK_KEY_F8 0xffc5
#define GDK_KEY_F9 0xffc6
#define GDK_KEY_F10 0xffc7
#define GDK_KEY_F11 0xffc8
#define GDK_KEY_F12 0xffc9
#define GDK_KEY_F14 0xffcb
#define GDK_KEY_Shift_L 0xffe1
#define GDK_KEY_Shift_R 0xffe2
#define GDK_KEY_Control_L 0xffe3
#define GDK_KEY_Control_R 0xffe4
#define GDK_KEY_Alt_R 0xffea
#define GDK_KEY_Hyper_R 0xffee
#define GDK_KEY_Delete 0xffff
#define GDK_KEY_zerosubscript 0x1002080
#define GDK_KEY_ninesubscript 0x1002089

// The keysyms PcKeys sends that nothing here names, so the port's list is the one
// test_physical_keyboard_contract.py holds Kotlin to. GTK ignores each of them.
#define GDK_KEY_Prior 0xff55
#define GDK_KEY_Next 0xff56
#define GDK_KEY_Insert 0xff63
#define GDK_KEY_Num_Lock 0xff7f
#define GDK_KEY_KP_Home 0xff95
#define GDK_KEY_KP_Left 0xff96
#define GDK_KEY_KP_Up 0xff97
#define GDK_KEY_KP_Right 0xff98
#define GDK_KEY_KP_Down 0xff99
#define GDK_KEY_KP_Prior 0xff9a
#define GDK_KEY_KP_Next 0xff9b
#define GDK_KEY_KP_End 0xff9c
#define GDK_KEY_KP_Begin 0xff9d
#define GDK_KEY_KP_Insert 0xff9e
#define GDK_KEY_KP_Separator 0xffac
#define GDK_KEY_KP_Equal 0xffbd
#define GDK_KEY_Caps_Lock 0xffe5
#define GDK_KEY_Alt_L 0xffe9
#define GDK_KEY_Super_L 0xffeb
#define GDK_KEY_Super_R 0xffec
#define GDK_KEY_dead_doubleacute 0xfe59
#define GDK_KEY_dead_caron 0xfe5a

#define GDK_CONTROL_MASK 0x4

#define EXITIFNIM true

typedef enum { R47_PC_NEVER, R47_PC_BY_TAM } r47PcDisable_t;

// One row of a shortCutCommand or shortCutFNCommand chain, in that call's terms.
// The macros take the fields in the call's order.
typedef struct {
  const char *keyForBtnClicked; // "00" to "36", "1" to "6" a softkey, or "-01"
  uint32_t keyCode;             // the keysym the row answers
  r47PcDisable_t disable;
  uint32_t modes; // the calculator modes the row runs in, as a bit mask
  int16_t requiredCalcMode2;
  int16_t itemForRunFunction;
  bool fn;          // a shortCutFNCommand row
  bool exitIfInNim; // EXITIFNIM
  char shift;       // 0, 'f', or 'g'
} r47PcRow_t;

#define PC_ROW(isFn, k, nim, dis, sh, btn, m, m2, it)                          \
  {.keyForBtnClicked = (btn),                                                  \
   .keyCode = (k),                                                             \
   .disable = (dis),                                                           \
   .modes = (m),                                                               \
   .requiredCalcMode2 = (m2),                                                  \
   .itemForRunFunction = (it),                                                 \
   .fn = (isFn),                                                               \
   .exitIfInNim = (nim),                                                       \
   .shift = (sh)}
#define ROW(k, nim, dis, sh, btn, m, m2, it)                                   \
  PC_ROW(false, k, nim, dis, sh, btn, m, m2, it)
#define FN_ROW(k, nim, dis, sh, btn, m, m2, it)                                \
  PC_ROW(true, k, nim, dis, sh, btn, m, m2, it)

// The R47 rows of gtkGui.c's chain for the calculator modes, in its order.
static const r47PcRow_t r47PcCommandRows[] = {
    ROW(GDK_KEY_Tab, false, R47_PC_BY_TAM, 0, "13", 0b01101, -1, ITM_XexY),
    ROW(GDK_KEY_w, false, R47_PC_BY_TAM, 0, "13", 0b01101, -1, ITM_XexY),
    ROW(GDK_KEY_n, false, R47_PC_BY_TAM, 0, "14", 0b01101, -1, ITM_CHS),
    ROW(GDK_KEY_e, false, R47_PC_BY_TAM, 0, "15", 0b01101, -1, ITM_EXPONENT),
    ROW(GDK_KEY_greater, true, R47_PC_BY_TAM, 0, "-01", 0b01101, -1, ITM_DRG),
    ROW(GDK_KEY_Y, true, R47_PC_BY_TAM, 0, "-01", 0b01101, -1, ITM_YX),
    ROW(GDK_KEY_X, true, R47_PC_BY_TAM, 0, "-01", 0b01101, -1, KEY_COMPLEX),
    ROW(GDK_KEY_R, true, R47_PC_BY_TAM, 0, "-01", 0b01101, -1, ITM_toREC2),
    ROW(GDK_KEY_P, true, R47_PC_BY_TAM, 0, "-01", 0b01101, -1, ITM_toPOL2),
    ROW(GDK_KEY_p, true, R47_PC_BY_TAM, 0, "-01", 0b01101, -1, ITM_CONSTpi),
    ROW(GDK_KEY_V, true, R47_PC_BY_TAM, 0, "-01", 0b01101, -1, ITM_1ONX),
    ROW(GDK_KEY_y, true, R47_PC_BY_TAM, 0, "-01", 0b01101, -1, ITM_XTHROOT),
    ROW(GDK_KEY_C, true, R47_PC_BY_TAM, 0, "-01", 0b01101, -1, ITM_arccos),
    ROW(GDK_KEY_S, true, R47_PC_BY_TAM, 0, "-01", 0b01101, -1, ITM_arcsin),
    ROW(GDK_KEY_T, true, R47_PC_BY_TAM, 0, "-01", 0b01101, -1, ITM_arctan),
    ROW(GDK_KEY_L, true, R47_PC_BY_TAM, 0, "-01", 0b01101, -1, ITM_EXP),
    ROW(GDK_KEY_O, true, R47_PC_BY_TAM, 0, "-01", 0b01101, -1, ITM_10x),
    ROW(GDK_KEY_Q, true, R47_PC_BY_TAM, 0, "-01", 0b01101, -1, ITM_SQUARE),
    ROW(GDK_KEY_D, true, R47_PC_BY_TAM, 0, "-01", 0b01101, -1, ITM_Rup),
    ROW(GDK_KEY_I, false, R47_PC_NEVER, 0, "-01", 0b0000011000000001101, -1,
        -MNU_DISP),
    ROW(GDK_KEY_J, false, R47_PC_NEVER, 0, "-01", 0b0000011000000001101, -1,
        -MNU_EXP),
    ROW(GDK_KEY_K, false, R47_PC_NEVER, 0, "-01", 0b0000011000000001101, -1,
        -MNU_STK),
    ROW(GDK_KEY_F, false, R47_PC_NEVER, 0, "-01", 0b0000011000000001101, -1,
        -MNU_PREFIX),
    ROW(GDK_KEY_percent, true, R47_PC_BY_TAM, 0, "-01", 0b01101, -1, ITM_PC),
    ROW(GDK_KEY_exclam, true, R47_PC_BY_TAM, 0, "-01", 0b01101, -1, ITM_XFACT),
    ROW(GDK_KEY_U, true, R47_PC_NEVER, 0, "-01", 0xffff, -1, ITM_USERMODE),
    ROW(GDK_KEY_A, true, R47_PC_BY_TAM, 0, "-01", 0b01101, -1, ITM_ARG),
    ROW(GDK_KEY_Z, true, R47_PC_BY_TAM, 0, "-01", 0b01101, -1, ITM_MAGNITUDE),
    ROW(GDK_KEY_bar, true, R47_PC_BY_TAM, 0, "-01", 0b01101, -1, ITM_MAGNITUDE),
    ROW(126, true, R47_PC_BY_TAM, 0, "-01", 0b01101, -1, ITM_MAGNITUDE),
    ROW(GDK_KEY_F7, true, R47_PC_BY_TAM, 0, "-01", 0b01101, -1, ITM_SI_n),
    ROW(GDK_KEY_F8, true, R47_PC_BY_TAM, 0, "-01", 0b01101, -1, ITM_SI_u),
    ROW(GDK_KEY_F9, true, R47_PC_BY_TAM, 0, "-01", 0b01101, -1, ITM_SI_m),
    ROW(GDK_KEY_F10, true, R47_PC_BY_TAM, 0, "-01", 0b01101, -1, ITM_SI_k),
    ROW(GDK_KEY_F11, true, R47_PC_BY_TAM, 0, "-01", 0b01101, -1, ITM_SI_M),
    ROW(GDK_KEY_W, true, R47_PC_BY_TAM, 0, "-01", 0b01101, -1, ITM_LASTX),
    ROW(GDK_KEY_equal, true, R47_PC_BY_TAM, 0, "-01", 0b01101, -1, ITM_dotD),
    ROW(GDK_KEY_E, true, R47_PC_BY_TAM, 0, "-01", 0b01101, -1, CST_09),
    ROW(GDK_KEY_N, true, R47_PC_NEVER, 'f', "35", 0b01101, CM_PEM, ITM_PR),
    ROW(GDK_KEY_b, true, R47_PC_BY_TAM, 0, "-01", 0b01101, CM_PEM, ITM_LBL),
    ROW(GDK_KEY_u, true, R47_PC_NEVER, 'f', "16", 0b01101, CM_PEM, ITM_PR),
    ROW(GDK_KEY_H, false, R47_PC_NEVER, 0, "-01", 0b0000011000000001101, -1,
        -MNU_HOME),
    ROW(GDK_KEY_B, false, R47_PC_NEVER, 0, "-01", 0b0000011000000001101, -1,
        -MNU_MyMenu),
    ROW(GDK_KEY_less, true, R47_PC_BY_TAM, 0, "-01", 0b01101, -1, ITM_RTN),
    ROW(GDK_KEY_twosuperior, true, R47_PC_BY_TAM, 0, "-01", 0b01101, -1,
        ITM_SQUARE),
    ROW(GDK_KEY_backslash, false, R47_PC_NEVER, 0, "35", 0b0100000000001101, -1,
        ITM_STOP),
    ROW(96, false, R47_PC_NEVER, 0, "35", 0b01101, -1, ITM_STOP),
    ROW(GDK_KEY_z, false, R47_PC_NEVER, 0, "35", 0b01101, -1, ITM_STOP),
    ROW(GDK_KEY_Q, true, R47_PC_BY_TAM, 0, "00", 0b01101, -1, ITM_SQUARE),
    ROW(GDK_KEY_i, false, R47_PC_BY_TAM, 'f', "00", 0b11101, -1, ITM_op_j),
    ROW(GDK_KEY_j, false, R47_PC_BY_TAM, 'f', "00", 0b11101, -1, ITM_op_j),
    ROW(GDK_KEY_q, true, R47_PC_BY_TAM, 0, "01", 0b01101, -1, ITM_SQUAREROOTX),
    ROW(GDK_KEY_k, false, R47_PC_BY_TAM, 'f', "01", 0b11101, -1, ITM_op_j_pol),
    ROW(GDK_KEY_v, true, R47_PC_BY_TAM, 0, "02", 0b01101, -1, ITM_1ONX),
    ROW(GDK_KEY_Y, true, R47_PC_BY_TAM, 0, "03", 0b01101, -1, ITM_YX),
    ROW(GDK_KEY_asciicircum, true, R47_PC_BY_TAM, 0, "03", 0b01101, -1, ITM_YX),
    ROW(GDK_KEY_o, true, R47_PC_BY_TAM, 0, "04", 0b01101, -1, ITM_LOG10),
    ROW(GDK_KEY_l, true, R47_PC_BY_TAM, 0, "05", 0b01101, -1, ITM_LN),
    ROW(GDK_KEY_m, true, R47_PC_BY_TAM, 0, "06", 0b1000000001101, -1, ITM_STO),
    ROW(GDK_KEY_r, true, R47_PC_BY_TAM, 0, "07", 0b1000000001101, -1, ITM_RCL),
    ROW(GDK_KEY_d, true, R47_PC_BY_TAM, 0, "08", 0b01101, -1, ITM_Rdown),
    ROW(GDK_KEY_greater, true, R47_PC_BY_TAM, 0, "09", 0b01101, -1, ITM_DRG),
    ROW(GDK_KEY_a, true, R47_PC_BY_TAM, 0, "-01", 0b01101, -1, ITM_SIGMAPLUS),
    ROW(GDK_KEY_x, false, R47_PC_NEVER, 0, "17", 0b01101, -1, ITM_XEQ),
    ROW(GDK_KEY_apostrophe, true, R47_PC_NEVER, 'f', "17", 0b01101, -1,
        ITM_AIM),
    ROW(GDK_KEY_G, true, R47_PC_NEVER, 'g', "17", 0b01101, -1, ITM_GTO),
    ROW(GDK_KEY_M, false, R47_PC_NEVER, 0, "-01", 0b0000011000000001101, -1,
        -MNU_PREF),
    ROW(GDK_KEY_s, true, R47_PC_BY_TAM, 0, "-01", 0b01101, -1, ITM_sin),
    ROW(GDK_KEY_c, true, R47_PC_BY_TAM, 0, "-01", 0b01101, -1, ITM_cos),
    ROW(GDK_KEY_t, true, R47_PC_BY_TAM, 0, "-01", 0b01101, -1, ITM_tan),
    ROW(GDK_KEY_V, true, R47_PC_BY_TAM, 0, "-01", 0b01101, -1, ITM_1ONX),
    ROW(GDK_KEY_colon, true, R47_PC_BY_TAM, 'g', "34", 0b01101, -1, ITM_TGLFRT),
    ROW(GDK_KEY_numbersign, false, R47_PC_BY_TAM, 'g', "05", 0b11101, -1,
        ITM_HASH_JM),
    ROW(GDK_KEY_quotedbl, false, R47_PC_BY_TAM, 'g', "05", 0b11101, -1,
        ITM_HASH_JM),
    ROW(GDK_KEY_at, false, R47_PC_BY_TAM, 'g', "03", 0b11101, -1, ITM_dotD),
    ROW(GDK_KEY_eacute, false, R47_PC_BY_TAM, 'g', "03", 0b11101, -1, ITM_dotD),
    ROW(GDK_KEY_asciicircum, true, R47_PC_BY_TAM, 0, "03", 0b01101, -1, ITM_YX),
    ROW(GDK_KEY_dollar, false, R47_PC_BY_TAM, 'g', "02", 0b11101, -1, ITM_ms),
    ROW(GDK_KEY_ampersand, false, R47_PC_BY_TAM, 'g', "04", 0b11101, -1,
        ITM_RI),
};

// The R47 rows of its STO and RCL chain.
static const r47PcRow_t r47PcStoRclRows[] = {
    ROW(GDK_KEY_Up, false, R47_PC_NEVER, 0, "22", 0b01001, -1, 0),
    ROW(GDK_KEY_Down, false, R47_PC_NEVER, 0, "27", 0b01001, -1, 0),
    FN_ROW(GDK_KEY_Right, false, R47_PC_NEVER, 0, "1", 0b01001, -1, 0),
    ROW('/', false, R47_PC_NEVER, 0, "21", 0b01001, -1, 0),
    ROW('*', false, R47_PC_NEVER, 0, "26", 0b01001, -1, 0),
    ROW('-', false, R47_PC_NEVER, 0, "31", 0b01001, -1, 0),
    ROW('+', false, R47_PC_NEVER, 0, "36", 0b01001, -1, 0),
};

// The R47 rows of its chain for the flag and label arrows.
static const r47PcRow_t r47PcTamArrowRows[] = {
    ROW(GDK_KEY_Up, false, R47_PC_NEVER, 0, "22", 0b01001, -1, 0),
    ROW(GDK_KEY_Down, false, R47_PC_NEVER, 0, "27", 0b01001, -1, 0),
};

// The R47 rows of its chain for the menus whose arrows are softkeys.
static const r47PcRow_t r47PcMenuArrowRows[] = {
    FN_ROW(GDK_KEY_Up, false, R47_PC_NEVER, 0, "1", 3 << 13, -1, 0),
    FN_ROW(GDK_KEY_Down, false, R47_PC_NEVER, 0, "2", 3 << 13, -1, 0),
    FN_ROW(GDK_KEY_Left, false, R47_PC_NEVER, 0, "5", 3 << 13, -1, 0),
    FN_ROW(GDK_KEY_Right, false, R47_PC_NEVER, 0, "6", 3 << 13, -1, 0),
};

typedef struct {
  uint32_t keysym; // GTK's legacy keysym
  uint32_t unicode;
  int16_t item;
} r47PcKeysym_t;

// gtkGui.c's gdkKeyMap, with each keysym's code point from keysymdef.h.
static const r47PcKeysym_t r47PcKeysyms[] = {
    {0x03c0, 0x0100, ITM_A_MACRON},                  // Amacron
    {0x00c1, 0x00c1, ITM_A_ACUTE},                   // Aacute
    {0x01c3, 0x0102, ITM_A_BREVE},                   // Abreve
    {0x00c0, 0x00c0, ITM_A_GRAVE},                   // Agrave
    {0x00c4, 0x00c4, ITM_A_DIARESIS},                // Adiaeresis
    {0x00c3, 0x00c3, ITM_A_TILDE},                   // Atilde
    {0x00c2, 0x00c2, ITM_A_CIRC},                    // Acircumflex
    {0x00c5, 0x00c5, ITM_A_RING},                    // Aring
    {0x00c6, 0x00c6, ITM_AE},                        // AE
    {0x01a1, 0x0104, ITM_A_OGONEK},                  // Aogonek
    {0x01c6, 0x0106, ITM_C_ACUTE},                   // Cacute
    {0x01c8, 0x010c, ITM_C_CARON},                   // Ccaron
    {0x00c7, 0x00c7, ITM_C_CEDILLA},                 // Ccedilla
    {0x01d0, 0x0110, ITM_D_STROKE},                  // Dstroke
    {0x01cf, 0x010e, ITM_D_CARON},                   // Dcaron
    {0x03aa, 0x0112, ITM_E_MACRON},                  // Emacron
    {0x00c9, 0x00c9, ITM_E_ACUTE},                   // Eacute
    {0x00c8, 0x00c8, ITM_E_GRAVE},                   // Egrave
    {0x00cb, 0x00cb, ITM_E_DIARESIS},                // Ediaeresis
    {0x00ca, 0x00ca, ITM_E_CIRC},                    // Ecircumflex
    {0x01ca, 0x0118, ITM_E_OGONEK},                  // Eogonek
    {0x02ab, 0x011e, ITM_G_BREVE},                   // Gbreve
    {0x03cf, 0x012a, ITM_I_MACRON},                  // Imacron
    {0x00cd, 0x00cd, ITM_I_ACUTE},                   // Iacute
    {0x100012c, 0x012c, ITM_I_BREVE},                // Ibreve
    {0x00cc, 0x00cc, ITM_I_GRAVE},                   // Igrave
    {0x00cf, 0x00cf, ITM_I_DIARESIS},                // Idiaeresis
    {0x00ce, 0x00ce, ITM_I_CIRC},                    // Icircumflex
    {0x03c7, 0x012e, ITM_I_OGONEK},                  // Iogonek
    {0x01a3, 0x0141, ITM_L_STROKE},                  // Lstroke
    {0x01c5, 0x0139, ITM_L_ACUTE},                   // Lacute
    {0x01d1, 0x0143, ITM_N_ACUTE},                   // Nacute
    {0x01d2, 0x0147, ITM_N_CARON},                   // Ncaron
    {0x00d1, 0x00d1, ITM_N_TILDE},                   // Ntilde
    {0x03d2, 0x014c, ITM_O_MACRON},                  // Omacron
    {0x00d3, 0x00d3, ITM_O_ACUTE},                   // Oacute
    {0x00d2, 0x00d2, ITM_O_GRAVE},                   // Ograve
    {0x00d6, 0x00d6, ITM_O_DIARESIS},                // Odiaeresis
    {0x00d5, 0x00d5, ITM_O_TILDE},                   // Otilde
    {0x00d4, 0x00d4, ITM_O_CIRC},                    // Ocircumflex
    {0x13bc, 0x0152, ITM_OE},                        // OE
    {0x00df, 0x00df, ITM_S_SHARP},                   // ssharp
    {0x01a6, 0x015a, ITM_S_ACUTE},                   // Sacute
    {0x01a9, 0x0160, ITM_S_CARON},                   // Scaron
    {0x01aa, 0x015e, ITM_S_CEDILLA},                 // Scedilla
    {0x01ab, 0x0164, ITM_T_CARON},                   // Tcaron
    {0x01de, 0x0162, ITM_T_CEDILLA},                 // Tcedilla
    {0x03de, 0x016a, ITM_U_MACRON},                  // Umacron
    {0x00da, 0x00da, ITM_U_ACUTE},                   // Uacute
    {0x02dd, 0x016c, ITM_U_BREVE},                   // Ubreve
    {0x00d9, 0x00d9, ITM_U_GRAVE},                   // Ugrave
    {0x00dc, 0x00dc, ITM_U_DIARESIS},                // Udiaeresis
    {0x03dd, 0x0168, ITM_U_TILDE},                   // Utilde
    {0x00db, 0x00db, ITM_U_CIRC},                    // Ucircumflex
    {0x01d9, 0x016e, ITM_U_RING},                    // Uring
    {0x1000174, 0x0174, ITM_W_CIRC},                 // Wcircumflex
    {0x1000176, 0x0176, ITM_Y_CIRC},                 // Ycircumflex
    {0x00dd, 0x00dd, ITM_Y_ACUTE},                   // Yacute
    {0x13be, 0x0178, ITM_Y_DIARESIS},                // Ydiaeresis
    {0x01ac, 0x0179, ITM_Z_ACUTE},                   // Zacute
    {0x01ae, 0x017d, ITM_Z_CARON},                   // Zcaron
    {0x01af, 0x017b, ITM_Z_DOT},                     // Zabovedot
    {0x03e0, 0x0101, ITM_a_MACRON},                  // amacron
    {0x00e1, 0x00e1, ITM_a_ACUTE},                   // aacute
    {0x01e3, 0x0103, ITM_a_BREVE},                   // abreve
    {0x00e0, 0x00e0, ITM_a_GRAVE},                   // agrave
    {0x00e4, 0x00e4, ITM_a_DIARESIS},                // adiaeresis
    {0x00e3, 0x00e3, ITM_a_TILDE},                   // atilde
    {0x00e2, 0x00e2, ITM_a_CIRC},                    // acircumflex
    {0x00e5, 0x00e5, ITM_a_RING},                    // aring
    {0x00e6, 0x00e6, ITM_ae},                        // ae
    {0x01b1, 0x0105, ITM_a_OGONEK},                  // aogonek
    {0x01e6, 0x0107, ITM_c_ACUTE},                   // cacute
    {0x01e8, 0x010d, ITM_c_CARON},                   // ccaron
    {0x00e7, 0x00e7, ITM_c_CEDILLA},                 // ccedilla
    {0x01f0, 0x0111, ITM_d_STROKE},                  // dstroke
    {0x03ba, 0x0113, ITM_e_MACRON},                  // emacron
    {0x00e9, 0x00e9, ITM_e_ACUTE},                   // eacute
    {0x00e8, 0x00e8, ITM_e_GRAVE},                   // egrave
    {0x00eb, 0x00eb, ITM_e_DIARESIS},                // ediaeresis
    {0x00ea, 0x00ea, ITM_e_CIRC},                    // ecircumflex
    {0x01ea, 0x0119, ITM_e_OGONEK},                  // eogonek
    {0x02bb, 0x011f, ITM_g_BREVE},                   // gbreve
    {0x02b1, 0x0127, ITM_h_STROKE},                  // hstroke
    {0x03ef, 0x012b, ITM_i_MACRON},                  // imacron
    {0x00ed, 0x00ed, ITM_i_ACUTE},                   // iacute
    {0x100012d, 0x012d, ITM_i_BREVE},                // ibreve
    {0x00ec, 0x00ec, ITM_i_GRAVE},                   // igrave
    {0x00ef, 0x00ef, ITM_i_DIARESIS},                // idiaeresis
    {0x00ee, 0x00ee, ITM_i_CIRC},                    // icircumflex
    {0x03e7, 0x012f, ITM_i_OGONEK},                  // iogonek
    {0x02b9, 0x0131, ITM_i_DOTLESS},                 // idotless
    {0x01b3, 0x0142, ITM_l_STROKE},                  // lstroke
    {0x01e5, 0x013a, ITM_l_ACUTE},                   // lacute
    {0x01f1, 0x0144, ITM_n_ACUTE},                   // nacute
    {0x01f2, 0x0148, ITM_n_CARON},                   // ncaron
    {0x00f1, 0x00f1, ITM_n_TILDE},                   // ntilde
    {0x03f2, 0x014d, ITM_o_MACRON},                  // omacron
    {0x00f3, 0x00f3, ITM_o_ACUTE},                   // oacute
    {0x00f2, 0x00f2, ITM_o_GRAVE},                   // ograve
    {0x00f6, 0x00f6, ITM_o_DIARESIS},                // odiaeresis
    {0x00f5, 0x00f5, ITM_o_TILDE},                   // otilde
    {0x00f4, 0x00f4, ITM_o_CIRC},                    // ocircumflex
    {0x13bd, 0x0153, ITM_oe},                        // oe
    {0x01f8, 0x0159, ITM_r_CARON},                   // rcaron
    {0x01e0, 0x0155, ITM_r_ACUTE},                   // racute
    {0x01b6, 0x015b, ITM_s_ACUTE},                   // sacute
    {0x01b9, 0x0161, ITM_s_CARON},                   // scaron
    {0x01ba, 0x015f, ITM_s_CEDILLA},                 // scedilla
    {0x01fe, 0x0163, ITM_t_CEDILLA},                 // tcedilla
    {0x03fe, 0x016b, ITM_u_MACRON},                  // umacron
    {0x00fa, 0x00fa, ITM_u_ACUTE},                   // uacute
    {0x02fd, 0x016d, ITM_u_BREVE},                   // ubreve
    {0x00f9, 0x00f9, ITM_u_GRAVE},                   // ugrave
    {0x00fc, 0x00fc, ITM_u_DIARESIS},                // udiaeresis
    {0x03fd, 0x0169, ITM_u_TILDE},                   // utilde
    {0x00fb, 0x00fb, ITM_u_CIRC},                    // ucircumflex
    {0x01f9, 0x016f, ITM_u_RING},                    // uring
    {0x1000175, 0x0175, ITM_w_CIRC},                 // wcircumflex
    {0x1000177, 0x0177, ITM_y_CIRC},                 // ycircumflex
    {0x00fd, 0x00fd, ITM_y_ACUTE},                   // yacute
    {0x00ff, 0x00ff, ITM_y_DIARESIS},                // ydiaeresis
    {0x01bc, 0x017a, ITM_z_ACUTE},                   // zacute
    {0x01be, 0x017e, ITM_z_CARON},                   // zcaron
    {0x01bf, 0x017c, ITM_z_DOT},                     // zabovedot
    {0x005b, 0x005b, ITM_LEFT_SQUARE_BRACKET},       // bracketleft
    {0x005c, 0x005c, ITM_BACK_SLASH},                // backslash
    {0x005d, 0x005d, ITM_RIGHT_SQUARE_BRACKET},      // bracketright
    {0x005e, 0x005e, ITM_CIRCUMFLEX},                // asciicircum
    {0x005f, 0x005f, ITM_UNDERSCORE},                // underscore
    {0x007b, 0x007b, ITM_LEFT_CURLY_BRACKET},        // braceleft
    {0x007c, 0x007c, ITM_PIPE},                      // bar
    {0x007d, 0x007d, ITM_RIGHT_CURLY_BRACKET},       // braceright
    {0x007e, 0x007e, ITM_TILDE},                     // asciitilde
    {0x00a1, 0x00a1, ITM_INVERTED_EXCLAMATION_MARK}, // exclamdown
    {0x00a2, 0x00a2, ITM_CENT},                      // cent
    {0x00a3, 0x00a3, ITM_POUND},                     // sterling
    {0x00a5, 0x00a5, ITM_YEN},                       // yen
    {0x00a7, 0x00a7, ITM_SECTION},                   // section
    {0x00ab, 0x00ab, ITM_LEFT_DOUBLE_ANGLE},         // guillemotleft
    {0x00ac, 0x00ac, ITM_NOT},                       // notsign
    {0x00b0, 0x00b0, ITM_DEGREE},                    // degree
    {0x00b1, 0x00b1, ITM_PLUS_MINUS},                // plusminus
    {0x00b5, 0x00b5, ITM_MICRO},                     // mu
    {0x00bb, 0x00bb, ITM_RIGHT_DOUBLE_ANGLE},        // guillemotright
    {0x00bd, 0x00bd, ITM_ONE_HALF},                  // onehalf
    {0x00bc, 0x00bc, ITM_ONE_QUARTER},               // onequarter
    {0x00bd, 0x00bd, ITM_ONE_HALF},                  // onehalf
    {0x00bf, 0x00bf, ITM_INVERTED_QUESTION_MARK},    // questiondown
    {0x00d0, 0x00d0, ITM_ETH},                       // ETH
    {0x00d7, 0x00d7, ITM_CROSS},                     // multiply
    {0x00f0, 0x00f0, ITM_eth},                       // eth
    {0x03cc, 0x0116, ITM_E_DOT},                     // Eabovedot
    {0x03ec, 0x0117, ITM_e_DOT},                     // eabovedot
    {0x01cc, 0x011a, ITM_E_CARON},                   // Ecaron
    {0x01ec, 0x011b, ITM_e_CARON},                   // ecaron
    {0x01c0, 0x0154, ITM_R_ACUTE},                   // Racute
    {0x01d8, 0x0158, ITM_R_CARON},                   // Rcaron
    {0x03d9, 0x0172, ITM_U_OGONEK},                  // Uogonek
    {0x03f9, 0x0173, ITM_u_OGONEK},                  // uogonek
    {0x0aa1, 0x2003, ITM_SPACE_EM},                  // emspace
    {0x0aa3, 0x2004, ITM_SPACE_3_PER_EM},            // em3space
    {0x0aa4, 0x2005, ITM_SPACE_4_PER_EM},            // em4space
    {0x0aa5, 0x2007, ITM_SPACE_FIGURE},              // digitspace
    {0x0aa6, 0x2008, ITM_SPACE_PUNCTUATION},         // punctspace
    {0x0aa8, 0x200a, ITM_SPACE_HAIR},                // hairspace
    {0x0ad0, 0x2018, ITM_LEFT_SINGLE_QUOTE},         // leftsinglequotemark
    {0x0ad1, 0x2019, ITM_RIGHT_SINGLE_QUOTE},        // rightsinglequotemark
    {0x0afd, 0x201a, ITM_SINGLE_LOW_QUOTE},          // singlelowquotemark
    {0x0ad2, 0x201c, ITM_LEFT_DOUBLE_QUOTE},         // leftdoublequotemark
    {0x0ad3, 0x201d, ITM_RIGHT_DOUBLE_QUOTE},        // rightdoublequotemark
    {0x0afe, 0x201e, ITM_DOUBLE_LOW_QUOTE},          // doublelowquotemark
    {0x0aae, 0x2026, ITM_ELLIPSIS},                  // ellipsis
    {0x20ac, 0x20ac, ITM_EURO},                      // EuroSign
    {0x08fb, 0x2190, ITM_LEFT_ARROW},                // leftarrow
    {0x08fc, 0x2191, ITM_UP_ARROW},                  // uparrow
    {0x08fd, 0x2192, ITM_RIGHT_ARROW},               // rightarrow
    {0x08fe, 0x2193, ITM_DOWN_ARROW},                // downarrow
    {0x08ef, 0x2202, ITM_PARTIAL_DIFF},              // partialderivative
    {0x1002205, 0x2205, ITM_EMPTY_SET},              // emptyset
    {0x08c5, 0x2207, ITM_NABLA},                     // nabla
    {0x1002208, 0x2208, ITM_ELEMENT_OF},             // elementof
    {0x1002209, 0x2209, ITM_NOT_ELEMENT_OF},         // notelementof
    {0x100220b, 0x220b, ITM_CONTAINS},               // containsas
    {0x00b1, 0x00b1, ITM_MINUS_PLUS},                // plusminus
    {0x0bca, 0x2218, ITM_RING},                      // jot
    {0x0ae6, 0x2022, ITM_BULLET},                    // enfilledcircbullet
    {0x100221a, 0x221a, ITM_SQUARE_ROOT},            // squareroot
    {0x100221b, 0x221b, ITM_CUBEROOT_SIGN},          // cuberoot
    {0x08c2, 0x221e, ITM_INFINITY},                  // infinity
    {0x08de, 0x2227, ITM_AND},                       // logicaland
    {0x08df, 0x2228, ITM_OR},                        // logicalor
    {0x08dc, 0x2229, ITM_INTERSECTION},              // intersection
    {0x08dd, 0x222a, ITM_UNION},                     // union
    {0x08bf, 0x222b, ITM_INTEGRAL_SIGN},             // integral
    {0x100222c, 0x222c, ITM_DOUBLE_INTEGRAL},        // dintegral
    {0x0af3, 0x2713, ITM_CHECK_MARK},                // checkmark
    {0x08c9, 0x2243, ITM_ASYMPOTICALLY_EQUAL},       // similarequal
    {0x08c8, 0x223c, ITM_ALMOST_EQUAL},              // approximate
    {0x08bd, 0x2260, ITM_NOT_EQUAL},                 // notequal
    {0x08cf, 0x2261, ITM_IDENTICAL_TO},              // identical
    {0x08bc, 0x2264, ITM_LESS_EQUAL},                // lessthanequal
    {0x08be, 0x2265, ITM_GREATER_EQUAL},             // greaterthanequal
    {0x0bc2, 0x22a4, ITM_TRANSPOSED},                // downtack
    {0x1002070, 0x2070, ITM_SUP_0},                  // zerosuperior
    {0x00b9, 0x00b9, ITM_SUP_1},                     // onesuperior
    {0x00b2, 0x00b2, ITM_SUP_2},                     // twosuperior
    {0x00b3, 0x00b3, ITM_SUP_3},                     // threesuperior
    {0x1002074, 0x2074, ITM_SUP_4},                  // foursuperior
    {0x1002075, 0x2075, ITM_SUP_5},                  // fivesuperior
    {0x1002076, 0x2076, ITM_SUP_6},                  // sixsuperior
    {0x1002077, 0x2077, ITM_SUP_7},                  // sevensuperior
    {0x1002078, 0x2078, ITM_SUP_8},                  // eightsuperior
    {0x1002079, 0x2079, ITM_SUP_9},                  // ninesuperior
    {0, 0, 0},
};

typedef struct {
  int16_t item;
  int16_t item_macron;
  int16_t item_acute;
  int16_t item_breve;
  int16_t item_grave;
  int16_t item_diaresis;
  int16_t item_tilde;
  int16_t item_circ;
  int16_t item_caron;
  int16_t item_ogonek;
  int16_t item_ring;
  int16_t item_cedilla;
  int16_t item_stroke;
  int16_t item_dot;
} r47PcDeadKeys_t;

// gtkGui.c's deadKeysMap.
static const r47PcDeadKeys_t deadKeysMap[] = {
    {ITM_A, ITM_A_MACRON, ITM_A_ACUTE, ITM_A_BREVE, ITM_A_GRAVE, ITM_A_DIARESIS,
     ITM_A_TILDE, ITM_A_CIRC, ITM_A, ITM_A_OGONEK, ITM_A_RING, ITM_A, ITM_A,
     ITM_A},
    {ITM_C, ITM_C, ITM_C_ACUTE, ITM_C, ITM_C, ITM_C, ITM_C, ITM_C, ITM_C_CARON,
     ITM_C, ITM_C, ITM_C_CEDILLA, ITM_C, ITM_C},
    {ITM_D, ITM_D, ITM_D, ITM_D, ITM_D, ITM_D, ITM_D, ITM_D, ITM_D_CARON, ITM_D,
     ITM_D, ITM_D, ITM_D_STROKE, ITM_D},
    {ITM_E, ITM_E_MACRON, ITM_E_ACUTE, ITM_E_BREVE, ITM_E_GRAVE, ITM_E_DIARESIS,
     ITM_E, ITM_E_CIRC, ITM_E_CARON, ITM_E_OGONEK, ITM_E, ITM_E, ITM_E,
     ITM_E_DOT},
    {ITM_G, ITM_G, ITM_G, ITM_G_BREVE, ITM_G, ITM_G, ITM_G, ITM_G, ITM_G, ITM_G,
     ITM_G, ITM_G, ITM_G, ITM_G},
    {ITM_I, ITM_I_MACRON, ITM_I_ACUTE, ITM_I_BREVE, ITM_I_GRAVE, ITM_I_DIARESIS,
     ITM_I, ITM_I_CIRC, ITM_I, ITM_I_OGONEK, ITM_I, ITM_I, ITM_I, ITM_I_DOT},
    {ITM_L, ITM_L, ITM_L_ACUTE, ITM_L, ITM_L, ITM_L, ITM_L, ITM_L, ITM_L, ITM_L,
     ITM_L, ITM_L, ITM_L_STROKE, ITM_L},
    {ITM_N, ITM_N, ITM_N_ACUTE, ITM_N, ITM_N, ITM_N, ITM_N_TILDE, ITM_N,
     ITM_N_CARON, ITM_N, ITM_N, ITM_N, ITM_N, ITM_N},
    {ITM_O, ITM_O_MACRON, ITM_O_ACUTE, ITM_O_BREVE, ITM_O_GRAVE, ITM_O_DIARESIS,
     ITM_O_TILDE, ITM_O_CIRC, ITM_O, ITM_O, ITM_O, ITM_O, ITM_O_STROKE, ITM_O},
    {ITM_R, ITM_R, ITM_R_ACUTE, ITM_R, ITM_R, ITM_R, ITM_R, ITM_R, ITM_R_CARON,
     ITM_R, ITM_R, ITM_R, ITM_R, ITM_R},
    {ITM_S, ITM_S, ITM_S_ACUTE, ITM_S, ITM_S, ITM_S, ITM_S, ITM_S, ITM_S_CARON,
     ITM_S, ITM_S, ITM_S_CEDILLA, ITM_S, ITM_S},
    {ITM_T, ITM_T, ITM_T, ITM_T, ITM_T, ITM_T, ITM_T, ITM_T, ITM_T_CARON, ITM_T,
     ITM_T, ITM_T_CEDILLA, ITM_T, ITM_T},
    {ITM_U, ITM_U_MACRON, ITM_U_ACUTE, ITM_U_BREVE, ITM_U_GRAVE, ITM_U_DIARESIS,
     ITM_U_TILDE, ITM_U_CIRC, ITM_U, ITM_U_OGONEK, ITM_U_RING, ITM_U, ITM_U,
     ITM_U},
    {ITM_W, ITM_W, ITM_W, ITM_W, ITM_W, ITM_W, ITM_W, ITM_W_CIRC, ITM_W, ITM_W,
     ITM_W, ITM_W, ITM_W, ITM_W},
    {ITM_Y, ITM_Y, ITM_Y_ACUTE, ITM_Y, ITM_Y, ITM_Y_DIARESIS, ITM_Y, ITM_Y_CIRC,
     ITM_Y, ITM_Y, ITM_Y, ITM_Y, ITM_Y, ITM_Y},
    {ITM_Z, ITM_Z, ITM_Z_ACUTE, ITM_Z, ITM_Z, ITM_Z, ITM_Z, ITM_Z, ITM_Z_CARON,
     ITM_Z, ITM_Z, ITM_Z, ITM_Z, ITM_Z_DOT},
    {ITM_a, ITM_a_MACRON, ITM_a_ACUTE, ITM_a_BREVE, ITM_a_GRAVE, ITM_a_DIARESIS,
     ITM_a_TILDE, ITM_a_CIRC, ITM_a, ITM_a_OGONEK, ITM_a_RING, ITM_a, ITM_a,
     ITM_a},
    {ITM_c, ITM_c, ITM_c_ACUTE, ITM_c, ITM_c, ITM_c, ITM_c, ITM_c, ITM_c_CARON,
     ITM_c, ITM_c, ITM_c_CEDILLA, ITM_c, ITM_c},
    {ITM_d, ITM_d, ITM_d, ITM_d, ITM_d, ITM_d, ITM_d, ITM_d, ITM_d, ITM_d,
     ITM_d, ITM_d, ITM_d_STROKE, ITM_d},
    {ITM_e, ITM_e_MACRON, ITM_e_ACUTE, ITM_e_BREVE, ITM_e_GRAVE, ITM_e_DIARESIS,
     ITM_e, ITM_e_CIRC, ITM_e_CARON, ITM_e_OGONEK, ITM_e, ITM_e, ITM_e,
     ITM_e_DOT},
    {ITM_g, ITM_g, ITM_g, ITM_g_BREVE, ITM_g, ITM_g, ITM_g, ITM_g, ITM_g, ITM_g,
     ITM_g, ITM_g, ITM_g, ITM_g},
    {ITM_h, ITM_h, ITM_h, ITM_h, ITM_h, ITM_h, ITM_h, ITM_h, ITM_h, ITM_h,
     ITM_h, ITM_h, ITM_h_STROKE, ITM_h},
    {ITM_i, ITM_i_MACRON, ITM_i_ACUTE, ITM_i_BREVE, ITM_i_GRAVE, ITM_i_DIARESIS,
     ITM_i, ITM_i_CIRC, ITM_i, ITM_i_OGONEK, ITM_i, ITM_i, ITM_i, ITM_i_DOT},
    {ITM_l, ITM_l, ITM_l_ACUTE, ITM_l, ITM_l, ITM_l, ITM_l, ITM_l, ITM_l, ITM_l,
     ITM_l, ITM_l, ITM_l_STROKE, ITM_l},
    {ITM_n, ITM_n, ITM_n_ACUTE, ITM_n, ITM_n, ITM_n, ITM_n_TILDE, ITM_n,
     ITM_n_CARON, ITM_n, ITM_n, ITM_n, ITM_n, ITM_n},
    {ITM_o, ITM_o_MACRON, ITM_o_ACUTE, ITM_o_BREVE, ITM_o_GRAVE, ITM_o_DIARESIS,
     ITM_o_TILDE, ITM_o_CIRC, ITM_o, ITM_o, ITM_o, ITM_o, ITM_o_STROKE, ITM_o},
    {ITM_r, ITM_r, ITM_r_ACUTE, ITM_r, ITM_r, ITM_r, ITM_r, ITM_r, ITM_r_CARON,
     ITM_r, ITM_r, ITM_r, ITM_r, ITM_r},
    {ITM_s, ITM_s, ITM_s_ACUTE, ITM_s, ITM_s, ITM_s, ITM_s, ITM_s, ITM_s_CARON,
     ITM_s, ITM_s, ITM_s_CEDILLA, ITM_s, ITM_s},
    {ITM_t, ITM_t, ITM_t, ITM_t, ITM_t, ITM_t, ITM_t, ITM_t, ITM_t, ITM_t,
     ITM_t, ITM_t_CEDILLA, ITM_t, ITM_t},
    {ITM_u, ITM_u_MACRON, ITM_u_ACUTE, ITM_u_BREVE, ITM_u_GRAVE, ITM_u_DIARESIS,
     ITM_u_TILDE, ITM_u_CIRC, ITM_u, ITM_u_OGONEK, ITM_u_RING, ITM_u, ITM_u,
     ITM_u},
    {ITM_w, ITM_w, ITM_w, ITM_w, ITM_w, ITM_w, ITM_w, ITM_w_CIRC, ITM_w, ITM_w,
     ITM_w, ITM_w, ITM_w, ITM_w},
    {ITM_x, ITM_x, ITM_x, ITM_x, ITM_x, ITM_x, ITM_x, ITM_x_CIRC, ITM_x, ITM_x,
     ITM_x, ITM_x, ITM_x, ITM_x},
    {ITM_y, ITM_y, ITM_y_ACUTE, ITM_y, ITM_y, ITM_y_DIARESIS, ITM_y, ITM_y_CIRC,
     ITM_y, ITM_y, ITM_y, ITM_y, ITM_y, ITM_y},
    {ITM_z, ITM_z, ITM_z_ACUTE, ITM_z, ITM_z, ITM_z, ITM_z, ITM_z, ITM_z_CARON,
     ITM_z, ITM_z, ITM_z, ITM_z, ITM_z_DOT},
    {ITM_SPACE, ITM_SPACE, ITM_SPACE, ITM_SPACE, ITM_SPACE, ITM_SPACE,
     ITM_TILDE, ITM_CIRCUMFLEX, ITM_SPACE, ITM_SPACE, ITM_RING, ITM_SPACE,
     ITM_SPACE, ITM_DOT},
    {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0},
};

#define AlphaArrowsOffAndUpDn                                                  \
  ((bool_t)(softmenu[softmenuStack[0].softmenuId].menuItem == -MNU_SYSFL ||    \
            softmenu[softmenuStack[0].softmenuId].menuItem == -MNU_VAR ||      \
            softmenu[softmenuStack[0].softmenuId].menuItem == -MNU_PROG ||     \
            softmenu[softmenuStack[0].softmenuId].menuItem ==                  \
                -MNU_ALPHA_OMEGA ||                                            \
            softmenu[softmenuStack[0].softmenuId].menuItem ==                  \
                -MNU_alpha_omega ||                                            \
            softmenu[softmenuStack[0].softmenuId].menuItem ==                  \
                -MNU_ALPHAMISC ||                                              \
            softmenu[softmenuStack[0].softmenuId].menuItem ==                  \
                -MNU_ALPHAMATH ||                                              \
            softmenu[softmenuStack[0].softmenuId].menuItem ==                  \
                -MNU_ALPHAINTL ||                                              \
            softmenu[softmenuStack[0].softmenuId].menuItem == -MNU_ALPHAintl))

// The calculator thread's, as gtkGui.c's globals are the GTK thread's.
static uint32_t CTRL_State = 0;
static uint32_t SHIFT_State = 0;
static uint32_t event_keyval = 99999999;
static uint32_t event_command_shift = 0;
static uint32_t event_key_command = 99999999;
static bool pcKeyBusy = false; // the event was popped during a computation
static bool ctrlUsed = false;  // another key went down while Ctrl was down

// Text entry, where a key that arrives while the calculator is busy waits whole in
// the ring buffer until the calculator is idle.
bool r47_pc_key_in_text_entry(void) {
  return calcMode == CM_AIM || calcMode == CM_EIM || calcMode == CM_NIM ||
         calcMode == CM_MIM || calcMode == CM_PEM || tam.mode || tam.alpha ||
         catalog || getSystemFlag(FLAG_ALPHA);
}

static void pcKeyClicked(const char *data) {
  if (pcKeyBusy) {
    r47_key_push_click(data, 1);
  } else {
    btnClicked(NULL, (void *)data);
  }
}

static void pcKeyFnClicked(const char *data) {
  if (pcKeyBusy) {
    r47_key_push_click(data, 1);
  } else {
    btnFnClicked(NULL, (void *)data);
  }
}

static void btnFnClickedP(const char *data) {
  if (pcKeyBusy) {
    r47_key_push_key(data, true, 1);
  } else {
    btnFnPressed((void *)data);
  }
}

// A softkey pressed while the calculator was busy went to the buffer, where the
// computation may have taken the press, so at rest it is released only if the core
// still holds a softkey: upstream's btnFnReleased with none down shifts by a
// negative count, which GTK reaches the same way.
static void btnFnClickedR(const char *data) {
  if (pcKeyBusy) {
    r47_key_push_key(data, false, 1);
  } else if (FN_key_pressed != 0) {
    btnFnReleased((void *)data);
  }
}

// A digit in text entry, as a digit: NUMLOCK off and g held for the click.
static void btnClicked_NU(const char *data) {
  const bool numLock_MEM = getSystemFlag(FLAG_NUMLOCK);
  clearSystemFlag(FLAG_NUMLOCK);
  shiftF = false;
  shiftG = true;
  btnClicked(NULL, (void *)data);
  if (numLock_MEM) {
    setSystemFlag(FLAG_NUMLOCK);
  } else {
    clearSystemFlag(FLAG_NUMLOCK);
  }
  refreshStatusBar();
}

// The key a shortcut to an item gives while the calculator is busy: a click of the
// key the item sits on, with the f or g shift it takes there.
static void clickItemWhileBusy(int16_t item) {
  const calcKey_t *keys = getSystemFlag(FLAG_USER) ? kbd_usr : kbd_std;
  char data[3];
  for (int i = 0; i < 37; i++) {
    const int button = keys[i].primary == item    ? 1
                       : keys[i].fShifted == item ? 2
                       : keys[i].gShifted == item ? 3
                                                  : 0;
    if (button != 0) {
      if (r47_format(data, sizeof(data), "%02d", i)) { // i < 37 always fits
        r47_key_push_click(data, button);
      }
      return;
    }
  }
}

static bool checkNormal(int16_t keyNr, int16_t item) {
  int16_t result = Norm_Key_00_item_in_layout;
  const int16_t ss = Check_Norm_Key_00_Assigned(&result, keyNr);
  return ss == item;
}

static bool rowRuns(const r47PcRow_t *row) {
  return ((1u << calcMode) & row->modes) || calcMode == row->requiredCalcMode2;
}

static void setRowShift(const r47PcRow_t *row) {
  if (row->shift == 'f') {
    shiftF = true;
    shiftG = false;
  } else if (row->shift == 'g') {
    shiftF = false;
    shiftG = true;
  }
}

static int rowButton(const r47PcRow_t *row) {
  return row->shift == 'f' ? 2 : row->shift == 'g' ? 3 : 1;
}

static bool shortCutCommand(uint32_t key, const r47PcRow_t *row) {
  const bool clicks = row->keyForBtnClicked[0] != '-';
  if (row->disable == R47_PC_BY_TAM && tam.mode) {
    return false;
  }
  if (labelText && !(key == '\'' || key == GDK_KEY_Up || key == GDK_KEY_Down)) {
    return false;
  }
  if ((shiftF || shiftG) && !(row->shift == 0 && clicks) &&
      !(shiftF && row->shift == 'f' && clicks) &&
      !(shiftG && row->shift == 'g' && clicks)) {
    return false;
  }
  if (key != row->keyCode) {
    return false;
  }
  if (pcKeyBusy) {
    if (row->itemForRunFunction < 0) {
      clickItemWhileBusy(row->itemForRunFunction);
      return true;
    }
    if (rowRuns(row)) {
      if (clicks) {
        r47_key_push_click(row->keyForBtnClicked, rowButton(row));
      } else {
        clickItemWhileBusy(row->itemForRunFunction);
      }
      return true;
    }
    return false;
  }
  if (temporaryInformation == TI_WHO &&
      (row->itemForRunFunction < 0 || !clicks)) {
    closeShowMenu(); // no key press follows to close the WHO screen
  }
  temporaryInformation = TI_NO_INFO;
  if (row->exitIfInNim && calcMode == CM_NIM &&
      calcMode != row->requiredCalcMode2) {
    closeNim();
  }
  if (row->itemForRunFunction < 0) {
    showSoftmenu(row->itemForRunFunction);
    screenUpdatingMode = SCRUPD_AUTO;
    refreshScreen(1);
    return true;
  }
  if (rowRuns(row)) {
    if (clicks) {
      setRowShift(row);
      pcKeyClicked(row->keyForBtnClicked);
      screenUpdatingMode = SCRUPD_AUTO;
      refreshScreen(2);
      return true;
    }
    if (row->itemForRunFunction >= 0) {
      runFunction(row->itemForRunFunction);
      screenUpdatingMode = SCRUPD_AUTO;
      refreshScreen(3);
      return true;
    }
  }
  return false;
}

static bool shortCutFNCommand(uint32_t key, const r47PcRow_t *row) {
  const bool clicks = row->keyForBtnClicked[0] != '-';
  if (row->disable == R47_PC_BY_TAM && tam.mode) {
    return false;
  }
  if (labelText || key != row->keyCode) {
    return false;
  }
  if (pcKeyBusy) {
    if (rowRuns(row) && clicks) {
      r47_key_push_click(row->keyForBtnClicked, rowButton(row));
      return true;
    }
    return false;
  }
  temporaryInformation = TI_NO_INFO;
  if (rowRuns(row) && clicks) {
    setRowShift(row);
    pcKeyFnClicked(row->keyForBtnClicked);
    screenUpdatingMode = SCRUPD_AUTO;
    refreshScreen(5);
    return true;
  }
  return false;
}

// Runs a chain as gtkGui.c's else-if does: the first row that acts ends it. A
// shortCutFNCommand row matches the keysym with Ctrl's mark, as GTK passes it.
static bool runRows(const r47PcRow_t *rows, size_t count, uint32_t key) {
  for (size_t i = 0; i < count; i++) {
    if (rows[i].fn ? shortCutFNCommand(event_keyval, &rows[i])
                   : shortCutCommand(key, &rows[i])) {
      return true;
    }
  }
  return false;
}

#define RUN_ROWS(rows, key)                                                    \
  runRows((rows), sizeof(rows) / sizeof((rows)[0]), (key))

static void sendKey(int16_t sent) {
  showHideAlphaMode();
  if (calcMode == CM_PEM && !tam.mode && getSystemFlag(FLAG_ALPHA) &&
      !catalog) {
    pemAlpha(sent);
  } else {
    processAimInput(sent);
  }
}

static int16_t getGdkKeyItem(uint32_t gdkKey) {
  if ((GDK_KEY_Shift_L <= gdkKey && gdkKey <= GDK_KEY_Hyper_R) ||
      (GDK_KEY_Home <= gdkKey && gdkKey <= GDK_KEY_Begin) ||
      (GDK_KEY_F1 <= gdkKey && gdkKey <= GDK_KEY_F14) ||
      (GDK_KEY_zerosubscript < gdkKey)) {
    return 0;
  }
  if (GDK_KEY_0 <= gdkKey && gdkKey <= GDK_KEY_9) {
    return (int16_t)(ITM_0 + (gdkKey - GDK_KEY_0));
  }
  if (GDK_KEY_A <= gdkKey && gdkKey <= GDK_KEY_Z) {
    return (int16_t)(ITM_A + (gdkKey - GDK_KEY_A));
  }
  if (GDK_KEY_a <= gdkKey && gdkKey <= GDK_KEY_z) {
    return (int16_t)(ITM_a + (gdkKey - GDK_KEY_a));
  }
  if (GDK_KEY_KP_0 <= gdkKey && gdkKey <= GDK_KEY_KP_9) {
    return (int16_t)(ITM_0 + (gdkKey - GDK_KEY_KP_0));
  }
  if (GDK_KEY_KP_Multiply <= gdkKey && gdkKey <= GDK_KEY_KP_Divide) {
    return (int16_t)(ITM_ASTERISK + (gdkKey - GDK_KEY_KP_Multiply));
  }
  if (GDK_KEY_space <= gdkKey && gdkKey <= GDK_KEY_slash) {
    return (int16_t)(ITM_SPACE + (gdkKey - GDK_KEY_space));
  }
  if (GDK_KEY_colon <= gdkKey && gdkKey <= GDK_KEY_at) {
    return (int16_t)(ITM_COLON + (gdkKey - GDK_KEY_colon));
  }
  if (GDK_KEY_zerosubscript <= gdkKey && gdkKey <= GDK_KEY_ninesubscript) {
    return (int16_t)(ITM_SUB_0 + (gdkKey - GDK_KEY_zerosubscript));
  }
  int i = 0;
  while (r47PcKeysyms[i].item != 0 && r47PcKeysyms[i].keysym != gdkKey) {
    i++;
  }
  return r47PcKeysyms[i].item;
}

static int16_t getDeadKeyItem(int16_t item) {
  if (deadKey == GDK_KEY_F12) {
    if (ITM_A <= item && item <= ITM_Z) {
      static const int16_t upper[26] = {
          ITM_ALPHA,   ITM_BETA, ITM_GAMMA, ITM_DELTA, ITM_EPSILON, ITM_PHI,
          ITM_GAMMA,   ITM_CHI,  ITM_IOTA,  ITM_ETA,   ITM_KAPPA,   ITM_LAMBDA,
          ITM_MU,      ITM_NU,   ITM_OMEGA, ITM_PI,    ITM_OMICRON, ITM_RHO,
          ITM_SIGMA,   ITM_TAU,  ITM_THETA, ITM_QOPPA, ITM_PSI,     ITM_XI,
          ITM_UPSILON, ITM_ZETA};
      return upper[item - ITM_A];
    }
    if (ITM_a <= item && item <= ITM_z) {
      static const int16_t lower[26] = {
          ITM_alpha,   ITM_beta, ITM_gamma, ITM_delta, ITM_epsilon, ITM_phi,
          ITM_gamma,   ITM_chi,  ITM_iota,  ITM_eta,   ITM_kappa,   ITM_lambda,
          ITM_mu,      ITM_nu,   ITM_omega, ITM_pi,    ITM_omicron, ITM_rho,
          ITM_sigma,   ITM_tau,  ITM_theta, ITM_qoppa, ITM_psi,     ITM_xi,
          ITM_upsilon, ITM_zeta};
      return lower[item - ITM_a];
    }
    return item;
  }
  for (int i = 0; deadKeysMap[i].item != 0; i++) {
    if (deadKeysMap[i].item != item) {
      continue;
    }
    switch (deadKey) {
    case GDK_KEY_dead_macron:
      return deadKeysMap[i].item_macron;
    case GDK_KEY_dead_acute:
      return deadKeysMap[i].item_acute;
    case GDK_KEY_dead_breve:
      return deadKeysMap[i].item_breve;
    case GDK_KEY_dead_grave:
      return deadKeysMap[i].item_grave;
    case GDK_KEY_dead_diaeresis:
      return deadKeysMap[i].item_diaresis;
    case GDK_KEY_dead_tilde:
      return deadKeysMap[i].item_tilde;
    case GDK_KEY_dead_circumflex:
      return deadKeysMap[i].item_circ;
    case GDK_KEY_dead_ogonek:
      return deadKeysMap[i].item_ogonek;
    case GDK_KEY_dead_abovering:
      return deadKeysMap[i].item_ring;
    case GDK_KEY_dead_cedilla:
      return deadKeysMap[i].item_cedilla;
    case GDK_KEY_dead_stroke:
      return deadKeysMap[i].item_stroke;
    case GDK_KEY_dead_abovedot:
      return deadKeysMap[i].item_dot;
    default:
      break;
    }
  }
  return item;
}

// The item a typed key gives, 0 for none, or -1 for a dead key, which waits for
// the next key.
static int16_t keyCodeFromGdkKey(uint32_t gdkK) {
  uint32_t gdkKey = gdkK;
  if (testDeadKeys) { // a script's stand-ins for dead keys
    switch (gdkKey) {
    case '^':
      gdkKey = GDK_KEY_dead_circumflex;
      break;
    case '`':
      gdkKey = GDK_KEY_dead_grave;
      break;
    case '\'':
      gdkKey = GDK_KEY_dead_acute;
      break;
    case '~':
      gdkKey = GDK_KEY_dead_tilde;
      break;
    case '/':
      gdkKey = GDK_KEY_dead_stroke;
      break;
    default:
      break;
    }
  }
  switch (gdkKey) {
  case GDK_KEY_F12:
  case GDK_KEY_dead_macron:
  case GDK_KEY_dead_acute:
  case GDK_KEY_dead_breve:
  case GDK_KEY_dead_grave:
  case GDK_KEY_dead_diaeresis:
  case GDK_KEY_dead_tilde:
  case GDK_KEY_dead_circumflex:
  case GDK_KEY_dead_ogonek:
  case GDK_KEY_dead_abovering:
  case GDK_KEY_dead_cedilla:
  case GDK_KEY_dead_stroke:
  case GDK_KEY_dead_abovedot:
    if (deadKey != 0 && deadKey == gdkKey && testDeadKeys) {
      deadKey = 0; // the dead key twice types its own character
      switch (gdkKey) {
      case GDK_KEY_dead_circumflex:
        gdkKey = '^';
        break;
      case GDK_KEY_dead_grave:
        gdkKey = '`';
        break;
      case GDK_KEY_dead_acute:
        gdkKey = '\'';
        break;
      case GDK_KEY_dead_tilde:
        gdkKey = '~';
        break;
      case GDK_KEY_dead_stroke:
        gdkKey = '/';
        break;
      default:
        break;
      }
      showHideAlphaMode();
      lcd_refresh();
      break;
    }
    deadKey = gdkKey;
    showHideAlphaMode();
    lcd_refresh();
    return -1;
  default:
    break;
  }
  int16_t item;
  switch (gdkKey) {
  case GDK_KEY_Tab:
    item = ITM_CR;
    break;
  case '`':
    item = ITM_NQUOTE;
    break;
  case '*':
    item = ITM_PROD_SIGN;
    break;
  default:
    item = getGdkKeyItem(gdkKey);
    break;
  }
  if (item == ITM_PROD_SIGN) {
    item = getSystemFlag(FLAG_MULTx) ? ITM_CROSS : ITM_DOT;
  }
  if (item != 0 && deadKey != 0) {
    item = getDeadKeyItem(item);
    deadKey = 0;
  }
  return item;
}

// The press of the layout's own shift key, or a shift toggled, as gtkGui.c's Shift
// and Ctrl releases give one.
static void releaseShift(void) {
  if (checkNormal(0, KEY_fg)) {
    pcKeyClicked("00");
  } else if (checkNormal(10, KEY_fg)) {
    pcKeyClicked("10");
  } else if (checkNormal(11, KEY_fg)) {
    pcKeyClicked("11");
  } else if (checkNormal(0, ITM_SHIFTf)) {
    pcKeyClicked("00");
  } else if (checkNormal(10, ITM_SHIFTf)) {
    pcKeyClicked("10");
  } else if (checkNormal(11, ITM_SHIFTf)) {
    pcKeyClicked("11");
  } else if ((getSystemFlag(FLAG_USER) ? kbd_usr[10].primary
                                       : kbd_std[10].primary) == ITM_SHIFTf) {
    pcKeyClicked("10");
  } else if ((getSystemFlag(FLAG_USER) ? kbd_usr[0].primary
                                       : kbd_std[0].primary) == KEY_fg) {
    pcKeyClicked("00");
  } else if ((getSystemFlag(FLAG_USER) ? kbd_usr[10].primary
                                       : kbd_std[10].primary) == KEY_fg) {
    pcKeyClicked("10");
  } else if ((getSystemFlag(FLAG_USER) ? kbd_usr[11].primary
                                       : kbd_std[11].primary) == KEY_fg) {
    pcKeyClicked("11");
  } else if ((getSystemFlag(FLAG_USER) ? kbd_usr[27].primary
                                       : kbd_std[27].primary) == KEY_fg) {
    pcKeyClicked("27");
  } else if (!pcKeyBusy) {
    shiftF = !shiftF;
    shiftG = false;
    refreshStatusBar();
    showShiftState();
  }
}

static void releaseCtrl(void) {
  if (checkNormal(0, KEY_fg)) {
    pcKeyClicked("00");
  } else if (checkNormal(10, KEY_fg)) {
    pcKeyClicked("10");
  } else if (checkNormal(11, KEY_fg)) {
    pcKeyClicked("11");
  } else if (checkNormal(0, ITM_SHIFTg)) {
    pcKeyClicked("00");
  } else if (checkNormal(10, ITM_SHIFTg)) {
    pcKeyClicked("10");
  } else if (checkNormal(11, ITM_SHIFTg)) {
    pcKeyClicked("11");
  } else if ((getSystemFlag(FLAG_USER) ? kbd_usr[11].primary
                                       : kbd_std[11].primary) == ITM_SHIFTg) {
    pcKeyClicked("11");
  } else if ((getSystemFlag(FLAG_USER) ? kbd_usr[27].primary
                                       : kbd_std[27].primary) == KEY_fg) {
    const bool noShift = !shiftF && !shiftG; // one key steps none, f, g, none
    pcKeyClicked("27");
    if (noShift) {
      pcKeyClicked("27");
    }
  } else if (!pcKeyBusy) {
    shiftF = false;
    shiftG = !shiftG;
    refreshStatusBar();
    showShiftState();
  }
}

static void keyReleasedNow(uint32_t keyval) {
  if (event_keyval == keyval + CTRL_State) {
    event_keyval = 99999999;
  }
  switch (keyval) {
  case GDK_KEY_Shift_L:
  case GDK_KEY_Shift_R:
    event_command_shift = 0;
    // f on the release, so a PC shifted character still types
    if (SHIFT_State != 0) {
      releaseShift();
    }
    SHIFT_State = 0;
    break;
  case GDK_KEY_Control_L:
  case GDK_KEY_Control_R:
    if (CTRL_State != 0 && !ctrlUsed) {
      releaseCtrl();
    }
    CTRL_State = 0;
    ctrlUsed = false;
    event_keyval = 99999999;
    break;
  case GDK_KEY_F1:
  case GDK_KEY_F2:
  case GDK_KEY_F3:
  case GDK_KEY_F4:
  case GDK_KEY_F5:
  case GDK_KEY_F6:
    if (labelText || !tam.mode || (tam.mode && AlphaArrowsOffAndUpDn)) {
      const char softkey[2] = {(char)('1' + (keyval - GDK_KEY_F1)), 0};
      btnFnClickedR(softkey);
    }
    break;
  default:
    break;
  }
  if (keyval != GDK_KEY_Shift_L && keyval != GDK_KEY_Shift_R) {
    SHIFT_State = 0;
  }
}

// GTK's event_key_strip_capslock: a letter's case from the Shift key alone.
static uint32_t stripCapsLock(uint32_t keyval) {
  if (('A' <= keyval && keyval <= 'Z') || ('a' <= keyval && keyval <= 'z')) {
    return (keyval & 0xFFFFDF) + (0x20 & ~(event_command_shift >> (16 - 5)));
  }
  return keyval;
}

static void clickFLetter(void) {
  if (checkNormal(0, ITM_SHIFTf)) {
    pcKeyClicked("00");
  } else if (checkNormal(10, ITM_SHIFTf)) {
    pcKeyClicked("10");
  } else if (checkNormal(11, ITM_SHIFTf)) {
    pcKeyClicked("11");
  } else if ((getSystemFlag(FLAG_USER) ? kbd_usr[10].primary
                                       : kbd_std[10].primary) == ITM_SHIFTf) {
    pcKeyClicked("10");
  } else if ((getSystemFlag(FLAG_USER) ? kbd_usr[11].primary
                                       : kbd_std[11].primary) == ITM_SHIFTf) {
    pcKeyClicked("11");
  } else if ((getSystemFlag(FLAG_USER) ? kbd_usr[10].primary
                                       : kbd_std[10].primary) == KEY_fg) {
    pcKeyClicked("10");
  } else if ((getSystemFlag(FLAG_USER) ? kbd_usr[11].primary
                                       : kbd_std[11].primary) == KEY_fg) {
    pcKeyClicked("11");
  } else if ((getSystemFlag(FLAG_USER) ? kbd_usr[27].primary
                                       : kbd_std[27].primary) == KEY_fg) {
    pcKeyClicked("27");
  }
}

static void clickGLetter(void) {
  if (checkNormal(0, ITM_SHIFTg)) {
    pcKeyClicked("00");
  } else if (checkNormal(10, ITM_SHIFTg)) {
    pcKeyClicked("10");
  } else if (checkNormal(11, ITM_SHIFTg)) {
    pcKeyClicked("11");
  } else if ((getSystemFlag(FLAG_USER) ? kbd_usr[11].primary
                                       : kbd_std[11].primary) == ITM_SHIFTg) {
    pcKeyClicked("11");
  } else if ((getSystemFlag(FLAG_USER) ? kbd_usr[10].primary
                                       : kbd_std[10].primary) == ITM_SHIFTg) {
    pcKeyClicked("10");
  } else if (!pcKeyBusy) {
    shiftF = false;
    shiftG = !shiftG;
    refreshStatusBar();
    showShiftState();
  }
}

static const int16_t storclLetterRegisters[26] = {
    ITM_REG_A, ITM_REG_B, ITM_REG_C, ITM_REG_D, ITM_REG_E, ITM_REG_F, ITM_REG_G,
    ITM_REG_H, ITM_REG_I, ITM_REG_J, ITM_REG_K, ITM_REG_L, ITM_REG_M, ITM_REG_N,
    ITM_REG_O, ITM_REG_P, ITM_REG_Q, ITM_REG_R, ITM_REG_S, ITM_REG_T, ITM_REG_U,
    ITM_REG_V, ITM_REG_W, ITM_REG_X, ITM_REG_Y, ITM_REG_Z};

static void addItemAndRefresh(int16_t item) {
  addItemToBuffer(item);
  screenUpdatingMode = SCRUPD_AUTO;
  refreshScreen(3);
}

// The Ctrl commands, but the three GTK copies as images.
static void runCtrlCommand(void) {
  switch (event_keyval) {
  case 'S' + 65536:
  case 's' + 65536:
    fnSNAP(NOPARAM);
    break;
  case 'x' + 65536:
  case 'X' + 65536:
  case 'c' + 65536:
  case 'C' + 65536:
    copyRegisterXToClipboard();
    break;
  case 'd' + 65536:
  case 'D' + 65536:
    copyStackRegistersToClipboard();
    break;
  case 'a' + 65536:
  case 'A' + 65536:
    copyAllRegistersToClipboard();
    break;
  case 'N' + 65536:
  case 'n' + 65536:
    fnSNAPMENU(NOPARAM);
    break;
  case 'J' + 65536:
  case 'j' + 65536:
    fnSNAPXBold(NOPARAM);
    break;
  default:
    break;
  }
}

static void textEntryKey(void) {
  switch (event_keyval) {
  case GDK_KEY_Up:
    if (AlphaArrowsOffAndUpDn || calcMode == CM_EIM) {
      pcKeyClicked(isR47FAM ? "22" : "17");
    } else if (!tam.mode) {
      pcKeyFnClicked("1");
    }
    break;
  case GDK_KEY_Down:
    if (AlphaArrowsOffAndUpDn || calcMode == CM_EIM) {
      pcKeyClicked(isR47FAM ? "27" : "22");
    } else {
      pcKeyFnClicked("2");
    }
    break;
  case GDK_KEY_Left:
    pcKeyFnClicked("5");
    break;
  case GDK_KEY_Right:
    pcKeyFnClicked("6");
    break;
  case GDK_KEY_F1:
  case GDK_KEY_F2:
  case GDK_KEY_F3:
  case GDK_KEY_F4:
  case GDK_KEY_F5:
  case GDK_KEY_F6: {
    const char softkey[2] = {(char)('1' + (event_keyval - GDK_KEY_F1)), 0};
    if (calcMode == CM_EIM || AlphaArrowsOffAndUpDn || labelText) {
      btnFnClickedP(softkey);
    } else {
      pcKeyFnClicked(softkey);
    }
    break;
  }
  case GDK_KEY_KP_Enter:
  case GDK_KEY_Return:
    pcKeyClicked("12");
    break;
  case GDK_KEY_BackSpace:
    pcKeyClicked("16");
    break;
  case GDK_KEY_KP_Delete:
  case GDK_KEY_Delete:
    fnT_ARROW(ITM_T_RIGHT_ARROW);
    pcKeyClicked("16");
    break;
  case GDK_KEY_Home:
    pcKeyClicked("17");
    break;
  case GDK_KEY_End:
    pcKeyClicked("22");
    break;
  case GDK_KEY_KP_7:
  case '7':
    btnClicked_NU("18");
    break;
  case GDK_KEY_KP_8:
  case '8':
    btnClicked_NU("19");
    break;
  case GDK_KEY_KP_9:
  case '9':
    btnClicked_NU("20");
    break;
  case GDK_KEY_KP_4:
  case '4':
    btnClicked_NU("23");
    break;
  case GDK_KEY_KP_5:
  case '5':
    btnClicked_NU("24");
    break;
  case GDK_KEY_KP_6:
  case '6':
    btnClicked_NU("25");
    break;
  case GDK_KEY_KP_1:
  case '1':
    btnClicked_NU("28");
    break;
  case GDK_KEY_KP_2:
  case '2':
    btnClicked_NU("29");
    break;
  case GDK_KEY_KP_3:
  case '3':
    btnClicked_NU("30");
    break;
  case GDK_KEY_KP_0:
  case '0':
    btnClicked_NU("33");
    break;
  case '.':
  case GDK_KEY_KP_Decimal:
    btnClicked_NU("34");
    break;
  case GDK_KEY_KP_Divide:
  case '/':
    btnClicked_NU("21");
    break;
  case GDK_KEY_KP_Multiply:
  case '*':
    btnClicked_NU("26");
    break;
  case GDK_KEY_KP_Subtract:
  case '-':
    btnClicked_NU("31");
    break;
  case GDK_KEY_KP_Add:
  case '+':
    btnClicked_NU("36");
    break;
  case GDK_KEY_Escape:
    pcKeyClicked("32");
    break;
  default:
    break;
  }
}

static void calculatorKey(void) {
  switch (event_keyval) {
  case GDK_KEY_question: // the blank key
    if (calcModel == USER_R47fg_bk &&
        (calcMode == CM_NORMAL || calcMode == CM_NIM)) {
      pcKeyClicked("11");
    } else if (calcModel == USER_R47bk_fg &&
               (calcMode == CM_NORMAL || calcMode == CM_NIM)) {
      pcKeyClicked("10");
    }
    break;
  case GDK_KEY_Left:
    pcKeyFnClicked("5");
    break;
  case GDK_KEY_Right:
    pcKeyFnClicked("6");
    break;
  case GDK_KEY_F1:
  case GDK_KEY_F2:
  case GDK_KEY_F3:
  case GDK_KEY_F4:
  case GDK_KEY_F5:
  case GDK_KEY_F6: {
    const char softkey[2] = {(char)('1' + (event_keyval - GDK_KEY_F1)), 0};
    btnFnClickedP(softkey);
    break;
  }
  case GDK_KEY_KP_Enter:
  case GDK_KEY_Return:
    pcKeyClicked("12");
    break;
  case 'w':
    pcKeyClicked("13");
    break;
  case 'n':
    pcKeyClicked("14");
    break;
  case 'e':
    pcKeyClicked("15");
    break;
  case GDK_KEY_BackSpace:
    pcKeyClicked("16");
    break;
  case GDK_KEY_Up:
    pcKeyClicked(isR47FAM ? "22" : "17");
    break;
  case '7':
  case GDK_KEY_KP_7:
    pcKeyClicked("18");
    break;
  case '8':
  case GDK_KEY_KP_8:
    pcKeyClicked("19");
    break;
  case '9':
  case GDK_KEY_KP_9:
    pcKeyClicked("20");
    break;
  case '/':
  case GDK_KEY_KP_Divide:
    pcKeyClicked("21");
    break;
  case GDK_KEY_Down:
    pcKeyClicked(isR47FAM ? "27" : "22");
    break;
  case '4':
  case GDK_KEY_KP_4:
    pcKeyClicked("23");
    break;
  case '5':
  case GDK_KEY_KP_5:
    pcKeyClicked("24");
    break;
  case '6':
  case GDK_KEY_KP_6:
    pcKeyClicked("25");
    break;
  case '*':
  case GDK_KEY_KP_Multiply:
    pcKeyClicked("26");
    break;
  case '1':
  case GDK_KEY_KP_1:
    pcKeyClicked("28");
    break;
  case '2':
  case GDK_KEY_KP_2:
    pcKeyClicked("29");
    break;
  case '3':
  case GDK_KEY_KP_3:
    pcKeyClicked("30");
    break;
  case '-':
  case GDK_KEY_KP_Subtract:
    pcKeyClicked("31");
    break;
  case GDK_KEY_Escape:
    pcKeyClicked("32");
    break;
  case '0':
  case GDK_KEY_KP_0:
    pcKeyClicked("33");
    break;
  case ',':
  case '.':
  case GDK_KEY_KP_Decimal:
    pcKeyClicked("34");
    break;
  case '+':
  case GDK_KEY_KP_Add:
    pcKeyClicked("36");
    break;
  default:
    break;
  }
}

// gtkGui.c's AIM, EIM, alpha PEM, alpha ASSIGN, catalog, register and flag TAM, and
// label section: the typed character, in the case the calculator shows. Returns
// whether it took the key.
static bool typedCharacter(uint32_t keyval) {
  const uint8_t alphaCase_MEM = alphaCase;
  uint32_t ll = keyval;
  if ('A' <= ll && ll <= 'Z' && alphaCase == AC_UPPER) { // A-Z flips
    ll += 'a' - 'A';
    alphaCase = AC_LOWER;
  } else if ('A' <= ll && ll <= 'Z' && alphaCase == AC_LOWER) {
    alphaCase = AC_UPPER;
  } else if ('a' <= ll && ll <= 'z' && alphaCase == AC_UPPER) {
    ll -= 'a' - 'A';
  }
  const int16_t item = keyCodeFromGdkKey(ll);
  if (item > 0) {
    lastshiftF = shiftF;
    lastshiftG = shiftG;
    sendKey(item);
    screenUpdatingMode = SCRUPD_AUTO;
    refreshStatusBar();
    refreshScreen(8);
    lcd_refresh();
    resetShiftState();
    alphaCase = alphaCase_MEM;
    return true;
  }
  if (item == -1) {
    screenUpdatingMode = SCRUPD_AUTO;
    alphaCase = alphaCase_MEM;
    refreshStatusBar();
    refreshScreen(8);
    lcd_refresh();
    resetShiftState();
    return true;
  }
  alphaCase = alphaCase_MEM;
  return false;
}

static void keyPressedNow(uint32_t keyval, uint32_t state) {
  if ((state & GDK_CONTROL_MASK) && (keyval == 'p' || keyval == 'P')) {
    CTRL_State = 0; // GTK's printer window takes Ctrl+P, and the app has none
    return;
  }
  const bool ctrlPressed =
      swapCtrlCode ? (keyval == GDK_KEY_Control_L && !(state & 0b00100))
                   : (keyval == GDK_KEY_Control_L && (state & 0b00100));
  const bool altGrPressed = keyval == GDK_KEY_Alt_R && (state & 0b10100);
  const bool allowAltGrKey = !altGrPressed && !ctrlPressed && (state & 0b11100);

  if (CTRL_State != 0 && !(state & GDK_CONTROL_MASK) &&
      keyval != GDK_KEY_Control_L && keyval != GDK_KEY_Control_R) {
    CTRL_State = 0; // the release of Ctrl went elsewhere
    ctrlUsed = false;
  }
  const bool keyRepeat = event_keyval == keyval + CTRL_State;
  event_keyval = keyval + CTRL_State;

  if (ctrlPressed) {
    CTRL_State = 65536;
    goto continueWithOldDetections;
  }
  if (altGrPressed) { // clears any Ctrl the AltGr press began
    SHIFT_State = 0;
    event_command_shift = 0;
    CTRL_State = 0;
    if (!pcKeyBusy) {
      shiftF = false;
      shiftG = false;
      refreshStatusBar();
      showShiftState();
    }
    return;
  }

  SHIFT_State = 0;
  switch (event_keyval) {
  case GDK_KEY_Shift_L:
  case GDK_KEY_Shift_R:
    SHIFT_State = 65536;
    event_command_shift = 65536;
    break;
  case GDK_KEY_Control_L:
  case GDK_KEY_Control_R:
    CTRL_State = 65536;
    break;
  default:
    break;
  }

  if (CTRL_State == 65536) {
    if (keyval != GDK_KEY_Control_L && keyval != GDK_KEY_Control_R) {
      ctrlUsed = true;
    }
    goto continueWithOldDetections;
  }

  if (!(calcMode == CM_AIM || calcMode == CM_EIM || tam.mode ||
        (calcMode == CM_PEM && getSystemFlag(FLAG_ALPHA)) || tam.alpha)) {
    switch (stripCapsLock(keyval)) {
    case 'f':
      clickFLetter();
      break;
    case 'g':
      clickGLetter();
      break;
    default:
      break;
    }
  }

  const bool tamArrows =
      labelText || tam.mode == TM_FLAGW || tam.mode == TM_FLAGR;
  if ((CTRL_State != 65536 || allowAltGrKey) &&
      (calcMode == CM_NIM || calcMode == CM_PEM) &&
      !getSystemFlag(FLAG_ALPHA) && calcMode == CM_NIM && lastIntegerBase > 0 &&
      nimNumberPart != NP_INT_BASE) { // hex digits
    if (keyval >= 'A' && keyval <= 'F') {
      addItemAndRefresh((int16_t)(ITM_A + (keyval - 'A')));
      return;
    }
    if (keyval >= 'a' && keyval <= 'f') {
      addItemAndRefresh((int16_t)(ITM_A + (keyval - 'a')));
      return;
    }
    if (keyval == GDK_KEY_numbersign) {
      addItemAndRefresh(ITM_HASH_JM);
      return;
    }
  } else if ((CTRL_State != 65536 || allowAltGrKey) &&
             (!catalog || currentMenu() == -MNU_MVAR) &&
             (!(tamArrows || tam.mode == TM_STORCL || tam.mode == TM_MENU) ||
              (uint8_t)keyval == GDK_KEY_apostrophe) &&
             (calcMode == CM_NORMAL ||
              (calcMode == CM_NIM && nimNumberPart != NP_INT_BASE) ||
              calcMode == CM_PEM || calcMode == CM_TIMER ||
              (calcMode == CM_MIM && (stripCapsLock(keyval) == 'm' ||
                                      stripCapsLock(keyval) == 'r')) ||
              (calcMode == CM_ASSIGN && itemToBeAssigned == 0)) &&
             !getSystemFlag(FLAG_ALPHA)) {
    event_key_command = stripCapsLock(keyval);
    if ((event_keyval == GDK_KEY_backslash || event_keyval == 'z') &&
        SHOWMODE) {
      pcKeyClicked("35"); // R/S
      return;
    }
    if (RUN_ROWS(r47PcCommandRows, event_key_command)) {
      return;
    }
  } else if ((CTRL_State != 65536 || allowAltGrKey) &&
             (calcMode == CM_NORMAL || calcMode == CM_PEM) &&
             !getSystemFlag(FLAG_ALPHA)) {
    if (tam.mode == TM_STORCL) {
      if (RUN_ROWS(r47PcStoRclRows, keyval)) {
        return;
      }
      if (keyval >= 'A' && keyval <= 'Z') {
        addItemAndRefresh(storclLetterRegisters[keyval - 'A']);
        return;
      }
      if (keyval >= 'a' && keyval <= 'z') {
        addItemAndRefresh(storclLetterRegisters[keyval - 'a']);
        return;
      }
    } else if (tamArrows && !getSystemFlag(FLAG_ALPHA)) {
      if (RUN_ROWS(r47PcTamArrowRows, keyval)) {
        return;
      }
    }
  }

  if ((CTRL_State != 65536 || allowAltGrKey) && !catalog &&
      (calcMode == CM_NORMAL || calcMode == CM_MIM || calcMode == CM_EIM) &&
      IS_SIM_ARROW_ALLOWED_IN_MENU(currentMenu(), event_keyval)) {
    if (RUN_ROWS(r47PcMenuArrowRows, keyval)) {
      return;
    }
  }

  if ((CTRL_State != 65536 || allowAltGrKey) &&
      ((catalog && currentMenu() != -MNU_MVAR) || calcMode == CM_AIM ||
       calcMode == CM_EIM ||
       (calcMode == CM_PEM && getSystemFlag(FLAG_ALPHA)) ||
       (calcMode == CM_ASSIGN && getSystemFlag(FLAG_ALPHA)) ||
       (calcMode == CM_NORMAL &&
        (tam.mode == TM_REGISTER || tam.mode == TM_FLAGW ||
         tam.mode == TM_FLAGR)) ||
       labelText)) {
    if (typedCharacter(keyval)) {
      return;
    }
  }

continueWithOldDetections:
  if (CTRL_State != 0 &&
      (keyRepeat || (pcKeyBusy && programRunStop != PGM_PAUSED))) {
    return; // a Ctrl command runs once a press, and during a busy pause only
  }
  if (event_keyval == GDK_KEY_colon && tam.mode != 0) {
    addItemAndRefresh(ITM_COLON);
    return;
  }
  runCtrlCommand();
  if (calcMode == CM_AIM || calcMode == CM_EIM || tam.mode ||
      (calcMode == CM_PEM && getSystemFlag(FLAG_ALPHA)) || tam.alpha) {
    textEntryKey();
  } else {
    calculatorKey();
  }
}

// The calculator thread, from hal/keys.c: pcKeyHandle. busy is true when a
// computation popped the key, and then nothing here runs core code.
void r47_pc_key_handle(uint32_t keyval, uint32_t state, bool press, bool busy) {
  R47_CALCULATOR_THREAD_ONLY();
  if (keyval & 0x01000000) { // a Unicode keysym: GTK's map holds the legacy one
    const uint32_t unicode = keyval & 0x00ffffff;
    for (int i = 0; r47PcKeysyms[i].item != 0; i++) {
      if (r47PcKeysyms[i].unicode == unicode) {
        keyval = r47PcKeysyms[i].keysym;
        break;
      }
    }
  }
  pcKeyBusy = busy;
  if (press) {
    keyPressedNow(keyval, state);
  } else {
    keyReleasedNow(keyval);
  }
  pcKeyBusy = false;
}
