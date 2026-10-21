module;

#include "jni.h"

#include <vector>

export module xposedkit:heap;

import :clazz;
import :handle;
import :object;
import :symbol_resolver;

namespace xposedkit::art::gc {

    export class Heap {

    private:
        using ObjectCallback = void (*)(mirror::Object*, void*);

        // Android8及以下
        inline static auto VisitObjects_ = SymbolResolver::Find<void (*)(Heap*, ObjectCallback, void*)>("_ZN3art2gc4Heap12VisitObjectsEPFvPNS_6mirror6ObjectEPvES5_");

        // Android9 ~ Android11
        inline static auto GetInstances_ = SymbolResolver::Find<void (*)(Heap*, VariableSizedHandleScope&, Handle<mirror::Class>, bool, int32_t, std::vector<Handle<mirror::Object>>&)>("_ZN3art2gc4Heap12GetInstancesERNS_24VariableSizedHandleScopeENS_6HandleINS_6mirror5ClassEEEbiRNSt3__16vectorINS4_INS5_6ObjectEEENS8_9allocatorISB_EEEE");

        // Android12及以上

    public:
        void VisitObjects(ObjectCallback callback, void* arg) {
            if (!VisitObjects_) {
                return;
            }

            VisitObjects_(this, callback, arg);
        }

        void GetInstances(VariableSizedHandleScope& scope,
                          Handle<mirror::Class> c,
                          bool use_is_assignable_from,
                          int32_t max_count,
                          std::vector<Handle<mirror::Object>>& instances) {
            if (!GetInstances_) {
                return;
            }

            GetInstances_(this, scope, c, use_is_assignable_from, max_count, instances);
        }

    };

}
