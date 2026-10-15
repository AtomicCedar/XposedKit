module;

#include "jni.h"

#if defined(__LP64__)
#define SIZE_T_MANGLE "m"
#else
#define SIZE_T_MANGLE "j"
#endif

export module xposedkit:clazz;

import :art_method;
import :symbol_resolver;
import :object;

namespace xposedkit::art::mirror {

    enum class PointerSize : size_t;

    export class Class : public Object {

    private:
        inline static auto FindClassInitializer_ = [] {
            if (auto fn = SymbolResolver::Find<ArtMethod* (*)(Class*, PointerSize)>("_ZN3art6mirror5Class20FindClassInitializerENS_11PointerSizeE")) {
                // Android8及以上
                return reinterpret_cast<ArtMethod* (*)(Class*, size_t)>(fn);
            }

            // Android7及以下
            return SymbolResolver::Find<ArtMethod* (*)(Class*, size_t)>("_ZN3art6mirror5Class20FindClassInitializerE" SIZE_T_MANGLE);
        }();

    public:
        ArtMethod* FindClassInitializer() {
            if (FindClassInitializer_ == nullptr) {
                return nullptr;
            }

            return FindClassInitializer_(this, sizeof(void*));
        }

    };

}