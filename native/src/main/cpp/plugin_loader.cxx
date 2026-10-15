module;

#include "xdl.h"

#include <mutex>
#include <string>
#include <vector>

export module xposedkit:plugin_loader;

namespace xposedkit {

export class PluginLoader {

private:
    inline static std::mutex mutex_;
    inline static std::vector<std::string> loaded_;

public:
    // Loads an ART plugin such as libopenjdkjvmti.so.
    //
    // art::Runtime::EnsurePluginLoaded() cannot be relied on: recent ART builds put
    // the art namespace into hidden visibility, so the symbol is gone from
    // libart.so's dynamic symbol table and resolving it yields a null pointer.
    //
    // A plain dlopen() does not work either: the plugin lives in the ART APEX, which
    // is not part of an app's linker namespace. The ART namespace itself is
    // exported, though, so ask xdl to force the load with a caller address inside
    // libart.so. The linker picks the namespace from that caller, which resolves the
    // plugin in the com_android_art namespace - exactly how ART loads it.
    static bool Load(const char* library) {
        if (library == nullptr) {
            return false;
        }

        // Hot reload re-runs NativeBridge.Init() in the same process and the framework
        // never unloads this library, so a plugin that is already initialized must not
        // be passed to ArtPlugin_Initialize a second time.
        std::lock_guard lock(mutex_);
        for (const auto& loaded : loaded_) {
            if (loaded == library) {
                return true;
            }
        }

        void* handle = xdl_open(library, XDL_ALWAYS_FORCE_LOAD);
        if (handle == nullptr) {
            return false;
        }

        auto initialize = reinterpret_cast<bool (*)()>(xdl_sym(handle, "ArtPlugin_Initialize", nullptr));
        if (initialize == nullptr) {
            xdl_close(handle);
            return false;
        }

        if (!initialize()) {
            xdl_close(handle);
            return false;
        }

        // Keep the plugin loaded: it is process global and stays alive with the runtime.
        loaded_.emplace_back(library);
        return true;
    }

};

}
