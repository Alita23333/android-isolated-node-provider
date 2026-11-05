#include <jni.h>
#include <android/dlext.h>
#include <dlfcn.h>
#include <stdlib.h>
#include <stdio.h>
#include <unistd.h>
#include <string.h>

/* Probe only: reuse exact validated Node/libraries through read-only Binder FDs.
 * No file modes, namespaces, SELinux policy, executable payload or Core are patched. */
JNIEXPORT jstring JNICALL Java_dev_alita_providerprobe_IsolatedNodeService_runNode(
    JNIEnv *env, jclass type, jobjectArray names, jintArray fd_array, jint in_fd, jint out_fd,
    jint err_fd, jstring script) {
    (void)type;
    if (dup2(in_fd, STDIN_FILENO) < 0 || dup2(out_fd, STDOUT_FILENO) < 0 || dup2(err_fd, STDERR_FILENO) < 0)
        return (*env)->NewStringUTF(env, "stdio_dup_failed");
    jint *fds = (*env)->GetIntArrayElements(env, fd_array, NULL);
    int count = (*env)->GetArrayLength(env, names);
    if ((*env)->GetArrayLength(env, fd_array) != count) return (*env)->NewStringUTF(env, "fd_count_mismatch");
    void *node = NULL;
    for (int i = 0; i < count; i++) {
        jstring item = (jstring)(*env)->GetObjectArrayElement(env, names, i);
        const char *name = (*env)->GetStringUTFChars(env, item, NULL);
        android_dlextinfo info = {0};
        info.flags = ANDROID_DLEXT_USE_LIBRARY_FD;
        info.library_fd = fds[i];
        void *handle = android_dlopen_ext(name, RTLD_NOW | RTLD_GLOBAL, &info);
        if (!handle) {
            char error[1024]; snprintf(error, sizeof(error), "dlopen(%s): %s", name, dlerror());
            (*env)->ReleaseStringUTFChars(env, item, name);
            (*env)->ReleaseIntArrayElements(env, fd_array, fds, JNI_ABORT);
            return (*env)->NewStringUTF(env, error);
        }
        node = handle;
        (*env)->ReleaseStringUTFChars(env, item, name);
    }
    (*env)->ReleaseIntArrayElements(env, fd_array, fds, JNI_ABORT);
    int (*start)(int, char **) = (int (*)(int, char **))dlsym(node, "_ZN4node5StartEiPPc");
    if (!start) return (*env)->NewStringUTF(env, "node_start_export_missing");
    clearenv(); setenv("OPENSSL_CONF", "/dev/null", 1); setenv("HOME", "/", 1); setenv("PATH", "/system/bin", 1);
    const char *source = (*env)->GetStringUTFChars(env, script, NULL);
    char *arguments = malloc(8 + strlen(source) + 1);
    if (!arguments) return (*env)->NewStringUTF(env, "argument_allocation_failed");
    memcpy(arguments, "node\0-e\0", 8); strcpy(arguments + 8, source);
    char *argv[] = {arguments, arguments + 5, arguments + 8, NULL};
    int result = start(3, argv);
    free(arguments);
    (*env)->ReleaseStringUTFChars(env, script, source);
    char message[64]; snprintf(message, sizeof(message), "node_returned_%d", result);
    return (*env)->NewStringUTF(env, message);
}
