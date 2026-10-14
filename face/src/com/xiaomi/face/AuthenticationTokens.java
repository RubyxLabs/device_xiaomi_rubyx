// SPDX-License-Identifier: Apache-2.0
package com.xiaomi.face;

import android.hardware.keymaster.HardwareAuthToken;
import android.hardware.keymaster.HardwareAuthenticatorType;
import android.hardware.keymaster.Timestamp;

/** The empty token container used by native AIDL callbacks for convenience faces. */
final class AuthenticationTokens {
    private AuthenticationTokens() {}

    static HardwareAuthToken empty() {
        // Match a default-initialized native HardwareAuthToken. The callback's
        // AIDL argument is non-nullable; an empty MAC denotes an empty token.
        // No secure user ID, authenticator type or signed MAC is asserted.
        HardwareAuthToken token = new HardwareAuthToken();
        token.authenticatorType = HardwareAuthenticatorType.NONE;
        token.timestamp = new Timestamp();
        token.mac = new byte[0];
        return token;
    }
}
