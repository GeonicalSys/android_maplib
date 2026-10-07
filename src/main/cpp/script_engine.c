#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include "quickjs.h"

#define MAX_WIRE (128 * 1024)
#define MAX_SOURCE (512 * 1024)
typedef struct {
    JNIEnv *env;
    jobject host;
    jmethodID call;
    int calls;
    int64_t deadline;
} Execution;

static int64_t monotonic_ms(void) {
    struct timespec t;
    clock_gettime(CLOCK_MONOTONIC, &t);
    return (int64_t)t.tv_sec * 1000 + t.tv_nsec / 1000000;
}
static int interrupt_vm(JSRuntime *rt, void *opaque) {
    (void)rt;
    return monotonic_ms() >= ((Execution *)opaque)->deadline;
}
static jbyteArray bytes(JNIEnv *env, const char *s, size_t len) {
    if (len > MAX_WIRE) return NULL;
    jbyteArray result = (*env)->NewByteArray(env, (jsize)len);
    if (result) (*env)->SetByteArrayRegion(env, result, 0, (jsize)len, (const jbyte *)s);
    return result;
}
static JSValue host_call(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv) {
    (void)this_val;
    Execution *x = JS_GetContextOpaque(ctx);
    if (argc != 1 || ++x->calls > 32 || monotonic_ms() >= x->deadline)
        return JS_ThrowInternalError(ctx, "Host budget exceeded");
    size_t length;
    const char *request = JS_ToCStringLen(ctx, &length, argv[0]);
    if (!request) return JS_EXCEPTION;
    jbyteArray in = bytes(x->env, request, length);
    JS_FreeCString(ctx, request);
    if (!in) return JS_ThrowInternalError(ctx, "Host request too large");
    jbyteArray out = (*x->env)->CallObjectMethod(x->env, x->host, x->call, in);
    (*x->env)->DeleteLocalRef(x->env, in);
    if ((*x->env)->ExceptionCheck(x->env)) {
        (*x->env)->ExceptionClear(x->env);
        if (out) (*x->env)->DeleteLocalRef(x->env, out);
        return JS_ThrowInternalError(ctx, "Host call failed");
    }
    if (!out) return JS_ThrowInternalError(ctx, "Empty host reply");
    jsize size = (*x->env)->GetArrayLength(x->env, out);
    if (size > MAX_WIRE) {
        (*x->env)->DeleteLocalRef(x->env, out);
        return JS_ThrowInternalError(ctx, "Host reply too large");
    }
    jbyte *data = (*x->env)->GetByteArrayElements(x->env, out, NULL);
    JSValue value = data ? JS_NewStringLen(ctx, (const char *)data, (size_t)size) : JS_EXCEPTION;
    if (data) (*x->env)->ReleaseByteArrayElements(x->env, out, data, JNI_ABORT);
    (*x->env)->DeleteLocalRef(x->env, out);
    return value;
}

JNIEXPORT jbyteArray JNICALL
Java_com_nextgis_maplib_scripts_ProjectScriptEngine_evaluate(
        JNIEnv *env, jclass clazz, jbyteArray source, jbyteArray input, jobject host) {
    (void)clazz;
    const char *failure = "{\"error\":\"execution_failed\"}";
    if (!source || !input || !host || (*env)->GetArrayLength(env, source) > MAX_SOURCE
            || (*env)->GetArrayLength(env, input) > MAX_WIRE)
        return bytes(env, failure, strlen(failure));
    Execution x = { .env = env, .host = host, .calls = 0, .deadline = monotonic_ms() + 2000 };
    jclass host_class = (*env)->GetObjectClass(env, host);
    x.call = (*env)->GetMethodID(env, host_class, "call", "([B)[B");
    (*env)->DeleteLocalRef(env, host_class);
    if (!x.call) return NULL;
    JSRuntime *rt = JS_NewRuntime();
    if (!rt) return bytes(env, failure, strlen(failure));
    JS_SetMemoryLimit(rt, 16 * 1024 * 1024);
    JS_SetMaxStackSize(rt, 512 * 1024);
    JS_SetInterruptHandler(rt, interrupt_vm, &x);
    JSContext *ctx = JS_NewContext(rt);
    jbyteArray result = NULL;
    if (ctx) {
        JS_SetContextOpaque(ctx, &x);
        JSValue global = JS_GetGlobalObject(ctx);
        JS_SetPropertyStr(ctx, global, "__hostCall", JS_NewCFunction(ctx, host_call, "hostCall", 1));
        jbyte *in = (*env)->GetByteArrayElements(env, input, NULL);
        if (in) {
            JS_SetPropertyStr(ctx, global, "__input", JS_NewStringLen(ctx, (char *)in,
                (size_t)(*env)->GetArrayLength(env, input)));
            (*env)->ReleaseByteArrayElements(env, input, in, JNI_ABORT);
        }
        JS_FreeValue(ctx, global);
        jsize size = (*env)->GetArrayLength(env, source);
        /* JS_Eval requires a trailing zero even though it receives an explicit byte length. */
        char *code = malloc((size_t)size + 1);
        if (code) {
            (*env)->GetByteArrayRegion(env, source, 0, size, (jbyte *)code);
            code[size] = 0;
            JSValue value = JS_Eval(ctx, code, (size_t)size, "project-script.js", JS_EVAL_TYPE_GLOBAL);
            free(code);
            if (!JS_IsException(value)) {
                size_t len;
                const char *str = JS_ToCStringLen(ctx, &len, value);
                if (str) { result = bytes(env, str, len); JS_FreeCString(ctx, str); }
            }
            JS_FreeValue(ctx, value);
        }
        JS_FreeContext(ctx);
    }
    JS_FreeRuntime(rt);
    return result ? result : bytes(env, failure, strlen(failure));
}
