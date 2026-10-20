module;

#include "jni.h"

#include <atomic>
#include <string>
#include <vector>

export module xposedkit;

import :clazz;
import :object;
import :runtime;
import :thread;

import :java_primitive;
import :jni_helper;

extern "C"
JNIEXPORT jint JNICALL
JNI_OnLoad(JavaVM *vm, void *reserved) {
    JNIEnv* env;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR;
    }

    xposedkit::InstallJniHelper(env);

    return JNI_VERSION_1_6;
}

extern "C"
JNIEXPORT void JNICALL
JNI_OnUnload(JavaVM *vm, void *reserved) {
    JNIEnv* env;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        return;
    }

    xposedkit::UninstallJniHelper(env);
}

extern "C"
JNIEXPORT void JNICALL
Java_cc_meteormc_xposedkit_nativelib_NativeBridge_Init(JNIEnv *env, jclass clazz) {

}

extern "C"
JNIEXPORT void JNICALL
Java_cc_meteormc_xposedkit_nativelib_NativeBridge_Cleanup(JNIEnv *env, jclass clazz) {
    xposedkit::UninstallJniHelper(env);
}

extern "C"
JNIEXPORT jobject JNICALL
Java_cc_meteormc_xposedkit_nativelib_NativeBridge_AllocObject(JNIEnv *env, jclass thiz,
                                                              jclass clazz) {
    if (!clazz) {
        env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"), "clazz is null");
        return nullptr;
    }

    return env->AllocObject(clazz);
}

extern "C"
JNIEXPORT jobject JNICALL
Java_cc_meteormc_xposedkit_nativelib_NativeBridge_CallNonvirtualMethod(JNIEnv *env, jclass thiz,
                                                                       jobject method,
                                                                       jobject instance,
                                                                       jobjectArray args) {
    if (!method) {
        env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"), "method is null");
        return nullptr;
    }

    if (!instance) {
        env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"), "instance is null");
        return nullptr;
    }

    if (!args) {
        env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"), "args is null");
        return nullptr;
    }

    auto target = env->FromReflectedMethod(method);
    auto clazz = (jclass) env->CallObjectMethod(method, xposedkit::Method_Executable_declaringClass);
    auto paramTypes = (jobjectArray) env->CallObjectMethod(method, xposedkit::Method_Executable_paramTypes);

    jsize length = env->GetArrayLength(paramTypes);
    if (length != env->GetArrayLength(args)) {
        env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"), "argument length mismatch");
        return nullptr;
    }

    std::vector<jvalue> cargs(length);
    for (jsize i = 0; i < length; i++) {
        auto arg = env->GetObjectArrayElement(args, i);
        auto paramType = (jclass) env->GetObjectArrayElement(paramTypes, i);
        cargs[i] = xposedkit::ToPrimitiveType(env, paramType, arg);
        env->DeleteLocalRef(paramType);

        if (env->ExceptionCheck())
            return nullptr;
    }

    env->DeleteLocalRef(paramTypes);

    if (env->IsInstanceOf(method, xposedkit::Class_Method)) {
        jvalue local{};
        jvalue* result = &local;
        auto returnType = xposedkit::GetPrimitiveType(
                env,
                (jclass) env->CallObjectMethod(method, xposedkit::Method_Method_returnType));
        switch (returnType) {
            case xposedkit::PrimitiveType::Object:
                return env->CallNonvirtualObjectMethodA(instance, clazz, target, cargs.data());
            case xposedkit::PrimitiveType::Boolean:
                local.z = env->CallNonvirtualBooleanMethodA(instance, clazz, target, cargs.data());
                break;
            case xposedkit::PrimitiveType::Byte:
                local.b = env->CallNonvirtualByteMethodA(instance, clazz, target, cargs.data());
                break;
            case xposedkit::PrimitiveType::Char:
                local.c = env->CallNonvirtualCharMethodA(instance, clazz, target, cargs.data());
                break;
            case xposedkit::PrimitiveType::Short:
                local.s = env->CallNonvirtualShortMethodA(instance, clazz, target, cargs.data());
                break;
            case xposedkit::PrimitiveType::Int:
                local.i = env->CallNonvirtualIntMethodA(instance, clazz, target, cargs.data());
                break;
            case xposedkit::PrimitiveType::Long:
                local.j = env->CallNonvirtualLongMethodA(instance, clazz, target, cargs.data());
                break;
            case xposedkit::PrimitiveType::Float:
                local.f = env->CallNonvirtualFloatMethodA(instance, clazz, target, cargs.data());
                break;
            case xposedkit::PrimitiveType::Double:
                local.d = env->CallNonvirtualDoubleMethodA(instance, clazz, target, cargs.data());
                break;
            case xposedkit::PrimitiveType::Void:
                result = nullptr;
                break;
        }

        if (result) {
            return xposedkit::FromPrimitiveType(env, returnType, *result);
        }
    }

    env->CallNonvirtualVoidMethodA(instance, clazz, target, cargs.data());
    return nullptr;
}

extern "C"
JNIEXPORT jobject JNICALL
Java_cc_meteormc_xposedkit_nativelib_NativeBridge_FindClassInitializer(JNIEnv *env, jclass thiz,
                                                                       jclass clazz) {
    if (!clazz) {
        env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"), "clazz is null");
        return nullptr;
    }

    auto self = xposedkit::art::Thread::Current();
    if (!self) {
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), "Failed to get current thread");
        return nullptr;
    }

    jobject global = env->NewGlobalRef(clazz);
    if (!global) {
        return nullptr;
    }

    auto mirror = (xposedkit::art::mirror::Class*) self->DecodeGlobalJObject(global);
    if (!mirror) {
        env->DeleteGlobalRef(global);
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), "Failed to decode global reference");
        return nullptr;
    }

    auto clinit = mirror->FindClassInitializer();
    env->DeleteGlobalRef(global);
    if (!clinit) {
        return nullptr;
    }

    return env->ToReflectedMethod(clazz, reinterpret_cast<jmethodID>(clinit), true);
}

extern "C"
JNIEXPORT jobjectArray JNICALL
Java_cc_meteormc_xposedkit_nativelib_NativeBridge_VisitHeapObjects(JNIEnv *env, jclass thiz,
                                                                   jclass clazz) {
    if (!clazz) {
        env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"), "clazz is null");
        return nullptr;
    }

    return nullptr;
}