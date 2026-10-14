// SPDX-License-Identifier: Apache-2.0
package com.xiaomi.face;

import java.nio.ByteBuffer;

/** Converts Camera2 planes without assuming contiguous chroma or unpadded rows. */
final class Yuv {
    static void copyPlane(ByteBuffer source, int rowStride, int pixelStride, int width,
            int height, byte[] target, int offset, int outputStride) {
        int base = source.position();
        if (rowStride <= 0 || pixelStride <= 0 || width < 0 || height < 0) {
            throw new IllegalArgumentException("Invalid YUV plane dimensions or strides");
        }
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                target[offset + (y * width + x) * outputStride] =
                        source.get(base + y * rowStride + x * pixelStride);
            }
        }
    }
}
