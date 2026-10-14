cmake_minimum_required(VERSION 3.22.1)
project("apexcontainer" C)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)

find_library(log-lib log)
find_library(dl-lib dl)

# 1. Virtual Root LD_PRELOAD hook library: libvroot.so
add_library(vroot SHARED vroot_hook.c)
target_compile_options(vroot PRIVATE -Wall -Wextra -fPIC -O2)
target_link_libraries(vroot ${log-lib} ${dl-lib})

# 2. Custom su client binary disguised as a shared library (libsu_bin.so)
# Built as a Position Independent Executable (-fPIE -pie) and emitted into
# CMAKE_LIBRARY_OUTPUT_DIRECTORY + jniLibs so Android 10+ W^X extracts it
# into nativeLibraryDir with r-x permissions.
add_executable(su_bin su_client.c)
target_compile_options(su_bin PRIVATE -Wall -Wextra -fPIE -O2)
target_link_options(su_bin PRIVATE -pie)
target_link_libraries(su_bin ${log-lib})

set_target_properties(su_bin PROPERTIES
    PREFIX "lib"
    OUTPUT_NAME "su_bin"
    SUFFIX ".so"
    RUNTIME_OUTPUT_DIRECTORY "${CMAKE_LIBRARY_OUTPUT_DIRECTORY}"
)

# Guarantee packaging across all AGP 8.x versions by syncing to jniLibs/<ABI>/
add_custom_command(TARGET su_bin POST_BUILD
    COMMAND ${CMAKE_COMMAND} -E make_directory "${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}"
    COMMAND ${CMAKE_COMMAND} -E copy_if_different "$<TARGET_FILE:su_bin>" "${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libsu_bin.so"
    COMMENT "Syncing PIE executable libsu_bin.so to jniLibs/${ANDROID_ABI}"
)
