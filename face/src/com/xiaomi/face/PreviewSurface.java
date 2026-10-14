// SPDX-License-Identifier: Apache-2.0
package com.xiaomi.face;

import android.view.Surface;

/** Sets camera stream dimensions on the Surface supplied by Settings. */
final class PreviewSurface {
    static { System.load("/system_ext/lib64/libruby_face_jni.so"); }

    private PreviewSurface() {}

    static native boolean configure(Surface surface, int width, int height);
}
