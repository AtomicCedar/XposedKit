module;

#include <cstdint>

export module xposedkit:scoped_thread_state_change;

import :symbol_resolver;
import :thread;

namespace xposedkit::art {

export class ScopedObjectAccess {
private:
    // Android13及以上
    inline static auto ArtJniMethodStart_ = SymbolResolver::Find<void (*)(Thread*)>("artJniMethodStart");

    // Android12及以下
    inline static auto JniMethodStart_ = SymbolResolver::Find<uint32_t (*)(Thread*)>("_ZN3art14JniMethodStartEPNS_6ThreadE");

    // Android13及以上
    inline static auto ArtJniMethodEnd_ = SymbolResolver::Find<void (*)(Thread*)>("artJniMethodEnd");

    // Android12及以下
    inline static auto GoToRunnable_ = SymbolResolver::Find<void (*)(Thread*)>("_ZN3artL12GoToRunnableEPNS_6ThreadE");

    Thread* const self_;

public:
    explicit ScopedObjectAccess(Thread* self) : self_(self) {
        if (ArtJniMethodEnd_) {
            ArtJniMethodEnd_(self_);
            return;
        }

        if (GoToRunnable_) {
            GoToRunnable_(self_);
            return;
        }
    }

    ~ScopedObjectAccess() {
        if (ArtJniMethodStart_) {
            ArtJniMethodStart_(self_);
            return;
        }

        if (JniMethodStart_) {
            JniMethodStart_(self_);
            return;
        }
    }
};

}  // namespace xposedkit::art
