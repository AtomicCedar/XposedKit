module;

#include "jni.h"

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
    if (env->IsSameObject(clazz, Class_Boolean))
        return PrimitiveType::Boolean;

    if (env->IsSameObject(clazz, Class_Byte))
        return PrimitiveType::Byte;

    if (env->IsSameObject(clazz, Class_Char))
        return PrimitiveType::Char;

    if (env->IsSameObject(clazz, Class_Short))
        return PrimitiveType::Short;

    if (env->IsSameObject(clazz, Class_Int))
        return PrimitiveType::Int;

    if (env->IsSameObject(clazz, Class_Long))
        return PrimitiveType::Long;

    if (env->IsSameObject(clazz, Class_Float))
        return PrimitiveType::Float;

    if (env->IsSameObject(clazz, Class_Double))
        return PrimitiveType::Double;

    if (env->IsSameObject(clazz, Class_Void))
        return PrimitiveType::Void;

    return PrimitiveType::Object;
}

jobject FromPrimitiveType(JNIEnv* env, PrimitiveType type, jvalue value) {
    switch (type) {
        case PrimitiveType::Object:
            return value.l;
        case PrimitiveType::Boolean:
            return env->CallStaticObjectMethod(Class_Boolean, Method_Boolean_boxing, value.z);
        case PrimitiveType::Byte:
            return env->CallStaticObjectMethod(Class_Byte, Method_Byte_boxing, value.b);
        case PrimitiveType::Char:
            return env->CallStaticObjectMethod(Class_Char, Method_Char_boxing, value.c);
        case PrimitiveType::Short:
            return env->CallStaticObjectMethod(Class_Short, Method_Short_boxing, value.s);
        case PrimitiveType::Int:
            return env->CallStaticObjectMethod(Class_Int, Method_Int_boxing, value.i);
        case PrimitiveType::Long:
            return env->CallStaticObjectMethod(Class_Long, Method_Long_boxing, value.j);
        case PrimitiveType::Float:
            return env->CallStaticObjectMethod(Class_Float, Method_Float_boxing, value.f);
        case PrimitiveType::Double:
            return env->CallStaticObjectMethod(Class_Double, Method_Double_boxing, value.d);
        case PrimitiveType::Void:
            return nullptr;
    }
}

jobject FromPrimitiveType(JNIEnv* env, jclass clazz, jvalue value) {
    return FromPrimitiveType(env, GetPrimitiveType(env, clazz), value);
}

jvalue ToPrimitiveType(JNIEnv* env, PrimitiveType type, jobject value) {
    jvalue jv{};
    switch (type) {
        case PrimitiveType::Object:
            jv.l = value;
            break;
        case PrimitiveType::Boolean:
            jv.z = env->CallBooleanMethod(value, Method_Boolean_unboxing);
            break;
        case PrimitiveType::Byte:
            jv.b = env->CallByteMethod(value, Method_Byte_unboxing);
            break;
        case PrimitiveType::Char:
            jv.c = env->CallCharMethod(value, Method_Char_unboxing);
            break;
        case PrimitiveType::Short:
            jv.s = env->CallShortMethod(value, Method_Short_unboxing);
            break;
        case PrimitiveType::Int:
            jv.i = env->CallIntMethod(value, Method_Int_unboxing);
            break;
        case PrimitiveType::Long:
            jv.j = env->CallLongMethod(value, Method_Long_unboxing);
            break;
        case PrimitiveType::Float:
            jv.f = env->CallFloatMethod(value, Method_Float_unboxing);
            break;
        case PrimitiveType::Double:
            jv.d = env->CallDoubleMethod(value, Method_Double_unboxing);
            break;
        case PrimitiveType::Void:
            break;
    }

    return jv;
}

jvalue ToPrimitiveType(JNIEnv* env, jclass clazz, jobject value) {
    return ToPrimitiveType(env, GetPrimitiveType(env, clazz), value);
}

}