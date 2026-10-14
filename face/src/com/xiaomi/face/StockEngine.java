// SPDX-License-Identifier: Apache-2.0
package com.xiaomi.face;

import android.os.Build;

import com.android.internal.os.ClassLoaderFactory;

import java.io.File;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/** Calls the unmodified SDK from Ruby's stock APK without starting MIUI's Application. */
final class StockEngine implements AutoCloseable {
    private final Class<?> nativeClass;
    private final Object manager;
    private final Method release;
    private final Method setOrientation;
    private final Method enroll;
    private final Method authenticate;
    private final Method lastUnlockId;
    private final Method enumerate;
    private final Method delete;
    private final Method reset;
    private final Constructor<?> image;
    private final Field faceId;
    private boolean initialized;
    private int compareFlags;
    private static ClassLoader sdkLoader;

    StockEngine(File root) throws Exception {
        ClassLoader loader = loader();
        nativeClass = loader.loadClass("com.ts.faceauth.FaceAuthNative");
        Class<?> managerClass = loader.loadClass("com.ts.faceauth.FaceAuthManager");
        Class<?> imageClass = loader.loadClass("com.ts.faceauth.data.STImage");
        Class<?> faceIdClass = loader.loadClass("com.ts.faceauth.data.FaceIDInfo");
        manager = call(managerClass.getMethod("getInstance"), null);
        release = managerClass.getMethod("release");
        setOrientation = nativeClass.getMethod("setOrientation", int.class, int[].class);
        enroll = nativeClass.getMethod("enroll", imageClass, int.class, int[].class);
        authenticate = nativeClass.getMethod("auth", imageClass, int.class,
                int[].class, int[].class);
        lastUnlockId = nativeClass.getMethod("getLastUnlockID", int[].class);
        enumerate = nativeClass.getMethod("getFaceIDInfoInTemplate", int[].class);
        delete = nativeClass.getMethod("deleteFace", int.class);
        reset = nativeClass.getMethod("resetFaceAuth");
        image = imageClass.getConstructor(byte[].class, int.class, int.class,
                int.class, int.class);
        faceId = faceIdClass.getField("mFaceID");
        call(nativeClass.getMethod("setLogLevel", int.class), null, 0);

        File templates = new File(root, "templates");
        if (!Boolean.TRUE.equals(call(managerClass.getMethod("initHandler", String.class),
                manager, templates.getPath()))) {
            throw new IllegalStateException("Stock SDK rejected the template directory");
        }
        // The original bytecode supplies its original license and region thresholds.
        // These arguments match the stock caller; models are embedded in the native library.
        initialized = true; // Also release partially initialized native state on failure.
        try {
            int result = (Integer) call(managerClass.getMethod("initAll", String.class,
                    String.class), manager, "model1", "model2");
            if (result != 0) {
                throw new IllegalStateException("Stock SDK initialization failed, status=" + result);
            }
            int[] status = new int[1];
            Class<?> rect = loader.loadClass("com.ts.faceapi.model.StRect");
            call(nativeClass.getMethod("setRoi", rect, int[].class), null, null, status);
            requireOk("setRoi", status[0]);
            requireOk("setEnrollNumberForFace", (Integer) call(nativeClass.getMethod(
                    "setEnrollNumberForFace", int.class), null, 1));
        } catch (Exception | LinkageError e) {
            try {
                close();
            } catch (Exception | LinkageError cleanupError) {
                e.addSuppressed(cleanupError);
            }
            throw e;
        }
    }

    private static synchronized ClassLoader loader() {
        if (sdkLoader == null) {
            sdkLoader = ClassLoaderFactory.createClassLoader(
                    "/system_ext/framework/ruby-face-sdk.jar",
                    "/system_ext/etc/ruby_face/lib64", "/system_ext/etc/ruby_face",
                    StockEngine.class.getClassLoader(),
                    Build.VERSION.SDK_INT, true, null);
        }
        return sdkLoader;
    }

    void setAttention(boolean enabled) {
        // Original SDK flags: close eye-status detection (1), close gaze detection (2).
        compareFlags = enabled ? 0 : 3;
    }

    int[] ids() throws Exception {
        int[] status = new int[1];
        Object entries = call(enumerate, null, (Object) status);
        TemplateStatus.check(status[0], entries == null ? 0 : Array.getLength(entries));
        int[] ids = new int[entries == null ? 0 : Array.getLength(entries)];
        for (int i = 0; i < ids.length; i++) {
            ids[i] = faceId.getInt(Array.get(entries, i));
        }
        return ids;
    }

    Result frame(byte[] nv21, int width, int height, int rotation, boolean enrolling)
            throws Exception {
        int[] status = new int[1];
        int orientation;
        switch (rotation) {
            case 0: orientation = 1; break;
            case 90: orientation = 8; break;
            case 180: orientation = 4; break;
            case 270: orientation = 2; break;
            default: throw new IllegalArgumentException("rotation must be 0, 90, 180, or 270");
        }
        call(setOrientation, null, orientation, status);
        requireOk("setOrientation", status[0]);
        // Stock CvPixelFormat.NV21 == 3; use the SDK's public integer constructor.
        Object sdkImage = image.newInstance(nv21, 3, width, height, width);
        if (enrolling) {
            int id = (Integer) call(enroll, null, sdkImage, 0, status);
            return new Result(status[0], id, status[0] == 0 || status[0] == 101);
        }
        call(authenticate, null, sdkImage, compareFlags, new int[1], status);
        int authStatus = status[0];
        if (authStatus != 201) {
            return new Result(authStatus, -1, false);
        }
        int id = (Integer) call(lastUnlockId, null, (Object) status);
        requireOk("getLastUnlockID", status[0]);
        return new Result(authStatus, id, true);
    }

    void reset() throws Exception {
        requireOk("resetFaceAuth", (Integer) call(reset, null));
    }

    void remove(int id) throws Exception {
        requireOk("deleteFace", (Integer) call(delete, null, id));
    }

    @Override
    public void close() throws Exception {
        if (initialized) {
            initialized = false;
            call(release, manager);
        }
    }

    private static void requireOk(String operation, int status) {
        if (status != 0) {
            throw new IllegalStateException(operation + " failed, native_status=" + status);
        }
    }

    private static Object call(Method method, Object receiver, Object... arguments) throws Exception {
        try {
            return method.invoke(receiver, arguments);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            if (cause instanceof Error) throw (Error) cause;
            throw e;
        }
    }

    static final class Result {
        final int status;
        final int id;
        final boolean success;

        Result(int status, int id, boolean success) {
            this.status = status;
            this.id = id;
            this.success = success;
        }
    }
}
