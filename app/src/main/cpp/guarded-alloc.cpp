// guarded-alloc.cpp
//
// Guard-page debugging heap, linked ONLY into the debugging ("asan") build
// variant via -Wl,--wrap. Covers malloc/calloc/realloc/free AND C++
// operator new/delete reachable from LibRaw and the JNI bridge.
//
// Layout per allocation:
//   [PROT_NONE page][ data, ptr at page start ][canary slack][PROT_NONE page]
//
// - A write BEFORE the allocation (underrun) faults instantly on the front
//   guard — the previous end-aligned version of this allocator proved there
//   is no forward overrun, so the front guard is now the priority.
// - A write past the end lands in the canary slack (verified at free, abort
//   with details) or on the rear guard page (instant fault).
// - free() unmaps the whole region, so use-after-free faults too.
// - Pointers not from this allocator (e.g. strdup'd inside libc) forward to
//   the real allocator.

#include <android/log.h>

#include <cstdint>
#include <cstring>
#include <mutex>
#include <unordered_map>

#include <sys/mman.h>
#include <unistd.h>

extern "C" {
void *__real_malloc(size_t size);
void *__real_calloc(size_t n, size_t size);
void *__real_realloc(void *ptr, size_t size);
void __real_free(void *ptr);
}

extern "C" void __wrap_free(void *ptr);

namespace {

constexpr uint8_t kCanary = 0xA5;

struct GuardInfo {
    void *base;
    size_t mapLen;
    size_t size;    // requested
    size_t padded;  // size rounded to 16
    size_t dataLen; // padded rounded to page
};

// The registry's own nodes must not re-enter the guarded allocator (deadlock
// on gLock): a thread-local bypass makes the operator-new wrapper fall back
// to the real allocator while the registry is being mutated.
thread_local bool tlsBypass = false;
struct BypassScope {
    BypassScope() { tlsBypass = true; }
    ~BypassScope() { tlsBypass = false; }
};

std::mutex gLock;
std::unordered_map<void *, GuardInfo> gAllocs;

constexpr size_t kAlign = 16;

void *guardedAlloc(size_t size) {
    if (size == 0) size = 1;
    const size_t page = static_cast<size_t>(sysconf(_SC_PAGESIZE));
    const size_t padded = (size + kAlign - 1) & ~(kAlign - 1);
    const size_t dataLen = (padded + page - 1) & ~(page - 1);
    const size_t mapLen = page + dataLen + page;  // front guard + data + rear guard
    void *base = mmap(nullptr, mapLen, PROT_READ | PROT_WRITE,
                      MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    if (base == MAP_FAILED) return nullptr;
    if (mprotect(base, page, PROT_NONE) != 0 ||
        mprotect(static_cast<char *>(base) + page + dataLen, page, PROT_NONE) != 0) {
        munmap(base, mapLen);
        return nullptr;
    }
    void *ptr = static_cast<char *>(base) + page;
    memset(static_cast<char *>(ptr) + padded, kCanary, dataLen - padded);
    {
        std::lock_guard<std::mutex> lock(gLock);
        BypassScope bypass;
        gAllocs[ptr] = {base, mapLen, size, padded, dataLen};
    }
    return ptr;
}

void checkCanaryAndRelease(void *ptr, const GuardInfo &info) {
    const uint8_t *slack = static_cast<const uint8_t *>(ptr) + info.padded;
    const size_t slackLen = info.dataLen - info.padded;
    for (size_t i = 0; i < slackLen; ++i) {
        if (slack[i] != kCanary) {
            __android_log_print(
                ANDROID_LOG_FATAL, "GuardHeap",
                "HEAP OVERFLOW detected at free: allocation of %zu bytes was "
                "overwritten %zu bytes past its 16-aligned end",
                info.size, i + 1);
            abort();
        }
    }
    munmap(info.base, info.mapLen);
}

}  // namespace

extern "C" void *__wrap_malloc(size_t size) {
    if (tlsBypass) return __real_malloc(size);
    return guardedAlloc(size);
}

extern "C" void *__wrap_calloc(size_t n, size_t size) {
    if (size != 0 && n > SIZE_MAX / size) return nullptr;
    if (tlsBypass) return __real_calloc(n, size);
    return guardedAlloc(n * size);  // fresh mmap memory is already zeroed
}

extern "C" void __wrap_free(void *ptr) {
    if (ptr == nullptr) return;
    // Registry teardown (map node deallocation inside the locked region below)
    // re-enters this function; without the bypass short-circuit that nested
    // call deadlocks on gLock. Bypass-allocated nodes are __real_malloc'd, so
    // forwarding straight to __real_free is also the correct pairing.
    if (tlsBypass) {
        __real_free(ptr);
        return;
    }
    GuardInfo info;
    {
        std::lock_guard<std::mutex> lock(gLock);
        BypassScope bypass;
        auto it = gAllocs.find(ptr);
        if (it == gAllocs.end()) {
            __real_free(ptr);  // not ours (allocated inside libc): forward
            return;
        }
        info = it->second;
        gAllocs.erase(it);
    }
    checkCanaryAndRelease(ptr, info);
}

extern "C" void *__wrap_realloc(void *ptr, size_t size) {
    if (ptr == nullptr) return guardedAlloc(size);
    if (size == 0) {
        __wrap_free(ptr);
        return nullptr;
    }
    size_t oldSize;
    {
        std::lock_guard<std::mutex> lock(gLock);
        BypassScope bypass;
        auto it = gAllocs.find(ptr);
        if (it == gAllocs.end()) return __real_realloc(ptr, size);
        oldSize = it->second.size;
    }
    void *fresh = guardedAlloc(size);
    if (fresh == nullptr) return nullptr;
    memcpy(fresh, ptr, oldSize < size ? oldSize : size);
    __wrap_free(ptr);
    return fresh;
}

// ---------------------------------------------------------------------------
// C++ operator new/delete, wrapped by mangled name (LP64: _Znwm/_Znam;
// ILP32: _Znwj/_Znaj) so LibRaw's std containers are guarded too.
// ---------------------------------------------------------------------------

namespace {

void *opNew(size_t n) {
    if (tlsBypass) {
        void *p = __real_malloc(n != 0 ? n : 1);
        if (p == nullptr) abort();
        return p;
    }
    void *p = guardedAlloc(n);
    if (p == nullptr) abort();
    return p;
}

}  // namespace

extern "C" void *__wrap__Znwm(size_t n) { return opNew(n); }
extern "C" void *__wrap__Znam(size_t n) { return opNew(n); }
extern "C" void *__wrap__Znwj(unsigned int n) { return opNew(n); }
extern "C" void *__wrap__Znaj(unsigned int n) { return opNew(n); }
extern "C" void __wrap__ZdlPv(void *p) { __wrap_free(p); }
extern "C" void __wrap__ZdaPv(void *p) { __wrap_free(p); }
extern "C" void __wrap__ZdlPvm(void *p, size_t) { __wrap_free(p); }
extern "C" void __wrap__ZdaPvm(void *p, size_t) { __wrap_free(p); }
extern "C" void __wrap__ZdlPvj(void *p, unsigned int) { __wrap_free(p); }
extern "C" void __wrap__ZdaPvj(void *p, unsigned int) { __wrap_free(p); }
