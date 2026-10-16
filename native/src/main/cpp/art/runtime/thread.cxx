module;

#include "jni.h"

export module xposedkit:thread;

import :object;
import :symbol_resolver;

namespace xposedkit::art {

    export class Thread {

    private:
        inline static auto CurrentFromGdb_ = SymbolResolver::Find<Thread* (*)()>("_ZN3art6Thread14CurrentFromGdbEv");

        inline static auto DecodeGlobalJObject_ = [] {
            using Fn = mirror::Object* (*)(Thread*, jobject);

            if (auto fn = SymbolResolver::Find<Fn>("_ZNK3art6Thread19DecodeGlobalJObjectEP8_jobject")) {
                // Android14及以上
                return fn;
            }

            // Android13及以下
            return SymbolResolver::Find<Fn>("_ZNK3art6Thread13DecodeJObjectEP8_jobject");
        }();

    public:
        inline static Thread* Current() {
            if (!CurrentFromGdb_) {
                return nullptr;
            }

            return CurrentFromGdb_();
        }

        mirror::Object* DecodeGlobalJObject(jobject obj) {
            if (!DecodeGlobalJObject_) {
                return nullptr;
            }

            return DecodeGlobalJObject_(this, obj);
        }

    };

}
