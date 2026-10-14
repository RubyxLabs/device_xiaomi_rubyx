// SPDX-License-Identifier: Apache-2.0
#define LOG_TAG "RubyFaceCredential"
#include <android/hardware/keymaster/4.0/IKeymasterDevice.h>
#include <android/native_window_jni.h>
#include <jni.h>
#include <log/log.h>

#include <cstdint>
#include <vector>

namespace {
namespace km = android::hardware::keymaster::V4_0;
using android::hardware::hidl_vec;

km::KeyParameter integer(km::Tag tag, uint32_t value) {
    km::KeyParameter parameter{};
    parameter.tag = tag;
    parameter.f.integer = value;
    return parameter;
}

bool hardwareEnforces(const km::KeyCharacteristics& characteristics, km::Tag tag, uint64_t value) {
    for (const auto& parameter : characteristics.hardwareEnforced) {
        if (parameter.tag == tag) {
            if (tag == km::Tag::USER_SECURE_ID) return parameter.f.longInteger == value;
            return parameter.f.integer == value;
        }
    }
    return false;
}
} // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_com_xiaomi_face_PreviewSurface_configure(JNIEnv* env, jclass, jobject surface,
        jint width, jint height) {
    if (surface == nullptr || width <= 0 || height <= 0) return false;
    ANativeWindow* window = ANativeWindow_fromSurface(env, surface);
    if (window == nullptr) return false;
    const int status = ANativeWindow_setBuffersGeometry(window, width, height, 0);
    ANativeWindow_release(window);
    return status == 0;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_xiaomi_face_CredentialVerifier_verifyNative(JNIEnv* env, jclass, jlong challenge,
        jlong userId, jlong authenticatorId, jint authenticatorType, jlong timestamp,
        jbyteArray mac) {
    if (mac == nullptr || env->GetArrayLength(mac) != 32 || userId == 0 || timestamp < 0 ||
            (authenticatorType != 1 && authenticatorType != 2)) return false;
    auto device = km::IKeymasterDevice::getService();
    if (device == nullptr) return false;
    km::SecurityLevel level = km::SecurityLevel::SOFTWARE;
    auto information = device->getHardwareInfo([&](auto securityLevel, const auto&, const auto&) {
        level = securityLevel;
    });
    if (!information.isOk() || level != km::SecurityLevel::TRUSTED_ENVIRONMENT) return false;

    km::HardwareAuthToken token{};
    token.challenge = challenge;
    token.userId = userId;
    token.authenticatorId = authenticatorId;
    token.authenticatorType = static_cast<km::HardwareAuthenticatorType>(authenticatorType);
    token.timestamp = timestamp;
    token.mac.resize(32);
    env->GetByteArrayRegion(mac, 0, 32, reinterpret_cast<jbyte*>(token.mac.data()));
    if (env->ExceptionCheck()) return false;

    // Have TEE Keymaster validate this exact HAT. An ephemeral, authentication-bound
    // HMAC key avoids relying on a cached software assertion of credential success.
    std::vector<km::KeyParameter> parameters = {
        integer(km::Tag::ALGORITHM, static_cast<uint32_t>(km::Algorithm::HMAC)),
        integer(km::Tag::KEY_SIZE, 256),
        integer(km::Tag::PURPOSE, static_cast<uint32_t>(km::KeyPurpose::SIGN)),
        integer(km::Tag::DIGEST, static_cast<uint32_t>(km::Digest::SHA_2_256)),
        integer(km::Tag::MIN_MAC_LENGTH, 256),
        integer(km::Tag::USER_AUTH_TYPE, authenticatorType),
        integer(km::Tag::AUTH_TIMEOUT, 300),
    };
    km::KeyParameter sid{};
    sid.tag = km::Tag::USER_SECURE_ID;
    sid.f.longInteger = userId;
    parameters.push_back(sid);
    hidl_vec<uint8_t> key;
    km::ErrorCode error = km::ErrorCode::UNKNOWN_ERROR;
    bool enforced = false;
    auto generated = device->generateKey(parameters, [&](auto status, const auto& blob,
            const auto& characteristics) {
        error = status;
        key = blob;
        enforced = hardwareEnforces(characteristics, km::Tag::USER_SECURE_ID, userId) &&
                hardwareEnforces(characteristics, km::Tag::USER_AUTH_TYPE, authenticatorType) &&
                hardwareEnforces(characteristics, km::Tag::AUTH_TIMEOUT, 300);
    });
    if (!generated.isOk() || error != km::ErrorCode::OK || key.size() == 0) {
        ALOGW("TEE credential key generation failed (status %d)", static_cast<int>(error));
        if (key.size() != 0) device->deleteKey(key).isOk();
        return false;
    }
    bool verified = false;
    if (enforced) {
        std::vector<km::KeyParameter> operation = {
            integer(km::Tag::DIGEST, static_cast<uint32_t>(km::Digest::SHA_2_256)),
            integer(km::Tag::MAC_LENGTH, 256),
        };
        uint64_t handle = 0;
        error = km::ErrorCode::UNKNOWN_ERROR;
        auto begun = device->begin(km::KeyPurpose::SIGN, key, operation, token,
                [&](auto status, const auto&, auto operationHandle) {
            error = status;
            handle = operationHandle;
        });
        verified = begun.isOk() && error == km::ErrorCode::OK && handle != 0;
        if (!verified) {
            ALOGW("TEE credential authorization failed (status %d)", static_cast<int>(error));
        }
        if (handle != 0) {
            auto aborted = device->abort(handle);
            if (!aborted.isOk()) {
                ALOGW("TEE credential operation abort transport failed");
                verified = false;
            } else if (aborted != km::ErrorCode::OK) {
                ALOGW("TEE credential operation abort failed (status %d)",
                        static_cast<int>(static_cast<km::ErrorCode>(aborted)));
                verified = false;
            }
        }
    } else {
        ALOGW("TEE did not enforce the credential key authentication requirements");
    }
    auto deleted = device->deleteKey(key);
    // This ephemeral key is never persisted and does not request rollback resistance.
    // Beanpod returns UNIMPLEMENTED for deleteKey(), which Keymaster permits for
    // such keys. Cleanup must not turn a successful TEE HAT check into rejection.
    if (!deleted.isOk()) {
        ALOGW("TEE credential key deletion transport failed");
        verified = false;
    } else if (deleted != km::ErrorCode::OK && deleted != km::ErrorCode::UNIMPLEMENTED) {
        ALOGW("TEE credential key deletion failed (status %d)",
                static_cast<int>(static_cast<km::ErrorCode>(deleted)));
        verified = false;
    }
    return verified;
}
