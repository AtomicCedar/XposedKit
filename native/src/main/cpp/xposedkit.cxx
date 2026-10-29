module;

#include <jni.h>
#include <vector>

export module xposedkit;

import :clazz;
import :object;
import :runtime;
import :scoped_thread_state_change;
import :thread;

import :java_primitive;
import :jni_helper;

namespace xposedkit {

static void JNICALL Init(JNIEnv* env, jclass) {
    InstallJniHelper(env);
}

static void JNICALL Cleanup(JNIEnv* env, jclass) {
    UninstallJniHelper(env);
}

static jobject JNICALL AllocObject(JNIEnv* env, jclass,
                                   jclass clazz) {
    if (!clazz) {
        env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"), "clazz is null");
        return nullptr;
    }

    return env->AllocObject(clazz);
}

static jobject JNICALL CallNonvirtualMethod(JNIEnv* env, jclass,
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
    auto clazz = static_cast<jclass>(env->CallObjectMethod(method, Method_Executable_declaringClass));
    auto paramTypes = static_cast<jobjectArray>(env->CallObjectMethod(method, Method_Executable_paramTypes));

    auto length = env->GetArrayLength(paramTypes);
    if (length != env->GetArrayLength(args)) {
        env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"), "argument length mismatch");
        return nullptr;
    }

    std::vector<jvalue> cargs(length);
    for (jsize i = 0; i < length; i++) {
        auto arg = env->GetObjectArrayElement(args, i);
        auto paramType = static_cast<jclass>(env->GetObjectArrayElement(paramTypes, i));
        cargs[i] = ToPrimitiveType(env, paramType, arg);
        env->DeleteLocalRef(paramType);

        if (env->ExceptionCheck()) {
            return nullptr;
        }
    }

    env->DeleteLocalRef(paramTypes);

    if (env->IsInstanceOf(method, Class_Method)) {
        jvalue local{};
        auto result = &local;
        auto returnType = GetPrimitiveType(
            env,
            static_cast<jclass>(env->CallObjectMethod(method, Method_Method_returnType)));
        switch (returnType) {
            case PrimitiveType::Object:
                return env->CallNonvirtualObjectMethodA(instance, clazz, target, cargs.data());
            case PrimitiveType::Boolean:
                local.z = env->CallNonvirtualBooleanMethodA(instance, clazz, target, cargs.data());
                break;
            case PrimitiveType::Byte:
                local.b = env->CallNonvirtualByteMethodA(instance, clazz, target, cargs.data());
                break;
            case PrimitiveType::Char:
                local.c = env->CallNonvirtualCharMethodA(instance, clazz, target, cargs.data());
                break;
            case PrimitiveType::Short:
                local.s = env->CallNonvirtualShortMethodA(instance, clazz, target, cargs.data());
                break;
            case PrimitiveType::Int:
                local.i = env->CallNonvirtualIntMethodA(instance, clazz, target, cargs.data());
                break;
            case PrimitiveType::Long:
                local.j = env->CallNonvirtualLongMethodA(instance, clazz, target, cargs.data());
                break;
            case PrimitiveType::Float:
                local.f = env->CallNonvirtualFloatMethodA(instance, clazz, target, cargs.data());
                break;
            case PrimitiveType::Double:
                local.d = env->CallNonvirtualDoubleMethodA(instance, clazz, target, cargs.data());
                break;
            case PrimitiveType::Void:
                result = nullptr;
                break;
        }

        if (result) {
            return FromPrimitiveType(env, returnType, *result);
        }
    }

    env->CallNonvirtualVoidMethodA(instance, clazz, target, cargs.data());
    return nullptr;
}

static jobject JNICALL FindClassInitializer(JNIEnv* env, jclass,
                                            jclass clazz) {
    if (!clazz) {
        env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"), "clazz is null");
        return nullptr;
    }

    auto self = art::Thread::Current();
    if (!self) {
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), "Failed to get current thread");
        return nullptr;
    }

    art::ScopedObjectAccess soa(self);
    auto mirror = static_cast<art::mirror::Class*>(self->DecodeJObject(env, clazz));
    if (!mirror) {
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), "Failed to decode object");
        return nullptr;
    }

    auto clinit = mirror->FindClassInitializer();
    if (!clinit) {
        return nullptr;
    }

    return env->ToReflectedMethod(clazz, reinterpret_cast<jmethodID>(clinit), true);
}

static jobjectArray JNICALL VisitHeapObjects(JNIEnv* env, jclass,
                                             jclass clazz,
                                             jboolean assignable) {
    if (!clazz) {
        env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"), "clazz is null");
        return nullptr;
    }

    if (GetPrimitiveType(env, clazz) != PrimitiveType::Object) {
        env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"), "clazz is a primitive type");
        return nullptr;
    }

    return nullptr;
}

extern "C"
JNIEXPORT jint JNICALL
JNI_OnLoad(JavaVM* vm, void*) {
    JNIEnv* env;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK || !env) {
        return JNI_ERR;
    }

    auto bridge = env->FindClass("cc/meteormc/xposedkit/nativelib/NativeBridge");
    if (!bridge) {
        return JNI_ERR;
    }

    static const JNINativeMethod methods[] = {
        {
            .name = "Init",
            .signature = "()V",
            .fnPtr = reinterpret_cast<void*>(&Init)
        },
        {
            .name = "Cleanup",
            .signature = "()V",
            .fnPtr = reinterpret_cast<void*>(&Cleanup)
        },
        {
            .name = "AllocObject",
            .signature = "(Ljava/lang/Class;)Ljava/lang/Object;",
            .fnPtr = reinterpret_cast<void*>(&AllocObject)
        },
        {
            .name = "CallNonvirtualMethod",
            .signature = "(Ljava/lang/reflect/Executable;Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;",
            .fnPtr = reinterpret_cast<void*>(&CallNonvirtualMethod)
        },
        {
            .name = "FindClassInitializer",
            .signature = "(Ljava/lang/Class;)Ljava/lang/reflect/Member;",
            .fnPtr = reinterpret_cast<void*>(&FindClassInitializer)
        },
        {
            .name = "VisitHeapObjects",
            .signature = "(Ljava/lang/Class;Z)[Ljava/lang/Object;",
            .fnPtr = reinterpret_cast<void*>(&VisitHeapObjects)
        },
    };

    auto result = env->RegisterNatives(bridge, methods, std::size(methods));
    env->DeleteLocalRef(bridge);
    if (result != JNI_OK) {
        return JNI_ERR;
    }

    return JNI_VERSION_1_6;
}

extern "C"
JNIEXPORT void JNICALL
JNI_OnUnload(JavaVM* vm, void*) {
    JNIEnv* env;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK || !env) {
        return;
    }

    Cleanup(env, nullptr);
}

}  // namespace xposedkit