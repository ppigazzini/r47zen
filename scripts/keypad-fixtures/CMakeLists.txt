cmake_minimum_required(VERSION 3.22.1)
project(r47-keypad-fixture-exporter C)

set(CMAKE_C_STANDARD 99)
set(CMAKE_C_STANDARD_REQUIRED ON)

get_filename_component(PROJECT_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../.." ABSOLUTE)
set(R47_TRACKED_CPP_DIR "${PROJECT_ROOT}/android/app/src/main/cpp")

if("${R47_STAGED_CPP_DIR}" STREQUAL "")
    set(R47_STAGED_CPP_DIR "${PROJECT_ROOT}/android/.staged-native/cpp")
endif()

set(R47_STAGED_C47_DIR "${R47_STAGED_CPP_DIR}/c47")
set(R47_STAGED_DECNUMBER_DIR "${R47_STAGED_CPP_DIR}/decNumberICU")
set(R47_STAGED_GENERATED_DIR "${R47_STAGED_CPP_DIR}/generated")
set(R47_STAGED_GMP_DIR "${R47_STAGED_CPP_DIR}/gmp")

find_package(JNI REQUIRED)
find_package(Threads REQUIRED)

add_definitions(
    -DANDROID_BUILD
    -DHOST_TOOL_BUILD
    -DPC_BUILD
    -DLINUX
    -DOS64BIT
    -DCALCMODEL=USER_R47
)

add_definitions(-Dmpz_div_2exp=mpz_tdiv_q_2exp)
add_definitions(-Dmpz_fits_uint_p=mpz_fits_ulong_p)

include_directories(
    ${JNI_INCLUDE_DIRS}
    ${R47_TRACKED_CPP_DIR}/r47_android/stubs
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
    ${R47_TRACKED_CPP_DIR}/r47_android
    ${R47_STAGED_GMP_DIR}
)

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

set(ANDROID_BRIDGE_SOURCES
    ${R47_TRACKED_CPP_DIR}/r47_android/android_runtime.c
    ${R47_TRACKED_CPP_DIR}/r47_android/android_helpers.c
    ${R47_TRACKED_CPP_DIR}/r47_android/clipboard_utils.c
    ${R47_TRACKED_CPP_DIR}/r47_android/hal/audio.c
    ${R47_TRACKED_CPP_DIR}/r47_android/hal/gui.c
    ${R47_TRACKED_CPP_DIR}/r47_android/hal/io.c
    ${R47_TRACKED_CPP_DIR}/r47_android/hal/lcd.c
    ${R47_TRACKED_CPP_DIR}/r47_android/hal/print_ir.c
    ${R47_TRACKED_CPP_DIR}/r47_android/jni_activity_bridge.c
    ${R47_TRACKED_CPP_DIR}/r47_android/jni_display.c
    ${R47_TRACKED_CPP_DIR}/r47_android/jni_input.c
    ${R47_TRACKED_CPP_DIR}/r47_android/jni_lifecycle.c
    ${R47_TRACKED_CPP_DIR}/r47_android/jni_registration.c
    ${R47_TRACKED_CPP_DIR}/r47_android/jni_storage.c
)

add_executable(r47-keypad-fixture-exporter
    ${CMAKE_CURRENT_SOURCE_DIR}/keypad_fixture_exporter.c
    ${R47_STAGED_C47_SOURCES}
    ${DEC_SOURCES}
    ${R47_STAGED_GENERATED_SOURCES}
    ${ANDROID_BRIDGE_SOURCES}
    ${R47_STAGED_GMP_SOURCES}
)

target_compile_options(r47-keypad-fixture-exporter PRIVATE
    "-include" "${R47_TRACKED_CPP_DIR}/r47_android/android_mocks.h"
)

target_link_libraries(r47-keypad-fixture-exporter PRIVATE
    Threads::Threads
    m
)
