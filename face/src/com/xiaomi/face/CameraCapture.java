// SPDX-License-Identifier: Apache-2.0
package com.xiaomi.face;

import android.content.Context;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.util.Size;
import android.util.Slog;
import android.view.Surface;

import java.util.ArrayList;
import java.util.Arrays;

/** All callbacks and SDK work run on the face worker; frames are never saved. */
final class CameraCapture implements AutoCloseable {
    static final int WIDTH = 640;
    static final int HEIGHT = 480;
    static final int SDK_ROTATION = 90; // The stock Ruby SDK caller uses portrait rotation 90.
    interface Listener {
        void onFrame(byte[] nv21) throws Exception;
        void onError(Throwable error);
    }
    private final Handler handler;
    private final Listener listener;
    private final Surface preview;
    private final byte[] nv21 = new byte[WIDTH * HEIGHT * 3 / 2];
    private CameraDevice camera;
    private CameraCaptureSession session;
    private ImageReader reader;
    private boolean closed;

    CameraCapture(Context context, Handler handler, Surface preview, Listener listener)
            throws Exception {
        this.handler = handler;
        this.preview = preview;
        this.listener = listener;
        CameraManager manager = context.getSystemService(CameraManager.class);
        String cameraId = null;
        for (String id : manager.getCameraIdList()) {
            CameraCharacteristics properties = manager.getCameraCharacteristics(id);
            Integer facing = properties.get(CameraCharacteristics.LENS_FACING);
            if (facing == null || facing != CameraCharacteristics.LENS_FACING_FRONT) continue;
            StreamConfigurationMap map = properties.get(
                    CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            Size[] sizes = map == null ? null : map.getOutputSizes(ImageFormat.YUV_420_888);
            if (sizes != null && Arrays.asList(sizes).contains(new Size(WIDTH, HEIGHT))) {
                cameraId = id;
                break;
            }
        }
        if (cameraId == null) throw new IllegalStateException("Front YUV camera unavailable");
        if (preview != null && (!preview.isValid()
                || !PreviewSurface.configure(preview, WIDTH, HEIGHT))) {
            throw new IllegalStateException("Face preview Surface configuration failed");
        }
        reader = ImageReader.newInstance(WIDTH, HEIGHT, ImageFormat.YUV_420_888, 2);
        reader.setOnImageAvailableListener(this::onImage, handler);
        try {
            manager.openCamera(cameraId, new CameraDevice.StateCallback() {
                @Override public void onOpened(CameraDevice device) {
                    if (closed) { device.close(); return; }
                    camera = device;
                    configure();
                }
                @Override public void onDisconnected(CameraDevice device) {
                    device.close();
                    fail(new IllegalStateException("Face camera disconnected"));
                }
                @Override public void onError(CameraDevice device, int error) {
                    device.close();
                    fail(new IllegalStateException("Face camera error " + error));
                }
            }, handler);
        } catch (Exception e) {
            close();
            throw e;
        }
    }

    private void configure() {
        try {
            ArrayList<Surface> outputs = new ArrayList<>();
            outputs.add(reader.getSurface());
            if (preview != null) outputs.add(preview);
            camera.createCaptureSession(outputs, new CameraCaptureSession.StateCallback() {
                @Override public void onConfigured(CameraCaptureSession configured) {
                    if (closed) { configured.close(); return; }
                    session = configured;
                    try {
                        CaptureRequest.Builder request = camera.createCaptureRequest(
                                CameraDevice.TEMPLATE_PREVIEW);
                        for (Surface surface : outputs) request.addTarget(surface);
                        session.setRepeatingRequest(request.build(), null, handler);
                    } catch (Exception e) {
                        fail(e);
                    }
                }
                @Override public void onConfigureFailed(CameraCaptureSession configured) {
                    configured.close();
                    fail(new IllegalStateException("Face camera configuration failed"));
                }
            }, handler);
        } catch (Exception e) {
            fail(e);
        }
    }

    private void onImage(ImageReader source) {
        if (closed) return;
        try (Image image = source.acquireLatestImage()) {
            if (image == null) return;
            if (image.getWidth() != WIDTH || image.getHeight() != HEIGHT
                    || image.getCropRect().width() != WIDTH
                    || image.getCropRect().height() != HEIGHT) {
                throw new IllegalStateException("Unexpected face camera image geometry");
            }
            Image.Plane[] planes = image.getPlanes();
            if (planes.length != 3) throw new IllegalStateException("Unexpected face YUV layout");
            for (int i = 0; i < 3; i++) {
                Yuv.copyPlane(planes[i].getBuffer(), planes[i].getRowStride(),
                        planes[i].getPixelStride(), i == 0 ? WIDTH : WIDTH / 2,
                        i == 0 ? HEIGHT : HEIGHT / 2, nv21,
                        i == 0 ? 0 : WIDTH * HEIGHT + (i == 1 ? 1 : 0), i == 0 ? 1 : 2);
            }
            listener.onFrame(nv21);
        } catch (Exception | LinkageError e) {
            fail(e);
        }
    }

    private void fail(Throwable error) {
        if (!closed) listener.onError(error);
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        try { if (session != null) session.close(); }
        catch (RuntimeException e) { Slog.w("RubyFace", "Closing camera session", e); }
        try { if (camera != null) camera.close(); }
        catch (RuntimeException e) { Slog.w("RubyFace", "Closing camera", e); }
        if (reader != null) reader.close();
    }
}
