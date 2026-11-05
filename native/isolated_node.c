#include <jni.h>
#include <android/dlext.h>
#include <dlfcn.h>
#include <stdlib.h>
#include <stdio.h>
#include <unistd.h>
#include <string.h>
#include <sys/resource.h>
#include <android/log.h>
#include <errno.h>
#include <fcntl.h>

/* Exact Node24.18.0 Start ABI. Separate process only; no Node/Core/ELF runtime patch. */
JNIEXPORT jstring JNICALL Java_dev_alita_androidagent_execution_isolation_IsolatedNodeService_runNode(
    JNIEnv *env, jclass type, jobjectArray names, jintArray fd_array, jint in_fd,
    jint out_fd, jint err_fd, jbyteArray script, jint cpu_seconds) {
    (void)type;
    int occupied = 0;
    for (int fd = 0; fd < 512; fd++) if (fcntl(fd, F_GETFD) >= 0) occupied++;
    __android_log_print(ANDROID_LOG_INFO, "IsolatedNodeRuntime", "initial_fds_below512=%d", occupied);
    if (occupied > 480) return (*env)->NewStringUTF(env, "runtime_fd_headroom_rejected");
    struct rlimit cores = {0, 0}, files = {512, 512};
    struct rlimit cpu = {(rlim_t)cpu_seconds + 1, (rlim_t)cpu_seconds + 1};
    if (cpu_seconds < 1 || cpu_seconds > 60 || setrlimit(RLIMIT_CORE, &cores) ||
        setrlimit(RLIMIT_NOFILE, &files) || setrlimit(RLIMIT_CPU, &cpu))
        { __android_log_print(ANDROID_LOG_ERROR, "IsolatedNodeRuntime", "resource_limits_failed errno=%d", errno);
        return (*env)->NewStringUTF(env, "resource_limits_failed"); }
    if (dup2(in_fd, 0) < 0 || dup2(out_fd, 1) < 0 || dup2(err_fd, 2) < 0)
        return (*env)->NewStringUTF(env, "stdio_dup_failed");
    int count = (*env)->GetArrayLength(env, names);
    if (count != 10 || (*env)->GetArrayLength(env, fd_array) != count)
        return (*env)->NewStringUTF(env, "fd_count_mismatch");
    jint *fds = (*env)->GetIntArrayElements(env, fd_array, NULL);
    if (!fds) return NULL;
    void *node = NULL;
    for (int i = 0; i < count; i++) {
        jstring item = (jstring)(*env)->GetObjectArrayElement(env, names, i);
        const char *name = (*env)->GetStringUTFChars(env, item, NULL);
        if (!name) { (*env)->ReleaseIntArrayElements(env, fd_array, fds, JNI_ABORT); return NULL; }
        android_dlextinfo info = {0}; info.flags = ANDROID_DLEXT_USE_LIBRARY_FD; info.library_fd = fds[i];
        node = android_dlopen_ext(name, RTLD_NOW | RTLD_GLOBAL, &info);
        if (!node) __android_log_print(ANDROID_LOG_ERROR, "IsolatedNodeRuntime", "dlopen %s: %s", name, dlerror());
        (*env)->ReleaseStringUTFChars(env, item, name); (*env)->DeleteLocalRef(env, item);
        if (!node) {
            (*env)->ReleaseIntArrayElements(env, fd_array, fds, JNI_ABORT);
            return (*env)->NewStringUTF(env, "runtime_library_load_failed");
        }
    }
    (*env)->ReleaseIntArrayElements(env, fd_array, fds, JNI_ABORT);
    int (*start)(int, char **) = (int (*)(int, char **))dlsym(node, "_ZN4node5StartEiPPc");
    if (!start) return (*env)->NewStringUTF(env, "node_start_export_missing");
    clearenv(); setenv("OPENSSL_CONF", "/dev/null", 1); setenv("HOME", "/", 1); setenv("PATH", "/system/bin", 1);
    jsize size = (*env)->GetArrayLength(env, script);
    if (size < 1 || size > 262144) return (*env)->NewStringUTF(env, "script_size_rejected");
    const char options[] = "node\0--max-old-space-size=64\0--max-semi-space-size=8\0-e\0";
    size_t prefix = sizeof(options) - 1;
    char *arguments = malloc(prefix + (size_t)size + 1);
    if (!arguments) return (*env)->NewStringUTF(env, "argument_allocation_failed");
    memcpy(arguments, options, prefix);
    (*env)->GetByteArrayRegion(env, script, 0, size, (jbyte *)(arguments + prefix));
    if ((*env)->ExceptionCheck(env)) { free(arguments); return NULL; }
    if (memchr(arguments + prefix, 0, (size_t)size)) { free(arguments); return (*env)->NewStringUTF(env, "script_nul_rejected"); }
    arguments[prefix + size] = 0;
    char *argv[6]; char *cursor = arguments;
    for (int i = 0; i < 4; i++) { argv[i] = cursor; cursor += strlen(cursor) + 1; }
    argv[4] = arguments + prefix; argv[5] = NULL;
    int result = start(5, argv);
    free(arguments);
    char message[64]; snprintf(message, sizeof(message), "node_returned_%d", result);
    return (*env)->NewStringUTF(env, message);
}
