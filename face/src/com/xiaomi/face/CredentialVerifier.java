// SPDX-License-Identifier: Apache-2.0
package com.xiaomi.face;

import android.hardware.keymaster.HardwareAuthToken;
import android.security.GateKeeper;

final class CredentialVerifier {
    static { System.load("/system_ext/lib64/libruby_face_jni.so"); }

    static boolean verify(int userId, HardwareAuthToken token, boolean passwordOnly)
            throws Exception {
        if (token == null || token.timestamp == null || token.mac == null || token.mac.length != 32
                || token.userId == 0 || (token.authenticatorType != 1 && token.authenticatorType != 2)
                || (passwordOnly && token.authenticatorType != 1)) return false;
        long sid = GateKeeper.getService().getSecureUserId(userId);
        if (sid == 0 || token.userId != sid) return false;
        return verifyNative(token.challenge, token.userId, token.authenticatorId,
                token.authenticatorType, token.timestamp.milliSeconds, token.mac);
    }

    private static native boolean verifyNative(long challenge, long userId, long authenticatorId,
            int authenticatorType, long timestampMillis, byte[] mac);
}
