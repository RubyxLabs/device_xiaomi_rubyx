// SPDX-License-Identifier: Apache-2.0
package com.xiaomi.face;

final class TemplateStatus {
    // Original SDK StFaceAuthStatus: template missing / no face information.
    private static final int TEMPLATE_MISSING = 203;
    private static final int TEMPLATE_EMPTY = 204;

    static void check(int status, int count) {
        if (status == 0 || ((status == TEMPLATE_MISSING || status == TEMPLATE_EMPTY) && count == 0)) {
            return;
        }
        // In particular, -1001 (damaged template) is never turned into an empty database.
        throw new IllegalStateException("Face template query failed, native status=" + status);
    }

    static int toFrameworkId(int id) {
        if (id < 0 || id == Integer.MAX_VALUE) throw new IllegalArgumentException("Invalid face ID");
        return id + 1;
    }

    static int fromFrameworkId(int id) {
        if (id <= 0) throw new IllegalArgumentException("Invalid framework face ID");
        return id - 1;
    }
}
