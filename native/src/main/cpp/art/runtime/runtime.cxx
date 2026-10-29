module;

export module xposedkit:runtime;

import :symbol_resolver;

namespace xposedkit::art {

export class Runtime {
private:
    inline static auto Current_ = SymbolResolver::Find<Runtime**>("_ZN3art7Runtime9instance_E");

public:
    inline static Runtime* Current() {
        if (!Current_) {
            return nullptr;
        }

        return *Current_;
    }
};

}  // namespace xposedkit::art
