cmake_minimum_required(VERSION 3.22.1)
project("c47-android")

set(R47_TRACKED_CPP_DIR "${CMAKE_CURRENT_SOURCE_DIR}")

if("${R47_STAGED_CPP_DIR}" STREQUAL "")
    get_filename_component(R47_STAGED_CPP_DIR "${R47_TRACKED_CPP_DIR}/../../../../.staged-native/cpp" ABSOLUTE)
endif()

set(R47_STAGED_C47_DIR "${R47_STAGED_CPP_DIR}/c47")
set(R47_STAGED_DECNUMBER_DIR "${R47_STAGED_CPP_DIR}/decNumberICU")
set(R47_STAGED_GENERATED_DIR "${R47_STAGED_CPP_DIR}/generated")
set(R47_STAGED_GMP_DIR "${R47_STAGED_CPP_DIR}/gmp")

# Android Build Definitions
add_definitions(
    -DANDROID_BUILD
    -DPC_BUILD          # Reusing PC logic where applicable (sim)
    -DLINUX             # closest platform match
    -DOS64BIT           # Android is 64-bit
    -DCALCMODEL=USER_R47
)

# Global GMP renames
add_definitions(-Dmpz_div_2exp=mpz_tdiv_q_2exp)
add_definitions(-Dmpz_fits_uint_p=mpz_fits_ulong_p)

# Forced include of mocks to ensure they are seen everywhere
add_compile_options("-include" "${CMAKE_CURRENT_SOURCE_DIR}/c47-android/android_mocks.h")

# Android stub headers satisfy upstream GTK/GDK/Cairo includes without
# rewriting the staged native tree during scripts/build_android.sh.
# Include directories
include_directories(
    c47-android/stubs
    ${R47_STAGED_C47_DIR}
    ${R47_STAGED_C47_DIR}/core
    ${R47_STAGED_C47_DIR}/hal
    ${R47_STAGED_C47_DIR}/ui
    ${R47_STAGED_C47_DIR}/logicalOps
    ${R47_STAGED_C47_DIR}/mathematics
    ${R47_STAGED_C47_DIR}/programming
    ${R47_STAGED_C47_DIR}/solver
    ${R47_STAGED_C47_DIR}/browsers
    ${R47_STAGED_C47_DIR}/distributions
    ${R47_STAGED_C47_DIR}/c47Extensions
    ${R47_STAGED_DECNUMBER_DIR}
    ${R47_STAGED_GENERATED_DIR}
    c47-android
    ${R47_STAGED_GMP_DIR}
)

set(R47_STAGED_METADATA_SCRIPT
    "${R47_TRACKED_CPP_DIR}/../../../../../scripts/android/generate_staged_native_metadata.sh"
)

execute_process(
    COMMAND bash "${R47_STAGED_METADATA_SCRIPT}" --cpp-dir "${R47_STAGED_CPP_DIR}"
    RESULT_VARIABLE R47_STAGED_METADATA_RESULT
    ERROR_VARIABLE R47_STAGED_METADATA_ERROR
)

if(NOT R47_STAGED_METADATA_RESULT EQUAL 0)
    message(FATAL_ERROR "Failed to generate staged native metadata: ${R47_STAGED_METADATA_ERROR}")
endif()

include("${R47_STAGED_CPP_DIR}/staged_native_sources.cmake")

set(DEC_SOURCES
    ${R47_STAGED_DECNUMBER_DIR}/decContext.c
    ${R47_STAGED_DECNUMBER_DIR}/decDouble.c
    ${R47_STAGED_DECNUMBER_DIR}/decNumber.c
    ${R47_STAGED_DECNUMBER_DIR}/decPacked.c
    ${R47_STAGED_DECNUMBER_DIR}/decQuad.c
    ${R47_STAGED_DECNUMBER_DIR}/decimal128.c
    ${R47_STAGED_DECNUMBER_DIR}/decimal32.c
    ${R47_STAGED_DECNUMBER_DIR}/decimal64.c
)

set(ANDROID_SOURCES
    ${CMAKE_CURRENT_SOURCE_DIR}/c47-android/android_runtime.c
    ${CMAKE_CURRENT_SOURCE_DIR}/c47-android/android_helpers.c
    ${CMAKE_CURRENT_SOURCE_DIR}/c47-android/clipboard_utils.c
    ${CMAKE_CURRENT_SOURCE_DIR}/c47-android/hal/audio.c
    ${CMAKE_CURRENT_SOURCE_DIR}/c47-android/hal/gui.c
    ${CMAKE_CURRENT_SOURCE_DIR}/c47-android/hal/io.c
    ${CMAKE_CURRENT_SOURCE_DIR}/c47-android/hal/lcd.c
    ${CMAKE_CURRENT_SOURCE_DIR}/c47-android/hal/print_ir.c
    ${CMAKE_CURRENT_SOURCE_DIR}/c47-android/jni_activity_bridge.c
    ${CMAKE_CURRENT_SOURCE_DIR}/c47-android/jni_display.c
    ${CMAKE_CURRENT_SOURCE_DIR}/c47-android/jni_input.c
    ${CMAKE_CURRENT_SOURCE_DIR}/c47-android/jni_lifecycle.c
    ${CMAKE_CURRENT_SOURCE_DIR}/c47-android/jni_registration.c
    ${CMAKE_CURRENT_SOURCE_DIR}/c47-android/jni_storage.c
)

# Shared library
add_library(c47-android SHARED
    ${R47_STAGED_C47_SOURCES}
    ${DEC_SOURCES}
    ${R47_STAGED_GENERATED_SOURCES}
    ${ANDROID_SOURCES}
    ${R47_STAGED_GMP_SOURCES}
)

# Link libraries
find_library(log-lib log)
find_library(m-lib m)

target_link_libraries(c47-android
    ${log-lib}
    ${m-lib}
)

# The Android module enables 16 KB support through the NDK toolchain argument
# in app/build.gradle rather than a project-local raw linker flag.
