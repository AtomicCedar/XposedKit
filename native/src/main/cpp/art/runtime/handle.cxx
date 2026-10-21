module;

#include "jni.h"

#include <cstddef>
#include <cstdint>
#include <cstring>
#include <new>

export module xposedkit:handle;

import :thread;

namespace xposedkit::art {

    export template<class T> class Handle {

    private:
        void* reference_;

    public:
        explicit Handle(void* reference = nullptr) : reference_(reference) {}

        jobject ToJObject() const {
            return reinterpret_cast<jobject>(reference_);
        }

        operator jobject() const {
            return ToJObject();
        }
    };

    // ABI-compatible storage for ART's Android 9-11
    // VariableSizedHandleScope. ART's NewHandle() template is inline, so we
    // initialize the documented fields directly instead of needing its type
    // definition or constructor symbol.
    export class VariableSizedHandleScope {

    private:
        void* link_ = nullptr;
        int32_t numberOfReferences_ = -1;
        Thread* self_ = nullptr;
        void* currentScope_ = nullptr;
        void** topHandleScope_ = nullptr;
        void* firstScope_ = nullptr;

        static constexpr size_t kFixedScopeSize = 64;

    public:
        bool Initialize(Thread* self, JNIEnv* env) {
            if (!self || !env) {
                return false;
            }

            const size_t pointerSize = sizeof(void*);
            size_t jniEnvOffset = 256;
            for (size_t offset = 144; offset < 256; offset += pointerSize) {
                auto value = *reinterpret_cast<void**>(reinterpret_cast<uint8_t*>(self) + offset);
                if (value == reinterpret_cast<void*>(env)) {
                    jniEnvOffset = offset;
                    break;
                }
            }
            if (jniEnvOffset == 256) {
                return false;
            }

            topHandleScope_ = reinterpret_cast<void**>(reinterpret_cast<uint8_t*>(self) +
                                                       jniEnvOffset + 10 * pointerSize);
            link_ = *topHandleScope_;
            numberOfReferences_ = -1;
            self_ = self;

            firstScope_ = ::operator new(kFixedScopeSize, std::nothrow);
            if (!firstScope_) {
                topHandleScope_ = nullptr;
                return false;
            }

            std::memset(firstScope_, 0, kFixedScopeSize);
            const size_t baseSize = pointerSize + sizeof(int32_t);
            const size_t referenceCount = (kFixedScopeSize - pointerSize - 8) / sizeof(uint32_t);
            *reinterpret_cast<int32_t*>(reinterpret_cast<uint8_t*>(firstScope_) + pointerSize) =
                    static_cast<int32_t>(referenceCount);
            *reinterpret_cast<uint32_t*>(reinterpret_cast<uint8_t*>(firstScope_) + baseSize) = 0;
            currentScope_ = firstScope_;
            *topHandleScope_ = this;
            return true;
        }

        template<class T>
        Handle<T> NewHandle(void* object) {
            if (!firstScope_ || !object) {
                return Handle<T>();
            }

            auto* scope = static_cast<uint8_t*>(currentScope_);
            const size_t capacityOffset = sizeof(void*);
            const size_t sizeOffset = capacityOffset + sizeof(int32_t);
            const size_t referencesOffset = sizeOffset + sizeof(uint32_t);
            auto* capacity = reinterpret_cast<int32_t*>(scope + capacityOffset);
            auto* size = reinterpret_cast<uint32_t*>(scope + sizeOffset);
            if (scope != firstScope_ || *size >= static_cast<uint32_t>(*capacity)) {
                return Handle<T>();
            }

            auto* reference = reinterpret_cast<uint32_t*>(scope + referencesOffset +
                                                          static_cast<size_t>(*size) * sizeof(uint32_t));
            *reference = static_cast<uint32_t>(reinterpret_cast<uintptr_t>(object));
            ++*size;
            return Handle<T>(reference);
        }

        void Destroy() {
            if (topHandleScope_) {
                *topHandleScope_ = link_;
            }

            auto* scope = static_cast<uint8_t*>(currentScope_);
            while (scope) {
                auto* next = *reinterpret_cast<void**>(scope);
                ::operator delete(scope);
                scope = static_cast<uint8_t*>(next);
            }

            firstScope_ = nullptr;
            currentScope_ = nullptr;
            topHandleScope_ = nullptr;
            self_ = nullptr;
            link_ = nullptr;
        }

        ~VariableSizedHandleScope() {
            Destroy();
        }

    };

}
