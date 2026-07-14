cmake_minimum_required(VERSION 3.22.1)
project(hdrstacker LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# ---------------------------------------------------------------------------
# LibRaw — decodes Nikon NEF (and every other supported RAW) into RGB.
#
# LibRaw ships only autotools/Makefiles, and its CMake support lives in a
# separate repo. Rather than depend on that overlay (and on libraw.org being
# reachable), we fetch the LibRaw source from GitHub and compile it directly
# with a small target of our own. This is fetched at configure time, so the
# first native build needs network access. To build fully offline, vendor
# LibRaw under app/src/main/cpp/third_party/LibRaw and point the source dir
# there instead (see README).
#
# We compile every file under src/ EXCEPT the three "*_ph.cpp" placeholders,
# which LibRaw's own Makefile.dist also omits (they are include-only, not
# standalone translation units). No demosaic-pack (GPL) sources exist in the
# main repo, so the result is LGPL-clean. zlib/jpeg/lcms integrations stay
# disabled (guarded out by #ifdef), so there are no external dependencies.
# ---------------------------------------------------------------------------
include(FetchContent)

FetchContent_Declare(
        libraw
        GIT_REPOSITORY "https://github.com/LibRaw/LibRaw.git"
        GIT_TAG "0.21.4"
        GIT_SHALLOW TRUE
)
FetchContent_MakeAvailable(libraw)

file(GLOB_RECURSE LIBRAW_SOURCES CONFIGURE_DEPENDS "${libraw_SOURCE_DIR}/src/*.cpp")
list(FILTER LIBRAW_SOURCES EXCLUDE REGEX ".*/(postprocessing_ph|preprocessing_ph|write_ph)\\.cpp$")

add_library(raw STATIC ${LIBRAW_SOURCES})
target_include_directories(raw PUBLIC "${libraw_SOURCE_DIR}")
# LibRaw is not warning-clean; keep its noise out of our build log.
target_compile_options(raw PRIVATE -w)

# ---------------------------------------------------------------------------
# Our JNI bridge.
# ---------------------------------------------------------------------------
add_library(hdrstacker SHARED native-hdr.cpp)

find_library(log-lib log)

target_link_libraries(hdrstacker
        raw
        ${log-lib})

# ---------------------------------------------------------------------------
# Panorama stitching (Stage 1).
#
# The Maven OpenCV artifact ships every module EXCEPT `stitching`, but its
# prebuilt libopencv_java4.so *does* export the cv:: symbols that stitching
# depends on (core/imgproc/features2d/calib3d/flann). So we compile only the
# stitching module's own sources from the matching OpenCV source tag and link
# them against the prebuilt library exposed through the AAR's prefab package.
# This keeps the HDR path and the OpenCV dependency untouched.
#
# `find_package(OpenCV)` here resolves to the prefab-generated config (enabled
# by android.buildFeatures.prefab); the imported target is OpenCV::opencv_java4.
# ---------------------------------------------------------------------------
find_package(OpenCV REQUIRED CONFIG)

# Git clone (shallow) rather than the archive tarball: the tarball download
# fails in CI, whereas a shallow clone of the tag is reliable (same as LibRaw).
FetchContent_Declare(
        opencv_src
        GIT_REPOSITORY "https://github.com/opencv/opencv.git"
        GIT_TAG "4.11.0"
        GIT_SHALLOW TRUE
)
FetchContent_MakeAvailable(opencv_src)

# Generated headers OpenCV's own build would normally produce. Empty/minimal
# is correct for our reduced, single-module compile (no CUDA/OpenCL/IPP).
set(OCV_GEN "${CMAKE_CURRENT_BINARY_DIR}/ocv_gen")
file(MAKE_DIRECTORY "${OCV_GEN}/opencv2")
file(WRITE "${OCV_GEN}/opencv2/opencv_modules.hpp"
        "#define HAVE_OPENCV_CORE\n#define HAVE_OPENCV_IMGPROC\n#define HAVE_OPENCV_FEATURES2D\n#define HAVE_OPENCV_CALIB3D\n#define HAVE_OPENCV_FLANN\n#define HAVE_OPENCV_STITCHING\n")
file(WRITE "${OCV_GEN}/cvconfig.h" "")
file(WRITE "${OCV_GEN}/cv_cpu_config.h" "#define CV_CPU_COMPILE_BASELINE 1\n#define CV_CPU_BASELINE_MODE 1\n")
file(WRITE "${OCV_GEN}/opencl_kernels_stitching.hpp" "")

file(GLOB STITCH_SOURCES "${opencv_src_SOURCE_DIR}/modules/stitching/src/*.cpp")
list(FILTER STITCH_SOURCES EXCLUDE REGEX ".*warpers_cuda\\.cpp$")

add_library(panostitcher SHARED native-stitch.cpp ${STITCH_SOURCES})
target_compile_definitions(panostitcher PRIVATE __OPENCV_BUILD=1)
target_include_directories(panostitcher PRIVATE
        "${OCV_GEN}"
        "${opencv_src_SOURCE_DIR}/modules/stitching/include"
        "${opencv_src_SOURCE_DIR}/modules/stitching/src"
        "${opencv_src_SOURCE_DIR}/modules/core/include"
        "${opencv_src_SOURCE_DIR}/modules/imgproc/include"
        "${opencv_src_SOURCE_DIR}/modules/features2d/include"
        "${opencv_src_SOURCE_DIR}/modules/calib3d/include"
        "${opencv_src_SOURCE_DIR}/modules/flann/include")
target_compile_options(panostitcher PRIVATE -w)
target_link_libraries(panostitcher
        OpenCV::opencv_java4
        ${log-lib})
