cmake_minimum_required(VERSION 3.22.1)
project(nokoxqx C)

add_library(nokoxqx SHARED
        noko_xqx.c
        noko_jni.c
        jbig.c
        jbig_ar.c)

target_compile_definitions(nokoxqx PRIVATE _POSIX_C_SOURCE=200112L)
target_compile_options(nokoxqx PRIVATE -O2 -Wall -Wno-unused-function -Wno-unused-variable)

find_library(log-lib log)
target_link_libraries(nokoxqx ${log-lib})
