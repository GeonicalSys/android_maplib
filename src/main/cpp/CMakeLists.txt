cmake_minimum_required(VERSION 3.22.1)
project(lisa_project_scripts C)
add_library(lisa_project_scripts SHARED
    script_engine.c quickjs/quickjs.c quickjs/dtoa.c quickjs/libregexp.c
    quickjs/libunicode.c quickjs/cutils.c)
target_include_directories(lisa_project_scripts PRIVATE quickjs)
target_compile_definitions(lisa_project_scripts PRIVATE _GNU_SOURCE CONFIG_VERSION="2026-06-04")
target_compile_options(lisa_project_scripts PRIVATE -fwrapv -fvisibility=hidden)
target_link_options(lisa_project_scripts PRIVATE "-Wl,-z,max-page-size=16384")
target_link_libraries(lisa_project_scripts m)
