module;

#include <cstdint>

#if defined(__LP64__)
#define SIZE_T_MANGLE "m"
#else
#define SIZE_T_MANGLE "j"
#endif

export module xposedkit:clazz;

import :art_method;
import :object;
import :symbol_resolver;

namespace xposedkit::art::mirror {

enum class PointerSize : size_t;

export class Class : public Object {
private:
    inline static auto FindClassInitializer_ = [] {
        using Fn = ArtMethod* (*)(Class*, size_t);

        if (const auto fn = SymbolResolver::Find<ArtMethod* (*)(Class*, PointerSize)>("_ZN3art6mirror5Class20FindClassInitializerENS_11PointerSizeE")) {
            // Android8及以上
            return reinterpret_cast<Fn>(fn);
        }

        // Android7及以下
        return SymbolResolver::Find<Fn>("_ZN3art6mirror5Class20FindClassInitializerE" SIZE_T_MANGLE);
    }();

public:
    ArtMethod* FindClassInitializer() {
        if (!FindClassInitializer_) {
            return nullptr;
        }

        return FindClassInitializer_(this, sizeof(void*));
    }
};

}  // namespace xposedkit::art::mirror