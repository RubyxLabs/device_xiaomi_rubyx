#include "common/CameraProviderExtension.h"

#include <errno.h>
#include <fcntl.h>
#include <stdint.h>
#include <string.h>
#include <sys/ioctl.h>
#include <unistd.h>

#include <log/log.h>

namespace {

constexpr const char* kTorchNode = "/dev/flashlight";

struct flashlight_user_arg {
    int type_id;
    int ct_id;
    int arg;
};

#ifndef FLASHLIGHT_MAGIC
#define FLASHLIGHT_MAGIC 'S'
#endif

#define FLASH_IOC_SET_TIME_OUT_TIME_MS _IOR(FLASHLIGHT_MAGIC, 100, int)
#define FLASH_IOC_SET_DUTY _IOR(FLASHLIGHT_MAGIC, 110, int)
#define FLASH_IOC_SET_ONOFF _IOR(FLASHLIGHT_MAGIC, 115, int)

#define FLASH_IOC_GET_MAX_TORCH_DUTY _IOWR(FLASHLIGHT_MAGIC, 230, int)
#define FLASH_IOC_GET_CURRENT_TORCH_DUTY _IOR(FLASHLIGHT_MAGIC, 232, int)

struct TorchDeviceKey {
    int typeId;
    int ctId;
};

TorchDeviceKey& cachedTorchKey() {
    static TorchDeviceKey key{-1, -1};
    return key;
}

int doTorchIoctl(unsigned int cmd, int32_t* inout, const TorchDeviceKey& key) {
    int fd = TEMP_FAILURE_RETRY(open(kTorchNode, O_RDWR | O_CLOEXEC));
    if (fd < 0) {
        return -errno;
    }

    flashlight_user_arg arg{};
    // MTK core uses 1-based IDs (see flashlight_get_type_id/flashlight_get_ct_id).
    arg.type_id = key.typeId;
    arg.ct_id = key.ctId;
    arg.arg = (inout != nullptr) ? *inout : 0;

    int ret = TEMP_FAILURE_RETRY(ioctl(fd, cmd, &arg));
    int savedErrno = errno;
    close(fd);

    if (ret < 0) {
        return -savedErrno;
    }
    if (inout != nullptr) {
        *inout = arg.arg;
    }
    return 0;
}

int doTorchIoctl(unsigned int cmd, int32_t* inout) {
    TorchDeviceKey key = cachedTorchKey();
    if (key.typeId <= 0 || key.ctId <= 0) {
        key.typeId = 1;
        key.ctId = 1;
    }
    return doTorchIoctl(cmd, inout, key);
}

bool ensureTorchKey() {
    TorchDeviceKey& key = cachedTorchKey();
    if (key.typeId > 0 && key.ctId > 0) return true;

    for (int typeId = 1; typeId <= 2; typeId++) {
        for (int ctId = 1; ctId <= 2; ctId++) {
            TorchDeviceKey candidate{typeId, ctId};
            int32_t max = 0;
            if (doTorchIoctl(FLASH_IOC_GET_MAX_TORCH_DUTY, &max, candidate) == 0 && max >= 1) {
                key = candidate;
                ALOGI("Using /dev/flashlight torch key typeId=%d ctId=%d (max=%d)",
                      key.typeId, key.ctId, max);
                return true;
            }
        }
    }

    // Fall back to 1/1 (still better than 0/0, which is always invalid).
    key.typeId = 1;
    key.ctId = 1;
    return false;
}

int32_t clampTorchLevel(int32_t level, int32_t maxLevel) {
    if (maxLevel <= 0) return 1;
    if (level <= 0) return 1;
    if (level > maxLevel) return maxLevel;
    return level;
}

} // namespace

bool supportsTorchStrengthControlExt() {
    int fd = TEMP_FAILURE_RETRY(open(kTorchNode, O_RDWR | O_CLOEXEC));
    if (fd < 0) return false;
    close(fd);

    (void)ensureTorchKey();

    int32_t max = 0;
    if (doTorchIoctl(FLASH_IOC_GET_MAX_TORCH_DUTY, &max) != 0) return false;
    return max >= 1;
}

int32_t getTorchDefaultStrengthLevelExt() {
    int32_t max = getTorchMaxStrengthLevelExt();
    return clampTorchLevel(6, max);
}

int32_t getTorchMaxStrengthLevelExt() {
    (void)ensureTorchKey();
    int32_t max = 0;
    if (doTorchIoctl(FLASH_IOC_GET_MAX_TORCH_DUTY, &max) != 0) {
        return 1;
    }
    if (max < 1) return 1;
    return max;
}

int32_t getTorchStrengthLevelExt() {
    (void)ensureTorchKey();
    int32_t cur = 0;
    if (doTorchIoctl(FLASH_IOC_GET_CURRENT_TORCH_DUTY, &cur) != 0) {
        return 0;
    }
    return cur;
}

void setTorchStrengthLevelExt(int32_t torchStrength, bool enabled) {
    if (!supportsTorchStrengthControlExt()) return;

    if (!enabled || torchStrength <= 0) {
        int32_t off = 0;
        (void)doTorchIoctl(FLASH_IOC_SET_ONOFF, &off);
        return;
    }

    const int32_t max = getTorchMaxStrengthLevelExt();
    int32_t level = clampTorchLevel(torchStrength, max);

    // Set level, disable timeout, then enable.
    (void)doTorchIoctl(FLASH_IOC_SET_DUTY, &level);
    int32_t timeoutMs = 0;
    (void)doTorchIoctl(FLASH_IOC_SET_TIME_OUT_TIME_MS, &timeoutMs);
    int32_t on = 1;
    int rc = doTorchIoctl(FLASH_IOC_SET_ONOFF, &on);
    if (rc != 0) {
        ALOGW("Torch enable failed (%d): %s", -rc, strerror(-rc));
    }
}
