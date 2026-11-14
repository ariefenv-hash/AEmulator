// su — a real su for the guest (arm64, freestanding, no libc).
//
// The engine's shim already grants every guest id call (run64.c answers setuid/setgid/
// setgroups with success and keeps the ids the guest believes it has), so su does not
// elevate anything: its whole job is to speak the *command-line protocols* its callers
// were built against. That is where a fake su breaks:
//
//   - libsu (and Shizuku on top of it) probes with `su -c id`, then holds ONE shell
//     open and feeds it command lines on stdin, reading stdout for its marker lines.
//     A su that answers once and exits, or that swallows stdin, hangs every root
//     launch after the probe — the "root checkers pass, Shizuku never starts" symptom.
//   - Shizuku's server start is `CLASSPATH=… app_process /system --nice-name=…
//     moe.shizuku.server.ShizukuServer` run through that same shell, so the server
//     inherits fds 0/1/2 untouched.
//
//   su                     exec /system/bin/sh (interactive), stdio untouched
//   su CMD [ARGS…]         exec CMD directly          (as `su id`)
//   su -c CMD [ARGS…]      exec /system/bin/sh -c "CMD ARGS…"
//   su -mm | --mount-master   accepted and ignored: the shim answers mount/unshare with 0
//   su -v | --version      one version line;   su -V   numeric version
//   su -h | --help         usage

typedef unsigned long u64;
typedef long i64;
typedef unsigned int u32;

#define SYS_WRITE 64
#define SYS_EXIT_GROUP 94
#define SYS_SETUID 146
#define SYS_SETGID 144
#define SYS_SETGROUPS 159
#define SYS_EXECVE 221

#define SU_VERSION_NUM "100"          // what `su -V` prints
#define SU_VERSION_STR "AEmulator su 1.0 (api " SU_VERSION_NUM ")"
#define SHELL "/system/bin/sh"

static void sc_exit(int c) { for (;;) { __asm__ volatile ("svc 0" :: "r"((long)SYS_EXIT_GROUP), "r"((long)c) : "memory"); } }

static long sc(long nr, long a, long b, long c) {
    register long x8 __asm__("x8") = nr;
    register long x0 __asm__("x0") = a;
    register long x1 __asm__("x1") = b;
    register long x2 __asm__("x2") = c;
    __asm__ volatile ("svc 0" : "=r"(x0) : "r"(x8), "0"(x0), "r"(x1), "r"(x2) : "memory", "cc");
    return x0;
}

static void wr(int fd, const char *s) {
    u64 n = 0; while (s[n]) n++;
    sc(SYS_WRITE, fd, (long)s, (long)n);
}

static u64 slen(const char *s) { u64 n = 0; while (s[n]) n++; return n; }

void _start(void) {
    u64 *sp; __asm__ volatile ("mov %0, sp" : "=r"(sp));
    long argc = (long)sp[0];
    char **argv = (char **)(sp + 1);
    char **envp = argv + argc + 1;

    // ids first: the shim keeps g_uid/g_ruid for every later exec of the chain
    sc(SYS_SETUID, 0, 0, 0);
    sc(SYS_SETGID, 0, 0, 0);
    sc(SYS_SETGROUPS, 0, 0, 0);

    const char *cmd = 0;      // -c
    int direct = 0;           // first non-flag arg is the program itself
    for (int i = 1; i < argc; i++) {
        const char *a = argv[i];
        if (a[0] != '-') { direct = 1; break; }
        if (a[1] == 'c' && !a[2] && i + 1 < argc) { cmd = argv[++i]; continue; }
        if (a[1] == '-' && !a[2]) { i++; direct = i < argc; break; }          // `--`
        if ((a[1] == 'm' && a[2] == 'm' && !a[3]) || (a[1] == '-' && a[2] == 'm' && a[3] == 'o')) continue; // -mm / --mount-master
        if (a[1] == 'v' && !a[2]) { wr(1, SU_VERSION_STR "\n"); sc_exit(0); }
        if (a[1] == 'V' && !a[2]) { wr(1, SU_VERSION_NUM "\n"); sc_exit(0); }
        if (a[1] == 'h' && !a[2]) {
            wr(1, "usage: su [-c CMD] [-mm] [-v] [-V] [CMD ARGS]\n");
            sc_exit(0);
        }
        wr(2, "su: unknown option: "); wr(2, a); wr(2, "\n"); sc_exit(1);
    }

    if (cmd) {                                    // sh -c "…"
        // every argument after -c belongs to the command line, space-joined, exactly what
        // libsu's shell builder and Shizuku's starter both send
        int start = -1;
        for (int i = 1; i < argc; i++) if (argv[i] == cmd) { start = i; break; }
        if (start < 0) sc_exit(1);
        u64 n = 2;                                // NUL + a little slack
        for (int i = start; i < argc; i++) n += slen(argv[i]) + 1;
        // room on the stack below the frame, aligned; no heap here
        char *buf = (char *)(((u64)(sp - ((n + 255) / 8 + 32)) & ~15UL));
        char *p = buf;
        for (int i = start; i < argc; i++) {
            const char *s = argv[i];
            while (*s) *p++ = *s++;
            if (i + 1 < argc) *p++ = ' ';
        }
        *p = 0;
        char *av[] = { (char*)"sh", (char*)"-c", buf, 0 };
        sc(SYS_EXECVE, (long)SHELL, (long)av, (long)envp);
        wr(2, "su: cannot exec " SHELL "\n");
        sc_exit(126);
    }

    if (direct) {                                 // su CMD ARGS — exec CMD itself
        char **av = argv + 1;                     // argv[1] is CMD, already NULL-terminated
        sc(SYS_EXECVE, (long)av[0], (long)av, (long)envp);
        wr(2, "su: cannot exec "); wr(2, av[0]); wr(2, "\n");
        sc_exit(127);
    }

    {                                             // plain `su`: interactive shell
        char *av[] = { (char*)"sh", 0 };
        sc(SYS_EXECVE, (long)SHELL, (long)av, (long)envp);
        wr(2, "su: cannot exec " SHELL "\n");
        sc_exit(126);
    }
}
