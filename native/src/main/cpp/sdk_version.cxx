module;

#include "jni.h"
#include "sys/system_properties.h"

#include <array>
#include <cstdlib>

export module xposedkit:sdk_version;

namespace xposedkit {

constexpr int kSdkN = 24;
constexpr int kSdkNMr1 = 25;
constexpr int kSdkO = 26;
constexpr int kSdkOMr1 = 27;
constexpr int kSdkP = 28;
constexpr int kSdkQ = 29;
constexpr int kSdkR = 30;
constexpr int kSdkS = 31;
constexpr int kSdkSV2 = 32;
constexpr int kSdkTiramisu = 33;
constexpr int kSdkUpsideDownCake = 34;
constexpr int kSdkVanillaIceCream = 35;
constexpr int kSdkBaklava = 36;
constexpr int kSdkCinnamonBun = 37;

auto GetAndroidSdkVersion() {
    static auto version = [] {
        std::array<char, PROP_VALUE_MAX> prop_value;
        __system_property_get("ro.build.version.sdk", prop_value.data());
        return std::atoi(prop_value.data());
    }();
    return version;
}

}