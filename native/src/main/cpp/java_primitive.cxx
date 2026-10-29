module;

#include <jni.h>

export module xposedkit:java_primitive;

import :jni_helper;

namespace xposedkit {

enum class PrimitiveType {
    Object,
    Boolean,
    Byte,
    Char,
    Short,
    Int,
    Long,
    Float,
    Double,
    Void,
};

auto GetPrimitiveType(JNIEnv* env, jclass clazz) {
    using enum PrimitiveType;
    if (env->IsSameObject(clazz, Class_Boolean))
        return Boolean;

    if (env->IsSameObject(clazz, Class_Byte))
        return Byte;

    if (env->IsSameObject(clazz, Class_Char))
        return Char;

    if (env->IsSameObject(clazz, Class_Short))
        return Short;

    if (env->IsSameObject(clazz, Class_Int))
        return Int;

    if (env->IsSameObject(clazz, Class_Long))
        return Long;

    if (env->IsSameObject(clazz, Class_Float))
        return Float;

    if (env->IsSameObject(clazz, Class_Double))
        return Double;

    if (env->IsSameObject(clazz, Class_Void))
        return Void;

    return Object;
}

jobject FromPrimitiveType(JNIEnv* env, PrimitiveType type, jvalue value) {
    using enum PrimitiveType;
    switch (type) {
        case Object:
            return value.l;
        case Boolean:
            return env->CallStaticObjectMethod(Class_Boolean, Method_Boolean_boxing, value.z);
        case Byte:
            return env->CallStaticObjectMethod(Class_Byte, Method_Byte_boxing, value.b);
        case Char:
            return env->CallStaticObjectMethod(Class_Char, Method_Char_boxing, value.c);
        case Short:
            return env->CallStaticObjectMethod(Class_Short, Method_Short_boxing, value.s);
        case Int:
            return env->CallStaticObjectMethod(Class_Int, Method_Int_boxing, value.i);
        case Long:
            return env->CallStaticObjectMethod(Class_Long, Method_Long_boxing, value.j);
        case Float:
            return env->CallStaticObjectMethod(Class_Float, Method_Float_boxing, value.f);
        case Double:
            return env->CallStaticObjectMethod(Class_Double, Method_Double_boxing, value.d);
        case Void:
            return nullptr;
    }

    return nullptr;
}

jobject FromPrimitiveType(JNIEnv* env, jclass clazz, jvalue value) {
    return FromPrimitiveType(env, GetPrimitiveType(env, clazz), value);
}

jvalue ToPrimitiveType(JNIEnv* env, PrimitiveType type, jobject value) {
    jvalue jv{};
    using enum PrimitiveType;
    switch (type) {
        case Object:
            jv.l = value;
            break;
        case Boolean:
            jv.z = env->CallBooleanMethod(value, Method_Boolean_unboxing);
            break;
        case Byte:
            jv.b = env->CallByteMethod(value, Method_Byte_unboxing);
            break;
        case Char:
            jv.c = env->CallCharMethod(value, Method_Char_unboxing);
            break;
        case Short:
            jv.s = env->CallShortMethod(value, Method_Short_unboxing);
            break;
        case Int:
            jv.i = env->CallIntMethod(value, Method_Int_unboxing);
            break;
        case Long:
            jv.j = env->CallLongMethod(value, Method_Long_unboxing);
            break;
        case Float:
            jv.f = env->CallFloatMethod(value, Method_Float_unboxing);
            break;
        case Double:
            jv.d = env->CallDoubleMethod(value, Method_Double_unboxing);
            break;
        case Void:
            break;
    }

    return jv;
}

jvalue ToPrimitiveType(JNIEnv* env, jclass clazz, jobject value) {
    return ToPrimitiveType(env, GetPrimitiveType(env, clazz), value);
}

}  // namespace xposedkit