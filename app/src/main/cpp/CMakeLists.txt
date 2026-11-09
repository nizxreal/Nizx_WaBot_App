cmake_minimum_required(VERSION 3.18.1)
project("nodeglue")

add_library(node SHARED IMPORTED)
set_target_properties(node PROPERTIES IMPORTED_LOCATION
    ${CMAKE_SOURCE_DIR}/../../../libnode/bin/${ANDROID_ABI}/libnode.so)

add_library(nodeglue SHARED native-lib.cpp)
target_link_libraries(nodeglue node log)
