#define _GNU_SOURCE
#undef _FORTIFY_SOURCE
#define _FORTIFY_SOURCE 0

#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <stdarg.h>
#include <stdbool.h>
#include <stddef.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <unistd.h>

#ifdef __ANDROID__
#include <android/log.h>
#define LOG_TAG "ApexVRoot"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#else
#define LOGD(...) do {} while (0)
#endif

/* Function pointer typedefs for original libc symbols */
typedef int (*orig_open_fn)(const char *pathname, int flags, ...);
typedef int (*orig_openat_fn)(int dirfd, const char *pathname, int flags, ...);
typedef int (*orig_stat_fn)(const char *pathname, struct stat *statbuf);
typedef int (*orig_lstat_fn)(const char *pathname, struct stat *statbuf);
typedef int (*orig_access_fn)(const char *pathname, int mode);
typedef int (*orig_faccessat_fn)(int dirfd, const char *pathname, int mode, int flags);
typedef int (*orig_fstatat_fn)(int dirfd, const char *pathname, struct stat *statbuf, int flags);
typedef int (*orig_execve_fn)(const char *pathname, char *const argv[], char *const envp[]);

static orig_open_fn real_open = NULL;
static orig_open_fn real_open64 = NULL;
static orig_openat_fn real_openat = NULL;
static orig_stat_fn real_stat = NULL;
static orig_lstat_fn real_lstat = NULL;
static orig_access_fn real_access = NULL;
static orig_faccessat_fn real_faccessat = NULL;
static orig_fstatat_fn real_fstatat = NULL;
static orig_execve_fn real_execve = NULL;

static void ensure_symbols_loaded(void) {
    if (!real_open) real_open = (orig_open_fn)dlsym(RTLD_NEXT, "open");
    if (!real_open64) {
        real_open64 = (orig_open_fn)dlsym(RTLD_NEXT, "open64");
        if (!real_open64) real_open64 = real_open;
    }
    if (!real_openat) real_openat = (orig_openat_fn)dlsym(RTLD_NEXT, "openat");
    if (!real_stat) real_stat = (orig_stat_fn)dlsym(RTLD_NEXT, "stat");
    if (!real_lstat) real_lstat = (orig_lstat_fn)dlsym(RTLD_NEXT, "lstat");
    if (!real_access) real_access = (orig_access_fn)dlsym(RTLD_NEXT, "access");
    if (!real_faccessat) real_faccessat = (orig_faccessat_fn)dlsym(RTLD_NEXT, "faccessat");
    if (!real_fstatat) real_fstatat = (orig_fstatat_fn)dlsym(RTLD_NEXT, "fstatat");
    if (!real_execve) real_execve = (orig_execve_fn)dlsym(RTLD_NEXT, "execve");
}

__attribute__((constructor))
static void vroot_init(void) {
    ensure_symbols_loaded();
    LOGD("libvroot.so initialized: Virtual UID=0 & Path Redirection active (PID=%d)", getpid());
}

/* =========================================================================
 * 1. UID / GID / Privilege Spoofing
 * ========================================================================= */

uid_t getuid(void) {
    return 0;
}

uid_t geteuid(void) {
    return 0;
}

gid_t getgid(void) {
    return 0;
}

gid_t getegid(void) {
    return 0;
}

int setuid(uid_t uid) {
    (void)uid;
    errno = 0;
    return 0;
}

int seteuid(uid_t euid) {
    (void)euid;
    errno = 0;
    return 0;
}

int setgid(gid_t gid) {
    (void)gid;
    errno = 0;
    return 0;
}

int setegid(gid_t egid) {
    (void)egid;
    errno = 0;
    return 0;
}

int setreuid(uid_t ruid, uid_t euid) {
    (void)ruid;
    (void)euid;
    errno = 0;
    return 0;
}

int setregid(gid_t rgid, gid_t egid) {
    (void)rgid;
    (void)egid;
    errno = 0;
    return 0;
}

int setresuid(uid_t ruid, uid_t euid, uid_t suid) {
    (void)ruid;
    (void)euid;
    (void)suid;
    errno = 0;
    return 0;
}

int setresgid(gid_t rgid, gid_t egid, gid_t sgid) {
    (void)rgid;
    (void)egid;
    (void)sgid;
    errno = 0;
    return 0;
}

int getresuid(uid_t *ruid, uid_t *euid, uid_t *suid) {
    if (ruid) *ruid = 0;
    if (euid) *euid = 0;
    if (suid) *suid = 0;
    errno = 0;
    return 0;
}

int getresgid(gid_t *rgid, gid_t *egid, gid_t *sgid) {
    if (rgid) *rgid = 0;
    if (egid) *egid = 0;
    if (sgid) *sgid = 0;
    errno = 0;
    return 0;
}

int setgroups(size_t size, const gid_t *list) {
    (void)size;
    (void)list;
    errno = 0;
    return 0;
}

int chown(const char *pathname, uid_t owner, gid_t group) {
    (void)pathname;
    (void)owner;
    (void)group;
    errno = 0;
    return 0;
}

int fchown(int fd, uid_t owner, gid_t group) {
    (void)fd;
    (void)owner;
    (void)group;
    errno = 0;
    return 0;
}

int lchown(const char *pathname, uid_t owner, gid_t group) {
    (void)pathname;
    (void)owner;
    (void)group;
    errno = 0;
    return 0;
}

int fchownat(int dirfd, const char *pathname, uid_t owner, gid_t group, int flags) {
    (void)dirfd;
    (void)pathname;
    (void)owner;
    (void)group;
    (void)flags;
    errno = 0;
    return 0;
}

static bool has_prefix(const char *str, const char *prefix) {
    return strncmp(str, prefix, strlen(prefix)) == 0;
}

/* Forward declaration of path redirection helper */
static const char *redirect_path(const char *pathname, char *buffer, size_t buf_size, bool create_or_write);

int chmod(const char *pathname, mode_t mode) {
    ensure_symbols_loaded();
    typedef int (*orig_chmod_fn)(const char *, mode_t);
    static orig_chmod_fn real_chmod = NULL;
    if (!real_chmod) real_chmod = (orig_chmod_fn)dlsym(RTLD_NEXT, "chmod");
    if (real_chmod) {
        char redirected[PATH_MAX];
        const char *target = redirect_path(pathname, redirected, sizeof(redirected), true);
        real_chmod(target, mode);
    }
    errno = 0;
    return 0;
}

int fchmod(int fd, mode_t mode) {
    typedef int (*orig_fchmod_fn)(int, mode_t);
    static orig_fchmod_fn real_fchmod = NULL;
    if (!real_fchmod) real_fchmod = (orig_fchmod_fn)dlsym(RTLD_NEXT, "fchmod");
    if (real_fchmod) {
        real_fchmod(fd, mode);
    }
    errno = 0;
    return 0;
}

/* =========================================================================
 * 2. Virtual RootFS Path Redirection Engine
 * ========================================================================= */

static bool is_passthrough_path(const char *path) {
    return has_prefix(path, "/sdcard") ||
           has_prefix(path, "/storage") ||
           has_prefix(path, "/proc") ||
           has_prefix(path, "/dev") ||
           has_prefix(path, "/sys") ||
           has_prefix(path, "/apex");
}

static bool is_always_virtualized_prefix(const char *path) {
    return has_prefix(path, "/etc/") || strcmp(path, "/etc") == 0 ||
           has_prefix(path, "/data/local/tmp/") || strcmp(path, "/data/local/tmp") == 0 ||
           has_prefix(path, "/root/") || strcmp(path, "/root") == 0 ||
           has_prefix(path, "/tmp/") || strcmp(path, "/tmp") == 0 ||
           has_prefix(path, "/bin/") || strcmp(path, "/bin") == 0 ||
           has_prefix(path, "/usr/") || strcmp(path, "/usr") == 0;
}

static bool is_overlay_system_prefix(const char *path) {
    return has_prefix(path, "/system/bin/") || strcmp(path, "/system/bin") == 0 ||
           has_prefix(path, "/system/xbin/") || strcmp(path, "/system/xbin") == 0 ||
           has_prefix(path, "/system/etc/") || strcmp(path, "/system/etc") == 0 ||
           has_prefix(path, "/system/") || strcmp(path, "/system") == 0;
}

static const char *redirect_path(const char *pathname, char *buffer, size_t buf_size, bool create_or_write) {
    if (!pathname || pathname[0] != '/') {
        return pathname;
    }

    if (is_passthrough_path(pathname)) {
        return pathname;
    }

    const char *rootfs = getenv("APEX_ROOTFS");
    if (!rootfs || rootfs[0] == '\0') {
        return pathname;
    }

    /* Avoid double-redirection if path is already inside APEX_ROOTFS */
    if (has_prefix(pathname, rootfs)) {
        return pathname;
    }

    ensure_symbols_loaded();

    /* Build candidate path inside $APEX_ROOTFS */
    int written = snprintf(buffer, buf_size, "%s%s", rootfs, pathname);
    if (written < 0 || (size_t)written >= buf_size) {
        return pathname;
    }

    /* Check if the file already exists inside $APEX_ROOTFS */
    if (real_access && real_access(buffer, F_OK) == 0) {
        return buffer;
    }

    /* Redirect writes/creations or paths under container-owned hierarchies */
    if (is_always_virtualized_prefix(pathname)) {
        if (create_or_write) {
            return buffer;
        }
        /* For read lookups in /data/local/tmp, /root, /tmp, /bin, /usr, always use container path */
        if (has_prefix(pathname, "/data/local/tmp") ||
            has_prefix(pathname, "/root") ||
            has_prefix(pathname, "/tmp") ||
            has_prefix(pathname, "/bin") ||
            has_prefix(pathname, "/usr")) {
            return buffer;
        }
    }

    if (is_overlay_system_prefix(pathname) && create_or_write) {
        return buffer;
    }

    return pathname;
}

/* =========================================================================
 * 3. Intercepted Filesystem Calls (open, open64, openat, stat, lstat, access)
 * ========================================================================= */

int open(const char *pathname, int flags, ...) {
    ensure_symbols_loaded();
    mode_t mode = 0;
    if (flags & (O_CREAT | O_TMPFILE)) {
        va_list args;
        va_start(args, flags);
        mode = (mode_t)va_arg(args, int);
        va_end(args);
    }

    char redirected[PATH_MAX];
    bool is_write = (flags & (O_CREAT | O_WRONLY | O_RDWR | O_TRUNC | O_APPEND)) != 0;
    const char *target = redirect_path(pathname, redirected, sizeof(redirected), is_write);

    return real_open(target, flags, mode);
}

#ifdef open64
#undef open64
#endif
int open64(const char *pathname, int flags, ...) {
    ensure_symbols_loaded();
    mode_t mode = 0;
    if (flags & (O_CREAT | O_TMPFILE)) {
        va_list args;
        va_start(args, flags);
        mode = (mode_t)va_arg(args, int);
        va_end(args);
    }

    char redirected[PATH_MAX];
    bool is_write = (flags & (O_CREAT | O_WRONLY | O_RDWR | O_TRUNC | O_APPEND)) != 0;
    const char *target = redirect_path(pathname, redirected, sizeof(redirected), is_write);

    return real_open64(target, flags, mode);
}

int openat(int dirfd, const char *pathname, int flags, ...) {
    ensure_symbols_loaded();
    mode_t mode = 0;
    if (flags & (O_CREAT | O_TMPFILE)) {
        va_list args;
        va_start(args, flags);
        mode = (mode_t)va_arg(args, int);
        va_end(args);
    }

    char redirected[PATH_MAX];
    bool is_write = (flags & (O_CREAT | O_WRONLY | O_RDWR | O_TRUNC | O_APPEND)) != 0;
    const char *target = redirect_path(pathname, redirected, sizeof(redirected), is_write);

    return real_openat(dirfd, target, flags, mode);
}

int stat(const char *pathname, struct stat *statbuf) {
    ensure_symbols_loaded();
    char redirected[PATH_MAX];
    const char *target = redirect_path(pathname, redirected, sizeof(redirected), false);
    int res = real_stat(target, statbuf);
    if (res == 0 && target == redirected) {
        /* Spoof root ownership for files inside virtual RootFS */
        statbuf->st_uid = 0;
        statbuf->st_gid = 0;
    }
    return res;
}

int lstat(const char *pathname, struct stat *statbuf) {
    ensure_symbols_loaded();
    char redirected[PATH_MAX];
    const char *target = redirect_path(pathname, redirected, sizeof(redirected), false);
    int res = real_lstat(target, statbuf);
    if (res == 0 && target == redirected) {
        statbuf->st_uid = 0;
        statbuf->st_gid = 0;
    }
    return res;
}

int fstatat(int dirfd, const char *pathname, struct stat *statbuf, int flags) {
    ensure_symbols_loaded();
    char redirected[PATH_MAX];
    const char *target = redirect_path(pathname, redirected, sizeof(redirected), false);
    int res = real_fstatat(dirfd, target, statbuf, flags);
    if (res == 0 && target == redirected) {
        statbuf->st_uid = 0;
        statbuf->st_gid = 0;
    }
    return res;
}

int access(const char *pathname, int mode) {
    ensure_symbols_loaded();
    char redirected[PATH_MAX];
    const char *target = redirect_path(pathname, redirected, sizeof(redirected), false);
    return real_access(target, mode);
}

int faccessat(int dirfd, const char *pathname, int mode, int flags) {
    ensure_symbols_loaded();
    char redirected[PATH_MAX];
    const char *target = redirect_path(pathname, redirected, sizeof(redirected), false);
    return real_faccessat(dirfd, target, mode, flags);
}

int execve(const char *pathname, char *const argv[], char *const envp[]) {
    ensure_symbols_loaded();
    char redirected[PATH_MAX];
    const char *target = redirect_path(pathname, redirected, sizeof(redirected), false);
    return real_execve(target, argv, envp);
}
