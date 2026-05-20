#include "c47.h"
#include "screen.h"
#include <inttypes.h>
#include <string.h>
#include <stdio.h>
#include <android/log.h>
#include "display.h"
#include "fonts.h"

#ifndef LOG_TAG
#define LOG_TAG "R47Helpers"
#endif
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

#define ANDROID_CLIPSTR 30000

static void ascii_clean(char *str, size_t str_size) {
    if (!str || !*str) return;
    char tmp[1024];
    char *s = str;
    char *d = tmp;
    while (*s && (d - tmp) < 1023) {
        uint8_t c1 = (uint8_t)s[0];
        uint8_t c2 = (uint8_t)s[1];

        if (c1 == 0xA1 && c2 == 0x48) { // STD_op_i
            *d++ = ' '; *d++ = 'i'; *d++ = ' '; s += 2;
        } else if (c1 == 0xA1 && c2 == 0x49) { // STD_op_j
            *d++ = ' '; *d++ = 'j'; *d++ = ' '; s += 2;
        } else if (c1 == 0x80 && c2 == 0xB7) { // STD_DOT (·)
            *d++ = ' '; s += 2;
        } else if (c1 == 0x80 && c2 == 0xD7) { // STD_CROSS (×)
            *d++ = '*'; s += 2;
        } else if (c1 == 0xA0 && c2 >= 0x80 && c2 <= 0x89) { // STD_SUB_0..9
            *d++ = (c2 - 0x80) + '0'; s += 2;
        } else if (c1 == 0xA0 && c2 == 0x8A) { // STD_SUB_PLUS
            *d++ = '+'; s += 2;
        } else if (c1 == 0xA0 && c2 == 0x8B) { // STD_SUB_MINUS
            *d++ = '-'; s += 2;
        } else if (c1 == 0xA1 && c2 >= 0x60 && c2 <= 0x69) { // STD_SUP_0..9
            *d++ = (c2 - 0x60) + '0'; s += 2;
        } else if (c1 == 0xA1 && c2 == 0x6B) { // STD_SUP_MINUS
            *d++ = '-'; s += 2;
        } else if (c1 == 0xA1 && c2 == 0x6A) { // STD_SUP_PLUS
            *d++ = '+'; s += 2;
        } else if ((c1 == 0xA4 && c2 == 0x69) || (c1 == 0xA4 && c2 == 0x7D)) { // STD_BASE_10 or STD_SUB_10 (x10)
            *d++ = 'e'; s += 2;
        } else if (c1 == 0x82 && c2 == 0xB3) { // STD_SUP_BOLD_r
            *d++ = 'r'; s += 2;
        } else if (c1 == 0x9D && c2 == 0x4D) { // STD_SUP_BOLD_g
            *d++ = 'g'; s += 2;
        } else if (c1 == 0xA0 && c2 == 0x0A) { // STD_SPACE_HAIR
            *d++ = ' '; s += 2;
        } else {
            *d++ = *s++;
        }
    }
    *d = '\0';
    snprintf(str, str_size, "%s", tmp);
}

static void trimTrailingRadix(char *str) {
    if (!str || !*str) return;
    size_t len = strlen(str);
    while (len > 0 && (str[len-1] == ' ' || str[len-1] == '.' || str[len-1] == ',')) {
        str[--len] = '\0';
    }
}

static void angularUnitToClipboardString(angularMode_t angularMode, char *string) {
    switch (angularMode) {
        case amRadian:
            strcpy(string, "r");
            break;
        case amMultPi:
            strcpy(string, STD_pi);
            break;
        case amGrad:
            strcpy(string, "g");
            break;
        case amDegree:
            strcpy(string, STD_DEGREE);
            break;
        case amDMS:
            strcpy(string, "d.ms");
            break;
        case amNone:
            string[0] = '\0';
            break;
        default:
            strcpy(string, "?");
            break;
    }
}

static void copyRegisterToClipboardUtf8String(calcRegister_t regist, char *clipboardString) {
    longInteger_t lgInt;
    int16_t base;
    int16_t sign;
    int16_t n;
    uint64_t shortInt;
    char string[ANDROID_CLIPSTR];

    switch (getRegisterDataType(regist)) {
        case dtLongInteger:
            convertLongIntegerRegisterToLongInteger(regist, lgInt);
            longIntegerToAllocatedString(lgInt, string, ANDROID_CLIPSTR);
            longIntegerFree(lgInt);
            break;

        case dtTime:
            timeToDisplayString(regist, string, false);
            break;

        case dtDate:
            dateToDisplayString(regist, string);
            break;

        case dtString:
            COPY_REGISTER_STRING_TO(string, regist);
            break;

        case dtReal34Matrix: {
            matrixHeader_t *matrixHeader = REGISTER_MATRIX_HEADER(regist);
            real34_t *real34 = REGISTER_REAL34_MATRIX_ELEMENTS(regist);
            real34_t reduced;
            uint32_t rows = matrixHeader->matrixRows;
            uint32_t columns = matrixHeader->matrixColumns;

            sprintf(string, "%" PRIu32 "x%" PRIu32, rows, columns);

            for (uint32_t i = 0; i < rows * columns; i++) {
                uint32_t len;

                strcat(string, LINEBREAK);
                len = (uint32_t)strlen(string);
                real34Reduce(real34++, &reduced);
                real34ToString(&reduced, string + len);

                if (strchr(string + len, '.') == NULL && strchr(string + len, 'E') == NULL) {
                    strcat(string + len, ".");
                }
            }
            break;
        }

        case dtComplex34Matrix: {
            matrixHeader_t *matrixHeader = REGISTER_MATRIX_HEADER(regist);
            complex34_t *complex34 = REGISTER_COMPLEX34_MATRIX_ELEMENTS(regist);
            real34_t reduced;
            uint32_t rows = matrixHeader->matrixRows;
            uint32_t columns = matrixHeader->matrixColumns;

            sprintf(string, "%" PRIu32 "x%" PRIu32, rows, columns);

            for (uint32_t i = 0; i < rows * columns; i++, complex34++) {
                uint32_t len;

                strcat(string, LINEBREAK);
                len = (uint32_t)strlen(string);

                real34Reduce((real34_t *)complex34, &reduced);
                real34ToString(&reduced, string + len);
                if (strchr(string + len, '.') == NULL && strchr(string + len, 'E') == NULL) {
                    strcat(string + len, ".");
                }
                len = (uint32_t)strlen(string);

                real34Reduce(((real34_t *)complex34) + 1, &reduced);
                if (real34IsNegative(&reduced)) {
                    sprintf(string + len, " - %sx", COMPLEX_UNIT);
                    len += 5;
                    real34SetPositiveSign(&reduced);
                    real34ToString(&reduced, string + len);
                } else {
                    sprintf(string + len, " + %sx", COMPLEX_UNIT);
                    len += 5;
                    real34ToString(&reduced, string + len);
                }
                if (strchr(string + len, '.') == NULL && strchr(string + len, 'E') == NULL) {
                    strcat(string + len, ".");
                }
            }
            break;
        }

        case dtShortInteger:
            convertShortIntegerRegisterToUInt64(regist, &sign, &shortInt);
            base = getRegisterShortIntegerBase(regist);

            n = ERROR_MESSAGE_LENGTH - 100;
            sprintf(errorMessage + n--, "#%d (word size = %u)", base, shortIntegerWordSize);

            if (shortInt == 0) {
                errorMessage[n--] = '0';
            } else {
                while (shortInt != 0) {
                    errorMessage[n--] = baseDigits[shortInt % base];
                    shortInt /= base;
                }
                if (sign) {
                    errorMessage[n--] = '-';
                }
            }
            n++;

            strcpy(string, errorMessage + n);
            break;

        case dtReal34: {
            real34_t reduced;

            real34Reduce(REGISTER_REAL34_DATA(regist), &reduced);
            real34ToString(&reduced, string);
            if (strchr(string, '.') == NULL && strchr(string, 'E') == NULL) {
                strcat(string, ".");
            }
            angularUnitToClipboardString(getRegisterAngularMode(regist), string + strlen(string));
            break;
        }

        case dtComplex34: {
            real34_t reduced;
            int len;
            char tmpStr[100];

            real34Reduce(REGISTER_REAL34_DATA(regist), &reduced);
            real34ToString(&reduced, tmpStr);
            if (strchr(tmpStr, '.') == NULL && strchr(tmpStr, 'E') == NULL) {
                strcat(tmpStr, ".");
            }
            len = (int)strlen(tmpStr);

            real34Reduce(REGISTER_IMAG34_DATA(regist), &reduced);
            if (real34IsNegative(&reduced)) {
                sprintf(string, "%s - %sx", tmpStr, COMPLEX_UNIT);
                len += 5;
                real34SetPositiveSign(&reduced);
                real34ToString(&reduced, string + len);
            } else {
                sprintf(string, "%s + %sx", tmpStr, COMPLEX_UNIT);
                len += 5;
                real34ToString(&reduced, string + len);
            }
            if (strchr(string + len, '.') == NULL && strchr(string + len, 'E') == NULL) {
                strcat(string + len, ".");
            }
            break;
        }

        case dtConfig:
            xcopy(string, "Configuration data", 19);
            break;

        default:
            sprintf(
                string,
                "In function copyRegisterXToClipboard, the data type %" PRIu32 " is unknown! Please try to reproduce and submit a bug.",
                getRegisterDataType(regist));
            break;
    }

    stringToUtf8(string, (uint8_t *)clipboardString);
}

static void copyStackRegistersToClipboardUtf8String(
    char *clipboardString,
    calcRegister_t lastRegist
) {
    char *ptr = clipboardString;
    const char *sep = "";

    for (calcRegister_t regist = lastRegist; regist >= REGISTER_X; regist--) {
        ptr += sprintf(ptr, "%s%c = ", sep, letteredRegisterName(regist));
        copyRegisterToClipboardUtf8String(regist, ptr);
        ptr = strchr(ptr, '\0');
        sep = LINEBREAK;
    }
}

char* getXRegisterString() {
    static char result[2048];
    char coreBuf[1024];
    coreBuf[0] = '\0';

    if (!ram) return "0";

    calcRegister_t regist = REGISTER_X;
    uint8_t dataType = getRegisterDataType(regist);
    uint8_t tag = getRegisterTag(regist);

    switch(dataType) {
        case dtReal34:
            real34ToDisplayString((const real34_t *)getRegisterDataPointer(regist), tag, coreBuf, &standardFont, 9999, 34, false, false, NOIRFRAC);
            break;

        case dtComplex34:
            complex34ToDisplayString((const complex34_t *)getRegisterDataPointer(regist), coreBuf, &standardFont, 9999, 34, false, false, NOIRFRAC, tag & amAngleMask, (tag & amPolar) != 0);
            break;

        case dtString:
            strncpy(coreBuf, REGISTER_STRING_DATA(regist), 1023);
            coreBuf[1023] = '\0';
            break;

        case dtShortInteger:
            shortIntegerToDisplayString(regist, coreBuf, false, 0);
            break;

        case dtLongInteger:
            longIntegerRegisterToDisplayString(regist, coreBuf, sizeof(coreBuf), 9999, 50, true);
            break;

        default:
            snprintf(coreBuf, sizeof(coreBuf), "[%s]", getDataTypeName(dataType, false, false));
            break;
    }

    ascii_clean(coreBuf, sizeof(coreBuf));
    trimTrailingRadix(coreBuf);

    stringToUtf8(coreBuf, (uint8_t*)result);

    LOGI("getXRegisterString: Result='%s'", result);
    return result;
}

char* getClipboardXRegisterString() {
    static char result[ANDROID_CLIPSTR];

    result[0] = '\0';
    if (!ram) {
        return result;
    }

    copyRegisterToClipboardUtf8String(REGISTER_X, result);
    return result;
}

char* getClipboardStackRegistersString() {
    static char result[ANDROID_CLIPSTR];

    result[0] = '\0';
    if (!ram) {
        return result;
    }

    copyStackRegistersToClipboardUtf8String(result, REGISTER_K);
    return result;
}

char* getClipboardAllRegistersString() {
    static char result[ANDROID_CLIPSTR];
    char *ptr = result;

    result[0] = '\0';
    if (!ram) {
        return result;
    }

    copyStackRegistersToClipboardUtf8String(ptr, LAST_SPARE_REGISTER);

    for (int32_t regist = 99; regist >= 0; --regist) {
        ptr += strlen(ptr);
        sprintf(ptr, LINEBREAK "R%02d = ", regist);
        ptr += strlen(ptr);
        copyRegisterToClipboardUtf8String(regist, ptr);
    }

    for (int32_t regist = currentNumberOfLocalRegisters - 1; regist >= 0; --regist) {
        ptr += strlen(ptr);
        sprintf(ptr, LINEBREAK "R.%02d = ", regist);
        ptr += strlen(ptr);
        copyRegisterToClipboardUtf8String(FIRST_LOCAL_REGISTER + regist, ptr);
    }

    if (statisticalSumsPointer != NULL) {
        const char * const statSumNames[NUMBER_OF_STATISTICAL_SUMS] = {
            /* 0*/ "n             ",
            /* 1*/ STD_SIGMA "(x)          ",
            /* 2*/ STD_SIGMA "(y)          ",
            /* 3*/ STD_SIGMA "(x" STD_SUP_2 ")         ",
            /* 4*/ STD_SIGMA "(x" STD_SUP_2 "y)        ",
            /* 5*/ STD_SIGMA "(y" STD_SUP_2 ")         ",
            /* 6*/ STD_SIGMA "(xy)         ",
            /* 7*/ STD_SIGMA "(ln(x)" STD_CROSS "ln(y))",
            /* 8*/ STD_SIGMA "(ln(x))      ",
            /* 9*/ STD_SIGMA "(ln" STD_SUP_2 "(x))     ",
            /*10*/ STD_SIGMA "(y ln(x))    ",
            /*11*/ STD_SIGMA "(ln(y))      ",
            /*12*/ STD_SIGMA "(ln" STD_SUP_2 "(y))     ",
            /*13*/ STD_SIGMA "(x ln(y))    ",
            /*14*/ STD_SIGMA "(ln(y)/x)    ",
            /*15*/ STD_SIGMA "(x" STD_SUP_2 "/y)       ",
            /*16*/ STD_SIGMA "(1/x)        ",
            /*17*/ STD_SIGMA "(1/x" STD_SUP_2 ")       ",
            /*18*/ STD_SIGMA "(x/y)        ",
            /*19*/ STD_SIGMA "(1/y)        ",
            /*20*/ STD_SIGMA "(1/y" STD_SUP_2 ")       ",
            /*21*/ STD_SIGMA "(x" STD_SUP_3 ")         ",
            /*22*/ STD_SIGMA "(x" STD_SUP_4 ")         ",
            /*23*/ "x min         ",
            /*24*/ "x max         ",
            /*25*/ "y min         ",
            /*26*/ "y max         "
        };
        char sumName[40];
        char statValue[256];

        for (int32_t sum = 0; sum < NUMBER_OF_STATISTICAL_SUMS; sum++) {
            ptr += strlen(ptr);
            strcpy(sumName, statSumNames[sum]);

            sprintf(ptr, LINEBREAK "SR%02d = ", sum);
            ptr += strlen(ptr);
            stringToUtf8(sumName, (uint8_t *)ptr);
            ptr += strlen(ptr);
            strcpy(ptr, " = ");
            ptr += strlen(ptr);
            realToString(statisticalSumsPointer + sum, statValue);
            if (strchr(statValue, '.') == NULL && strchr(statValue, 'E') == NULL) {
                strcat(statValue, ".");
            }
            strcpy(ptr, statValue);
        }
    }

    return result;
}
