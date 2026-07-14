cmake_minimum_required(VERSION 3.22.1)
project(hdrstacker LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# ---------------------------------------------------------------------------
# LibRaw — decodes Nikon NEF (and every other supported RAW) into RGB.
#
# LibRaw itself ships with autotools/Makefiles, so we overlay the official
# LibRaw-cmake project (which provides a CMakeLists + find modules) on top of
# the LibRaw source tree, then add it as a sub-project. Both are fetched at
# configure time, so the first native build needs network access. To build
# fully offline, vendor LibRaw under app/src/main/cpp/third_party/LibRaw and
# set HDR_LIBRAW_LOCAL (see README).
# ---------------------------------------------------------------------------
include(FetchContent)

set(LIBRAW_VERSION "0.21.4")

FetchContent_Declare(
        libraw_src
        URL "https://www.libraw.org/data/LibRaw-${LIBRAW_VERSION}.tar.gz"
        DOWNLOAD_EXTRACT_TIMESTAMP TRUE
)
FetchContent_Declare(
        libraw_cmake
        GIT_REPOSITORY "https://github.com/LibRaw/LibRaw-cmake.git"
        GIT_TAG "master"
)

FetchContent_GetProperties(libraw_src)
if (NOT libraw_src_POPULATED)
    FetchContent_Populate(libraw_src)
endif ()
FetchContent_GetProperties(libraw_cmake)
if (NOT libraw_cmake_POPULATED)
    FetchContent_Populate(libraw_cmake)
endif ()

# Overlay the CMake build files onto the LibRaw source tree.
file(COPY
        "${libraw_cmake_SOURCE_DIR}/CMakeLists.txt"
        "${libraw_cmake_SOURCE_DIR}/cmake"
        DESTINATION "${libraw_src_SOURCE_DIR}")

# Trim LibRaw to the bits we need — no demosaic-pack (GPL), no examples,
# no optional codecs we don't ship. RawSpeed/jasper/lcms stay off for a
# lean, license-clean library. Bayer + Nikon decoders remain built in.
set(ENABLE_OPENMP OFF CACHE BOOL "" FORCE)
set(ENABLE_LCMS OFF CACHE BOOL "" FORCE)
set(ENABLE_EXAMPLES OFF CACHE BOOL "" FORCE)
set(ENABLE_RAWSPEED OFF CACHE BOOL "" FORCE)
set(ENABLE_DNGSDK OFF CACHE BOOL "" FORCE)
set(ENABLE_JASPER OFF CACHE BOOL "" FORCE)
set(ENABLE_JPEG OFF CACHE BOOL "" FORCE)
set(LIBRAW_INSTALL OFF CACHE BOOL "" FORCE)

add_subdirectory("${libraw_src_SOURCE_DIR}" "${libraw_src_BINARY_DIR}")

# ---------------------------------------------------------------------------
# Our JNI bridge.
# ---------------------------------------------------------------------------
add_library(hdrstacker SHARED native-hdr.cpp)

target_include_directories(hdrstacker PRIVATE "${libraw_src_SOURCE_DIR}")

find_library(log-lib log)

# `raw` is LibRaw's non-thread-safe target; `raw_r` is the reentrant one.
# We only ever decode from one worker thread at a time, so `raw` is fine.
target_link_libraries(hdrstacker
        raw
        ${log-lib})
