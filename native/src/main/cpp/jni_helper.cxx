module;

#include <jni.h>
#include <string>

export module xposedkit:jni_helper;

namespace xposedkit {

namespace {
template <typename... Names>
requires(std::convertible_to<Names, const char*> && ...)
jclass FindClass(JNIEnv* env, Names&&... names) {
    jclass result = nullptr;

    auto try_find = [env, &result](const char* name) {
        if (result) return;

        result = env->FindClass(name);
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            result = nullptr;
        }
    };

    (try_find(std::forward<Names>(names)), ...);

    if (!result) {
        env->ThrowNew(env->FindClass("java/lang/ClassNotFoundException"), "None of the candidate classes were found");
        return nullptr;
    }

    return static_cast<jclass>(env->NewGlobalRef(result));
}
}  // namespace

jclass Class_Boolean;
jclass Class_Byte;
jclass Class_Char;
jclass Class_Short;
jclass Class_Int;
jclass Class_Long;
jclass Class_Float;
jclass Class_Double;
jclass Class_Void;

jclass Class_Class;
jclass Class_Executable;
jclass Class_Method;
jclass Class_Throwable;

jmethodID Method_Boolean_boxing;
jmethodID Method_Byte_boxing;
jmethodID Method_Char_boxing;
jmethodID Method_Short_boxing;
jmethodID Method_Int_boxing;
jmethodID Method_Long_boxing;
jmethodID Method_Float_boxing;
jmethodID Method_Double_boxing;

jmethodID Method_Boolean_unboxing;
jmethodID Method_Byte_unboxing;
jmethodID Method_Char_unboxing;
jmethodID Method_Short_unboxing;
jmethodID Method_Int_unboxing;
jmethodID Method_Long_unboxing;
jmethodID Method_Float_unboxing;
jmethodID Method_Double_unboxing;

jmethodID Method_Class_declaredConstructors;
jmethodID Method_Executable_declaringClass;
jmethodID Method_Executable_paramTypes;
jmethodID Method_Method_returnType;

jfieldID Field_Executable_artMethod;

void InstallJniHelper(JNIEnv* env) {
    Class_Boolean = FindClass(env, "java/lang/Boolean");
    Class_Byte = FindClass(env, "java/lang/Byte");
    Class_Char = FindClass(env, "java/lang/Character");
    Class_Short = FindClass(env, "java/lang/Short");
    Class_Int = FindClass(env, "java/lang/Integer");
    Class_Long = FindClass(env, "java/lang/Long");
    Class_Float = FindClass(env, "java/lang/Float");
    Class_Double = FindClass(env, "java/lang/Double");
    Class_Void = FindClass(env, "java/lang/Void");

    Class_Class = FindClass(env, "java/lang/Class");
    Class_Executable = FindClass(env, "java/lang/reflect/Executable", "java/lang/reflect/AbstractMethod");
    Class_Method = FindClass(env, "java/lang/reflect/Method");
    Class_Throwable = FindClass(env, "java/lang/Throwable");

    Method_Boolean_boxing = env->GetStaticMethodID(Class_Boolean, "valueOf", "(Z)Ljava/lang/Boolean;");
    Method_Byte_boxing = env->GetStaticMethodID(Class_Byte, "valueOf", "(B)Ljava/lang/Byte;");
    Method_Char_boxing = env->GetStaticMethodID(Class_Char, "valueOf", "(C)Ljava/lang/Character;");
    Method_Short_boxing = env->GetStaticMethodID(Class_Short, "valueOf", "(S)Ljava/lang/Short;");
    Method_Int_boxing = env->GetStaticMethodID(Class_Int, "valueOf", "(I)Ljava/lang/Integer;");
    Method_Long_boxing = env->GetStaticMethodID(Class_Long, "valueOf", "(J)Ljava/lang/Long;");
    Method_Float_boxing = env->GetStaticMethodID(Class_Float, "valueOf", "(F)Ljava/lang/Float;");
    Method_Double_boxing = env->GetStaticMethodID(Class_Double, "valueOf", "(D)Ljava/lang/Double;");

    Method_Boolean_unboxing = env->GetMethodID(Class_Boolean, "booleanValue", "()Z");
    Method_Byte_unboxing = env->GetMethodID(Class_Byte, "byteValue", "()B");
    Method_Char_unboxing = env->GetMethodID(Class_Char, "charValue", "()C");
    Method_Short_unboxing = env->GetMethodID(Class_Short, "shortValue", "()S");
    Method_Int_unboxing = env->GetMethodID(Class_Int, "intValue", "()I");
    Method_Long_unboxing = env->GetMethodID(Class_Long, "longValue", "()J");
    Method_Float_unboxing = env->GetMethodID(Class_Float, "floatValue", "()F");
    Method_Double_unboxing = env->GetMethodID(Class_Double, "doubleValue", "()D");

    Method_Class_declaredConstructors = env->GetMethodID(Class_Class, "getDeclaredConstructors", "()[Ljava/lang/reflect/Constructor;");
    Method_Executable_declaringClass = env->GetMethodID(Class_Executable, "getDeclaringClass", "()Ljava/lang/Class;");
    Method_Executable_paramTypes = env->GetMethodID(Class_Executable, "getParameterTypes", "()[Ljava/lang/Class;");
    Method_Method_returnType = env->GetMethodID(Class_Method, "getReturnType", "()Ljava/lang/Class;");

    Field_Executable_artMethod = env->GetFieldID(Class_Executable, "artMethod", "J");
}

void UninstallJniHelper(JNIEnv* env) {
    env->DeleteGlobalRef(Class_Boolean);
    env->DeleteGlobalRef(Class_Byte);
    env->DeleteGlobalRef(Class_Char);
    env->DeleteGlobalRef(Class_Short);
    env->DeleteGlobalRef(Class_Int);
    env->DeleteGlobalRef(Class_Long);
    env->DeleteGlobalRef(Class_Float);
    env->DeleteGlobalRef(Class_Double);
    env->DeleteGlobalRef(Class_Void);

    env->DeleteGlobalRef(Class_Class);
    env->DeleteGlobalRef(Class_Executable);
    env->DeleteGlobalRef(Class_Method);
    env->DeleteGlobalRef(Class_Throwable);
}

}  // namespace xposedkit