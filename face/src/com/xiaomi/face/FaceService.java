// SPDX-License-Identifier: Apache-2.0
package com.xiaomi.face;

import android.app.ActivityThread;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.biometrics.common.CommonProps;
import android.hardware.biometrics.common.ComponentInfo;
import android.hardware.biometrics.common.SensorStrength;
import android.hardware.biometrics.face.FaceSensorType;
import android.hardware.biometrics.face.IFace;
import android.hardware.biometrics.face.ISession;
import android.hardware.biometrics.face.ISessionCallback;
import android.hardware.biometrics.face.SensorProps;
import android.os.Binder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Process;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserManager;
import android.util.Slog;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashMap;
import java.util.Map;

public final class FaceService extends IFace.Stub {
    static final int SENSOR_ID = 1;
    static final File DATA = new File("/data/misc/ruby_face");
    private static IBinder systemServerBinder;
    private static final IBinder.DeathRecipient SYSTEM_SERVER_DEATH = () -> {
        // ActivityThread and UserManager cache framework binders in this process.
        // Init must relaunch us to reconnect them after a system_server restart.
        Slog.w("RubyFace", "System server died; restarting face HAL");
        Process.killProcess(Process.myPid());
    };
    final Context context;
    final Handler worker;
    private final UserManager users;
    private final Map<Integer, FaceState> states = new HashMap<>();
    private FaceSession session;

    private FaceService(Context context) {
        this.context = context;
        users = context.getSystemService(UserManager.class);
        HandlerThread thread = new HandlerThread("RubyFace");
        thread.start();
        worker = new Handler(thread.getLooper());
        context.registerReceiver(new BroadcastReceiver() {
            @Override public void onReceive(Context ignored, Intent intent) {
                int user = intent.getIntExtra(Intent.EXTRA_USER_HANDLE, -1);
                if (user < 0) return;
                synchronized (FaceService.this) {
                    if (session != null && session.userId == user) session.closeFromService();
                }
                worker.post(() -> {
                    states.remove(user);
                    try { deleteTree(new File(DATA, Integer.toString(user))); }
                    catch (IOException e) { Slog.e("RubyFace", "Removing deleted user data", e); }
                });
            }
        }, new IntentFilter(Intent.ACTION_USER_REMOVED), null, worker, Context.RECEIVER_NOT_EXPORTED);
    }

    public static void main(String[] arguments) throws RemoteException {
        if (Process.myUid() != Process.SYSTEM_UID) throw new SecurityException("Expected system UID");
        Looper.prepareMainLooper();
        // class hal may start before these framework services are published.
        systemServerBinder = ServiceManager.waitForService(Context.ACTIVITY_SERVICE);
        systemServerBinder.linkToDeath(SYSTEM_SERVER_DEATH, 0);
        ServiceManager.waitForService(Context.USER_SERVICE);
        Context context = ActivityThread.systemMain().getSystemContext();
        ServiceManager.addService(IFace.DESCRIPTOR + "/default", new FaceService(context));
        Slog.i("RubyFace", "Registered Ruby face HAL (convenience)");
        Looper.loop();
    }

    static void checkCaller() {
        if (Binder.getCallingUid() != Process.SYSTEM_UID) {
            throw new SecurityException("Face HAL operations are restricted to the system UID");
        }
    }

    @Override public SensorProps[] getSensorProps() {
        checkCaller();
        CommonProps common = new CommonProps();
        common.sensorId = SENSOR_ID;
        common.sensorStrength = SensorStrength.CONVENIENCE;
        common.maxEnrollmentsPerUser = 1;
        common.componentInfo = new ComponentInfo[0];
        SensorProps props = new SensorProps();
        props.commonProps = common;
        props.sensorType = FaceSensorType.RGB;
        props.halControlsPreview = true;
        props.enrollPreviewWidth = CameraCapture.WIDTH;
        props.enrollPreviewHeight = CameraCapture.HEIGHT;
        props.enrollPreviewScale = 1f;
        props.supportsDetectInteraction = false;
        return new SensorProps[] {props};
    }

    @Override public synchronized ISession createSession(int sensorId, int userId,
            ISessionCallback callback) {
        checkCaller();
        if (sensorId != SENSOR_ID || userId < 0 || callback == null
                || users.getUserInfo(userId) == null) throw new IllegalArgumentException("Invalid session");
        if (session != null && !session.isClosed()) throw new IllegalStateException("Session already open");
        FaceSession created = new FaceSession(this, userId, callback);
        session = created;
        worker.post(created::restoreLockout);
        return created;
    }

    FaceState state(int userId) throws Exception {
        long serial = users.getSerialNumberForUser(android.os.UserHandle.of(userId));
        if (serial < 0) throw new IllegalStateException("User no longer exists");
        FaceState current = states.get(userId);
        if (current != null && current.userSerial == serial) return current;
        File directory = new File(DATA, Integer.toString(userId));
        if (directory.exists() && FaceState.readSerial(directory) != serial) deleteTree(directory);
        current = new FaceState(directory, serial);
        states.put(userId, current);
        return current;
    }

    void scheduleLockoutClear(int userId, FaceState state) {
        long deadline = state.policy.lockedUntilMillis;
        if (deadline == 0 || state.policy.permanentlyLocked()) return;
        worker.postDelayed(() -> {
            // Session changes must not cancel expiry; removed/reused users must
            // not have their old state written back by an outstanding timer.
            if (states.get(userId) != state) return;
            try {
                if (!state.policy.expireLockout(deadline)) return;
                state.save();
                FaceSession current;
                synchronized (FaceService.this) { current = session; }
                if (current != null && current.userId == userId && !current.isClosed()) {
                    current.onLockoutClearedFromService();
                }
            } catch (Exception e) { Slog.e("RubyFace", "Persisting lockout expiry", e); }
        }, state.policy.timedLockout(System.currentTimeMillis()));
    }

    static void deleteTree(File directory) throws IOException {
        if (!directory.exists()) return;
        // Do not follow symbolic links, including ones inside native SDK storage.
        Files.walkFileTree(directory.toPath(), new SimpleFileVisitor<Path>() {
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attributes)
                    throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult postVisitDirectory(Path path, IOException error)
                    throws IOException {
                if (error != null) throw error;
                Files.delete(path);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    @Override public int getInterfaceVersion() { return IFace.VERSION; }
    @Override public String getInterfaceHash() { return IFace.HASH; }
}
