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
