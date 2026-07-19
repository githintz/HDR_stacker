// guarded-alloc.cpp
//
// Guard-page debugging heap, linked ONLY into the debugging ("asan") build
// variant via -Wl,--wrap. Every malloc/calloc/realloc reachable from LibRaw
// and the JNI bridge returns memory whose END sits flush against a PROT_NONE
// page, so the first write past an allocation faults immediately at the
// guilty instruction — producing a tombstone with the exact LibRaw frame —
// instead of silently corrupting a neighbouring buffer.
//
// free() unmaps the whole region, so use-after-free faults as well.
// Pointers that didn't come from this allocator (e.g. strdup'd inside libc)
// are forwarded to the real allocator untouched.

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

namespace {

struct GuardInfo {
    void *base;
    size_t mapLen;
    size_t size;  // originally requested size
};

std::mutex gLock;
// Bookkeeping containers allocate through libc++'s operator new, which lives
// in libc++_shared.so and is NOT wrapped — no recursion into this allocator.
std::unordered_map<void *, GuardInfo> gAllocs;

constexpr size_t kAlign = 16;

void *guardedAlloc(size_t size) {
    if (size == 0) size = 1;
    const size_t page = static_cast<size_t>(sysconf(_SC_PAGESIZE));
    const size_t padded = (size + kAlign - 1) & ~(kAlign - 1);
    const size_t dataLen = (padded + page - 1) & ~(page - 1);
    const size_t mapLen = dataLen + page;  // + trailing guard page
    void *base = mmap(nullptr, mapLen, PROT_READ | PROT_WRITE,
                      MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    if (base == MAP_FAILED) return nullptr;
    if (mprotect(static_cast<char *>(base) + dataLen, page, PROT_NONE) != 0) {
        munmap(base, mapLen);
        return nullptr;
    }
    void *ptr = static_cast<char *>(base) + (dataLen - padded);
    std::lock_guard<std::mutex> lock(gLock);
    gAllocs[ptr] = {base, mapLen, size};
    return ptr;
}

}  // namespace

extern "C" void *__wrap_malloc(size_t size) {
    return guardedAlloc(size);
}

extern "C" void *__wrap_calloc(size_t n, size_t size) {
    if (size != 0 && n > SIZE_MAX / size) return nullptr;
    return guardedAlloc(n * size);  // fresh mmap memory is already zeroed
}

extern "C" void __wrap_free(void *ptr) {
    if (ptr == nullptr) return;
    GuardInfo info;
    {
        std::lock_guard<std::mutex> lock(gLock);
        auto it = gAllocs.find(ptr);
        if (it == gAllocs.end()) {
            // Not ours (allocated inside libc, e.g. strdup): forward.
            __real_free(ptr);
            return;
        }
        info = it->second;
        gAllocs.erase(it);
    }
    munmap(info.base, info.mapLen);
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
