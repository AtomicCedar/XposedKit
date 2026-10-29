module;

#include <xdl.h>
#include <string_view>

export module xposedkit:symbol_resolver;

namespace xposedkit::art {

export class SymbolResolver {
private:
    inline static void* handle = nullptr;

    static bool EnsureInitialized() {
        if (handle) return true;
        handle = xdl_open("libart.so", XDL_DEFAULT);
        return handle != nullptr;
    }

public:
    template <typename T>
    static T Find(const std::string_view symbol) {
        if (!EnsureInitialized()) {
            throw std::runtime_error("Failed to open libart.so");
        }

        return reinterpret_cast<T>(xdl_sym(handle, symbol.data(), nullptr));
    }
};

}  // namespace xposedkit::art