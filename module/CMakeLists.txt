cmake_minimum_required(VERSION 3.22)
project(routtrans_zygisk CXX)

# Nama target sengaja dibuat "arm64-v8a" karena zip modul Zygisk memakai
# nama ABI sebagai nama berkas (zygisk/arm64-v8a.so).
add_library(arm64-v8a SHARED src/main/cpp/zygisk.cpp)

set_target_properties(arm64-v8a PROPERTIES
        CXX_STANDARD 20
        CXX_STANDARD_REQUIRED ON
        PREFIX ""
        OUTPUT_NAME "arm64-v8a")

target_compile_options(arm64-v8a PRIVATE -Wall -Wextra -fvisibility=hidden)
target_link_libraries(arm64-v8a log)
