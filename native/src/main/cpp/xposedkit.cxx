module;

#include "jni.h"
#include "xdl.h"

#include <algorithm>
#include <cstdint>
#include <cstring>
#include <limits>
#include <new>
#include <string>
#include <unordered_map>
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

static jlong JNICALL CreateXmlParseState(JNIEnv* env, jclass,
                                         jstring entries) {
    if (!entries) {
        env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"), "entries is null");
        return 0;
    }

    // Android's XmlBlock.Parser stores a ResXMLParser* and invokes
    // nativeDestroyParseState() on close. ResXMLTree derives from
    // ResXMLParser, so a tree over an in-memory AXML document is a suitable
    // parse state without ever opening an XML file.
    struct Entry {
        std::string ns;
        std::string name;
        std::string raw;
        bool has_ns = false;
        bool has_raw = false;
        uint32_t name_res_id = 0;
        uint32_t resource_value = 0;
        int32_t typed_value_type = -1;
    };
    auto throw_error = [&](const char* type, const char* message) {
        auto clazz = env->FindClass(type);
        if (clazz) env->ThrowNew(clazz, message);
    };
    auto java_string = [&](jstring value) -> std::string {
        if (!value) return {};
        const jchar* chars = env->GetStringChars(value, nullptr);
        if (!chars) return {};
        const jsize length = env->GetStringLength(value);
        std::string result;
        result.reserve(static_cast<size_t>(length) * 3);
        for (jsize i = 0; i < length; ++i) {
            uint32_t cp = chars[i];
            if (cp >= 0xd800 && cp <= 0xdbff && i + 1 < length &&
                chars[i + 1] >= 0xdc00 && chars[i + 1] <= 0xdfff) {
                cp = 0x10000 + ((cp - 0xd800) << 10) + (chars[++i] - 0xdc00);
            }
            if (cp <= 0x7f) result.push_back(static_cast<char>(cp));
            else if (cp <= 0x7ff) {
                result.push_back(static_cast<char>(0xc0 | (cp >> 6)));
                result.push_back(static_cast<char>(0x80 | (cp & 0x3f)));
            } else if (cp <= 0xffff) {
                result.push_back(static_cast<char>(0xe0 | (cp >> 12)));
                result.push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3f)));
                result.push_back(static_cast<char>(0x80 | (cp & 0x3f)));
            } else {
                result.push_back(static_cast<char>(0xf0 | (cp >> 18)));
                result.push_back(static_cast<char>(0x80 | ((cp >> 12) & 0x3f)));
                result.push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3f)));
                result.push_back(static_cast<char>(0x80 | (cp & 0x3f)));
            }
        }
        env->ReleaseStringChars(value, chars);
        return result;
    };

    auto json_class = env->FindClass("org/json/JSONArray");
    auto object_class = env->FindClass("org/json/JSONObject");
    if (!json_class || !object_class) return 0L;
    auto json_ctor = env->GetMethodID(json_class, "<init>", "(Ljava/lang/String;)V");
    auto length_method = env->GetMethodID(json_class, "length", "()I");
    auto get_object = env->GetMethodID(json_class, "getJSONObject", "(I)Lorg/json/JSONObject;");
    auto get_string = env->GetMethodID(object_class, "getString", "(Ljava/lang/String;)Ljava/lang/String;");
    auto get_int = env->GetMethodID(object_class, "getInt", "(Ljava/lang/String;)I");
    auto is_null = env->GetMethodID(object_class, "isNull", "(Ljava/lang/String;)Z");
    if (!json_ctor || !length_method || !get_object || !get_string || !get_int || !is_null) return 0L;
    auto array = env->NewObject(json_class, json_ctor, entries);
    if (!array || env->ExceptionCheck()) return 0L;
    const jsize count = env->CallIntMethod(array, length_method);
    if (env->ExceptionCheck()) return 0L;

    auto key = [&](const char* value) { return env->NewStringUTF(value); };
    std::vector<Entry> parsed;
    parsed.reserve(static_cast<size_t>(count));
    for (jsize i = 0; i < count; ++i) {
        auto item = env->CallObjectMethod(array, get_object, i);
        if (env->ExceptionCheck() || !item) return 0L;
        Entry entry;
        auto ns_key = key("namespace");
        auto name_key = key("name");
        auto name_res_key = key("nameResId");
        auto raw_key = key("rawValue");
        auto resource_key = key("resourceValue");
        auto value_type_key = key("valueType");
        entry.has_ns = !env->CallBooleanMethod(item, is_null, ns_key);
        if (entry.has_ns) {
            auto value = static_cast<jstring>(env->CallObjectMethod(item, get_string, ns_key));
            entry.ns = java_string(value);
            if (value) env->DeleteLocalRef(value);
        }
        auto name_value = static_cast<jstring>(env->CallObjectMethod(item, get_string, name_key));
        entry.name = java_string(name_value);
        if (name_value) env->DeleteLocalRef(name_value);
        entry.has_raw = !env->CallBooleanMethod(item, is_null, raw_key);
        if (entry.has_raw) {
            auto value = static_cast<jstring>(env->CallObjectMethod(item, get_string, raw_key));
            entry.raw = java_string(value);
            if (value) env->DeleteLocalRef(value);
        }
        entry.name_res_id = static_cast<uint32_t>(env->CallIntMethod(item, get_int, name_res_key));
        entry.resource_value = static_cast<uint32_t>(env->CallIntMethod(item, get_int, resource_key));
        if (!env->CallBooleanMethod(item, is_null, value_type_key)) {
            entry.typed_value_type = env->CallIntMethod(item, get_int, value_type_key);
        }
        env->DeleteLocalRef(ns_key); env->DeleteLocalRef(name_key);
        env->DeleteLocalRef(raw_key); env->DeleteLocalRef(name_res_key);
        env->DeleteLocalRef(resource_key); env->DeleteLocalRef(value_type_key);
        env->DeleteLocalRef(item);
        if (env->ExceptionCheck()) return 0L;
        if (entry.name.empty()) {
            throw_error("java/lang/IllegalArgumentException", "attribute name must not be empty");
            return 0L;
        }
        parsed.push_back(std::move(entry));
    }
    env->DeleteLocalRef(array);
    env->DeleteLocalRef(json_class);
    env->DeleteLocalRef(object_class);
    if (parsed.size() > std::numeric_limits<uint16_t>::max()) {
        throw_error("java/lang/IllegalArgumentException", "too many attributes");
        return 0L;
    }
    // AssetManager's RetrieveAttributes walks XML attributes and styleable IDs
    // in ascending resource-ID order; aapt emits the same ordering.
    std::stable_sort(parsed.begin(), parsed.end(), [](const Entry& left, const Entry& right) {
        return left.name_res_id < right.name_res_id;
    });

    // Build an Android binary XML document. String indexes are shared by
    // namespace URIs, names, and raw values just like an aapt-produced file.
    constexpr uint32_t NO_INDEX = 0xffffffffu;
    std::vector<std::string> strings;
    std::unordered_map<std::string, uint32_t> string_ids;
    auto intern = [&](const std::string& value) -> uint32_t {
        auto it = string_ids.find(value);
        if (it != string_ids.end()) return it->second;
        const uint32_t id = static_cast<uint32_t>(strings.size());
        strings.push_back(value);
        string_ids.emplace(value, id);
        return id;
    };
    std::vector<uint32_t> ns_ids, name_ids, raw_ids;
    const uint32_t root_name_id = intern("runtime");
    for (const auto& entry : parsed) {
        ns_ids.push_back(entry.has_ns ? intern(entry.ns) : NO_INDEX);
        name_ids.push_back(intern(entry.name));
        raw_ids.push_back(entry.has_raw ? intern(entry.raw) : NO_INDEX);
    }
    auto u16 = [](std::vector<uint8_t>& out, uint16_t v) {
        out.push_back(static_cast<uint8_t>(v)); out.push_back(static_cast<uint8_t>(v >> 8));
    };
    auto u32 = [](std::vector<uint8_t>& out, uint32_t v) {
        out.push_back(static_cast<uint8_t>(v)); out.push_back(static_cast<uint8_t>(v >> 8));
        out.push_back(static_cast<uint8_t>(v >> 16)); out.push_back(static_cast<uint8_t>(v >> 24));
    };
    auto patch32 = [](std::vector<uint8_t>& out, size_t at, uint32_t v) {
        for (int b = 0; b != 4; ++b) out[at + b] = static_cast<uint8_t>(v >> (b * 8));
    };
    auto utf8_len = [](const std::string& value) {
        size_t n = 0;
        for (unsigned char c : value) if ((c & 0xc0) != 0x80) ++n;
        return n;
    };
    auto put_len8 = [&](std::vector<uint8_t>& out, size_t n) {
        if (n > 0x7f) { out.push_back(static_cast<uint8_t>((n >> 8) | 0x80)); out.push_back(static_cast<uint8_t>(n)); }
        else out.push_back(static_cast<uint8_t>(n));
    };
    std::vector<uint8_t> xml;
    // XML document header (filled after chunks are appended).
    u16(xml, 0x0003); u16(xml, 8); u32(xml, 0);
    // UTF-8 string pool.
    const size_t pool_start = xml.size();
    u16(xml, 0x0001); u16(xml, 28); u32(xml, 0);
    u32(xml, static_cast<uint32_t>(strings.size())); u32(xml, 0); u32(xml, 0x100);
    const uint32_t strings_start = 28 + static_cast<uint32_t>(strings.size() * 4);
    u32(xml, strings_start); u32(xml, 0);
    std::vector<uint8_t> encoded_strings;
    for (const auto& value : strings) {
        size_t utf16_units = 0;
        for (unsigned char c : value) if ((c & 0xc0) != 0x80) utf16_units += c >= 0xf0 ? 2 : 1;
        u32(xml, static_cast<uint32_t>(encoded_strings.size()));
        put_len8(encoded_strings, utf16_units); put_len8(encoded_strings, utf8_len(value));
        encoded_strings.insert(encoded_strings.end(), value.begin(), value.end()); encoded_strings.push_back(0);
    }
    xml.insert(xml.end(), encoded_strings.begin(), encoded_strings.end());
    while (xml.size() & 3) xml.push_back(0);
    patch32(xml, pool_start + 4, static_cast<uint32_t>(xml.size() - pool_start));
    // Resource map: one entry per string, attribute-name indexes carry IDs.
    bool has_resource_ids = false;
    for (const auto& e : parsed) has_resource_ids |= e.name_res_id != 0;
    if (has_resource_ids) {
        u16(xml, 0x0180); u16(xml, 8); u32(xml, 8 + static_cast<uint32_t>(strings.size() * 4));
        std::vector<uint32_t> ids(strings.size(), 0);
        for (size_t i = 0; i < parsed.size(); ++i) ids[name_ids[i]] = parsed[i].name_res_id;
        for (uint32_t id : ids) u32(xml, id);
    }
    // Root START_TAG carrying the requested attributes.
    size_t tag_start = xml.size();
    u16(xml, 0x0102); u16(xml, 16); u32(xml, 36 + static_cast<uint32_t>(parsed.size() * 20));
    u32(xml, 1); u32(xml, NO_INDEX);
    u32(xml, NO_INDEX); u32(xml, root_name_id);
    u16(xml, 20); u16(xml, 20); u16(xml, static_cast<uint16_t>(parsed.size()));
    u16(xml, 0); u16(xml, 0); u16(xml, 0);
    for (size_t i = 0; i < parsed.size(); ++i) {
        const auto& e = parsed[i];
        u32(xml, ns_ids[i]); u32(xml, name_ids[i]); u32(xml, raw_ids[i]);
        u16(xml, 8); xml.push_back(0);
        if (e.typed_value_type >= 0) {
            xml.push_back(static_cast<uint8_t>(e.typed_value_type));
            u32(xml, e.typed_value_type == 3 && e.has_raw ? raw_ids[i] : e.resource_value);
        }
        else if (e.resource_value != 0) { xml.push_back(1); u32(xml, e.resource_value); }
        else if (e.has_raw) { xml.push_back(3); u32(xml, raw_ids[i]); }
        else { xml.push_back(0); u32(xml, 0); }
    }
    patch32(xml, tag_start + 4, static_cast<uint32_t>(xml.size() - tag_start));
    // END_TAG lets callers advance the parser normally after reading attributes.
    u16(xml, 0x0103); u16(xml, 16); u32(xml, 24); u32(xml, 1); u32(xml, NO_INDEX);
    u32(xml, NO_INDEX); u32(xml, root_name_id);
    patch32(xml, 4, static_cast<uint32_t>(xml.size()));

    // xDL can inspect mapped libraries even when the app's linker namespace
    // blocks dlopen("libandroidfw.so"). The oversized allocation intentionally
    // contains both the tree object and its non-owned XML bytes, so Android's
    // `delete ResXMLParser*` releases the allocation when Parser.close() runs.
    void* fw = xdl_open("libandroidfw.so", XDL_DEFAULT);
    if (!fw) {
        throw_error("java/lang/UnsatisfiedLinkError", "Unable to locate libandroidfw.so in the process");
        return 0L;
    }
    using TreeCtor = void (*)(void*);
    using SetTo = int32_t (*)(void*, const void*, size_t, bool);
    auto ctor = reinterpret_cast<TreeCtor>(xdl_sym(fw, "_ZN7android10ResXMLTreeC1Ev", nullptr));
    auto set_to = reinterpret_cast<SetTo>(xdl_sym(fw, "_ZN7android10ResXMLTree5setToEPKvmb", nullptr));
    xdl_close(fw);
    if (!ctor || !set_to) {
        throw_error("java/lang/UnsatisfiedLinkError", "Required ResXMLTree symbols are unavailable");
        return 0L;
    }
    constexpr size_t kBufferOffset = 4096;
    if (xml.size() > std::numeric_limits<size_t>::max() - kBufferOffset) {
        throw_error("java/lang/OutOfMemoryError", "Generated binary XML is too large");
        return 0L;
    }
    void* storage = ::operator new(kBufferOffset + xml.size(), std::nothrow);
    if (!storage) {
        throw_error("java/lang/OutOfMemoryError", "Unable to allocate binary XML state");
        return 0L;
    }
    std::memcpy(static_cast<uint8_t*>(storage) + kBufferOffset, xml.data(), xml.size());
    ctor(storage);
    const int32_t status = set_to(storage, static_cast<uint8_t*>(storage) + kBufferOffset, xml.size(), false);
    if (status != 0) {
        ::operator delete(storage);
        throw_error("java/lang/IllegalArgumentException", "Generated binary XML was rejected by androidfw");
        return 0L;
    }
    return reinterpret_cast<jlong>(storage);
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
            {"Init",
             "()V",
             reinterpret_cast<void*>(Init)},
            {"Cleanup",
             "()V",
             reinterpret_cast<void*>(Cleanup)},
            {"AllocObject",
             "(Ljava/lang/Class;)Ljava/lang/Object;",
             reinterpret_cast<void*>(AllocObject)},
            {"CallNonvirtualMethod",
             "(Ljava/lang/reflect/Executable;Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;",
             reinterpret_cast<void*>(CallNonvirtualMethod)},
            {"CreateXmlParseState",
             "(Ljava/lang/String;)J",
             reinterpret_cast<void*>(CreateXmlParseState)},
            {"FindClassInitializer",
             "(Ljava/lang/Class;)Ljava/lang/reflect/Member;",
             reinterpret_cast<void*>(FindClassInitializer)},
            {"VisitHeapObjects",
             "(Ljava/lang/Class;Z)[Ljava/lang/Object;",
             reinterpret_cast<void*>(VisitHeapObjects)},
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

}