#define _GNU_SOURCE
#include <ctype.h>
#include <errno.h>
#include <limits.h>
#include <stdbool.h>
#include <stddef.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <unistd.h>

extern char **environ;

#define SOCKET_NAME "apex_privileged_socket"
#define DEFAULT_PKG_DATA "/data/data/com.apex.container"

/* Resolve the directory containing this binary (nativeLibraryDir) via /proc/self/exe */
static void resolve_self_dir(char *out_dir, size_t size) {
    char exe_path[PATH_MAX];
    ssize_t len = readlink("/proc/self/exe", exe_path, sizeof(exe_path) - 1);
    if (len > 0) {
        exe_path[len] = '\0';
        char *last_slash = strrchr(exe_path, '/');
        if (last_slash) {
            *last_slash = '\0';
            snprintf(out_dir, size, "%s", exe_path);
            return;
        }
    }
    const char *env_native = getenv("APEX_NATIVE_DIR");
    if (env_native && env_native[0] != '\0') {
        snprintf(out_dir, size, "%s", env_native);
    } else {
        snprintf(out_dir, size, "%s/lib", DEFAULT_PKG_DATA);
    }
}

/* Ensure LD_PRELOAD, APEX_ROOTFS, PATH, PS1, HOME, and USER are configured */
static void setup_virtual_root_env(void) {
    char self_dir[PATH_MAX];
    resolve_self_dir(self_dir, sizeof(self_dir));

    const char *rootfs = getenv("APEX_ROOTFS");
    char default_rootfs[PATH_MAX];
    if (!rootfs || rootfs[0] == '\0') {
        snprintf(default_rootfs, sizeof(default_rootfs), "%s/files/rootfs", DEFAULT_PKG_DATA);
        rootfs = default_rootfs;
        setenv("APEX_ROOTFS", rootfs, 1);
    }

    char vroot_so[PATH_MAX + 64];
    const char *existing_vroot = getenv("APEX_VROOT_SO");
    if (existing_vroot && access(existing_vroot, R_OK) == 0) {
        snprintf(vroot_so, sizeof(vroot_so), "%s", existing_vroot);
    } else {
        snprintf(vroot_so, sizeof(vroot_so), "%s/libvroot.so", self_dir);
    }

    if (access(vroot_so, R_OK) == 0) {
        setenv("LD_PRELOAD", vroot_so, 1);
    }

    char new_path[PATH_MAX * 6];
    const char *old_path = getenv("PATH");
    if (!old_path) old_path = "/system/bin:/system/xbin:/product/bin:/apex/com.android.runtime/bin";
    snprintf(
        new_path,
        sizeof(new_path),
        "%s/bin:%s/usr/bin:%s/system/bin:%s/system/xbin:%s",
        rootfs, rootfs, rootfs, rootfs, old_path
    );
    setenv("PATH", new_path, 1);

    setenv("HOME", "/root", 1);
    setenv("USER", "root", 1);
    setenv("LOGNAME", "root", 1);
    setenv("SHELL", "/system/bin/sh", 1);
    setenv("PS1", "root@apex:\\w # ", 1);
}

/* Determine whether a command requires UID 2000 (AID_SHELL) / Android OS binder services */
static bool requires_host_bridge(const char *cmd) {
    if (!cmd) return false;
    while (*cmd && isspace((unsigned char)*cmd)) {
        cmd++;
    }

    const char *privileged_prefixes[] = {
        "pm ", "am ", "cmd ", "settings ", "dumpsys ", "logcat",
        "appops ", "wm ", "svc ", "content ", "input ", "ime ",
        "/system/bin/pm", "/system/bin/am", "/system/bin/cmd",
        "/system/bin/settings", "/system/bin/dumpsys", "/system/bin/logcat",
        NULL
    };

    for (int i = 0; privileged_prefixes[i] != NULL; i++) {
        size_t plen = strlen(privileged_prefixes[i]);
        if (strncmp(cmd, privileged_prefixes[i], plen) == 0) {
            return true;
        }
    }

    if (strstr(cmd, "/sdcard/Android/data") != NULL ||
        strstr(cmd, "/storage/emulated/0/Android/data") != NULL ||
        strstr(cmd, "/sdcard/Android/obb") != NULL) {
        return true;
    }

    return false;
}

/* Forward privileged command to Kotlin LocalServerSocket ("apex_privileged_socket") */
static int execute_via_socket(const char *cmd, int *out_exit_code) {
    int sock = socket(AF_UNIX, SOCK_STREAM, 0);
    if (sock < 0) {
        return -1;
    }

    struct sockaddr_un addr;
    memset(&addr, 0, sizeof(addr));
    addr.sun_family = AF_UNIX;
    /* Abstract namespace socket: leading null byte followed by name */
    addr.sun_path[0] = '\0';
    strncpy(addr.sun_path + 1, SOCKET_NAME, sizeof(addr.sun_path) - 2);
    socklen_t addr_len = (socklen_t)(offsetof(struct sockaddr_un, sun_path) + 1 + strlen(SOCKET_NAME));

    if (connect(sock, (struct sockaddr *)&addr, addr_len) < 0) {
        close(sock);
        return -1;
    }

    /* Send command terminated by newline */
    size_t cmd_len = strlen(cmd);
    if (write(sock, cmd, cmd_len) < 0 || write(sock, "\n", 1) < 0) {
        close(sock);
        return -1;
    }
    shutdown(sock, SHUT_WR);

    char buffer[4096];
    ssize_t n;
    int exit_code = 0;
    const char *exit_marker = "__APEX_EXIT_CODE__:";

    while ((n = read(sock, buffer, sizeof(buffer) - 1)) > 0) {
        buffer[n] = '\0';
        char *marker_pos = strstr(buffer, exit_marker);
        if (marker_pos) {
            *marker_pos = '\0';
            if (marker_pos > buffer) {
                fwrite(buffer, 1, (size_t)(marker_pos - buffer), stdout);
            }
            exit_code = atoi(marker_pos + strlen(exit_marker));
        } else {
            fwrite(buffer, 1, (size_t)n, stdout);
        }
    }
    fflush(stdout);
    close(sock);

    if (out_exit_code) {
        *out_exit_code = exit_code;
    }
    return 0;
}

int main(int argc, char *argv[]) {
    const char *command_to_run = NULL;
    char concatenated_cmd[4096] = {0};

    for (int i = 1; i < argc; i++) {
        if (strcmp(argv[i], "-c") == 0 && i + 1 < argc) {
            /* Combine all remaining arguments after -c */
            size_t offset = 0;
            for (int j = i + 1; j < argc; j++) {
                int written = snprintf(
                    concatenated_cmd + offset,
                    sizeof(concatenated_cmd) - offset,
                    "%s%s",
                    (j > i + 1) ? " " : "",
                    argv[j]
                );
                if (written > 0) {
                    offset += (size_t)written;
                    if (offset >= sizeof(concatenated_cmd) - 1) break;
                }
            }
            command_to_run = concatenated_cmd;
            break;
        } else if (strcmp(argv[i], "-v") == 0 || strcmp(argv[i], "--version") == 0) {
            printf("1.0.0:ApexContainer-VirtualSU (LD_PRELOAD + Shizuku Bridge)\n");
            return 0;
        }
    }

    /* Case 1: su -c "<command>" */
    if (command_to_run != NULL) {
        if (requires_host_bridge(command_to_run)) {
            int exit_code = 0;
            if (execute_via_socket(command_to_run, &exit_code) == 0) {
                return exit_code;
            }
            fprintf(stderr, "[ApexSU] Host socket unreachable; falling back to local container execution.\n");
        }

        setup_virtual_root_env();
        char *sh_argv[] = { "/system/bin/sh", "-c", (char *)command_to_run, NULL };
        execve("/system/bin/sh", sh_argv, environ);
        perror("execve(/system/bin/sh)");
        return 127;
    }

    /* Case 2: Interactive root shell (su without -c) */
    setup_virtual_root_env();
    char *sh_argv[] = { "/system/bin/sh", "-i", NULL };
    execve("/system/bin/sh", sh_argv, environ);
    perror("execve(/system/bin/sh)");
    return 127;
}
