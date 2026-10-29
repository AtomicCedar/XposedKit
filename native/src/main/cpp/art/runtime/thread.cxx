module;

#include <jni.h>

export module xposedkit:thread;

import :object;
import :symbol_resolver;

namespace xposedkit::art {

export class Thread {
private:
    inline static auto CurrentFromGdb_ = SymbolResolver::Find<Thread* (*)()>("_ZN3art6Thread14CurrentFromGdbEv");

    inline static auto DecodeJObject_ = SymbolResolver::Find<mirror::Object* (*)(Thread*, jobject)>("_ZNK3art6Thread13DecodeJObjectEP8_jobject");

    inline static auto DecodeGlobalJObject_ = SymbolResolver::Find<mirror::Object* (*)(Thread*, jobject)>("_ZNK3art6Thread19DecodeGlobalJObjectEP8_jobject");

public:
    inline static Thread* Current() {
        if (!CurrentFromGdb_) {
            return nullptr;
        }

        return CurrentFromGdb_();
    }

    mirror::Object* DecodeJObject(JNIEnv* env, jobject obj) {
        if (DecodeJObject_) {
            return DecodeJObject_(this, obj);
        }

        if (DecodeGlobalJObject_) {
            auto global = env->NewGlobalRef(obj);
            if (!global) return nullptr;

            auto result = DecodeGlobalJObject_(this, global);
            env->DeleteGlobalRef(global);
            return result;
        }

        return nullptr;
    }
};

}  // namespace xposedkit::art
