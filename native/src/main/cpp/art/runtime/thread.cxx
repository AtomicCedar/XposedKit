module;

#include <jni.h>

export module xposedkit:thread;

import :object;
import :symbol_resolver;

namespace xposedkit::art {

    export class Thread {

    private:
        using DecodeJObjectFn = mirror::Object* (*)(Thread*, jobject);

        inline static auto CurrentFromGdb_ = SymbolResolver::Find<Thread* (*)()>("_ZN3art6Thread14CurrentFromGdbEv");

        inline static auto DecodeJObject_ =
                SymbolResolver::Find<DecodeJObjectFn>("_ZNK3art6Thread13DecodeJObjectEP8_jobject");

        // Android 17 no longer exports DecodeJObject and provides DecodeGlobalJObject instead.
        inline static auto DecodeGlobalJObject_ =
                SymbolResolver::Find<DecodeJObjectFn>("_ZNK3art6Thread19DecodeGlobalJObjectEP8_jobject");

    public:
        inline static Thread* Current() {
            if (CurrentFromGdb_ == nullptr) {
                return nullptr;
            }

            return CurrentFromGdb_();
        }

        // DecodeGlobalJObject only accepts a global reference, passing a local one crashes.
        // DecodeJObject accepts both, so callers only need to build a global reference when
        // this returns true and can keep passing the original reference otherwise.
        inline static bool RequiresGlobalReference() {
            return DecodeJObject_ == nullptr;
        }

        mirror::Object* DecodeJObject(jobject obj) {
            auto decode = DecodeJObject_ != nullptr ? DecodeJObject_ : DecodeGlobalJObject_;
            if (decode == nullptr) {
                return nullptr;
            }

            return decode(this, obj);
        }

    };

}
