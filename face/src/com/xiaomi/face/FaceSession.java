// SPDX-License-Identifier: Apache-2.0
package com.xiaomi.face;

import android.hardware.biometrics.common.ICancellationSignal;
import android.hardware.biometrics.common.OperationContext;
import android.hardware.biometrics.face.AcquiredInfo;
import android.hardware.biometrics.face.AuthenticationFrame;
import android.hardware.biometrics.face.BaseFrame;
import android.hardware.biometrics.face.EnrollmentFrame;
import android.hardware.biometrics.face.EnrollmentStageConfig;
import android.hardware.biometrics.face.Error;
import android.hardware.biometrics.face.FaceEnrollOptions;
import android.hardware.biometrics.face.Feature;
import android.hardware.biometrics.face.ISession;
import android.hardware.biometrics.face.ISessionCallback;
import android.hardware.common.NativeHandle;
import android.hardware.keymaster.HardwareAuthToken;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.SystemClock;
import android.security.GateKeeper;
import android.util.Slog;
import android.view.Surface;

final class FaceSession extends ISession.Stub {
    final int userId;
    private final FaceService service;
    private final ISessionCallback callback;
    private final IBinder.DeathRecipient death = this::closeFromService;
    private volatile boolean closed;
    private boolean deathUnlinked;
    private boolean closeDelivered;
    private volatile Operation operation;

    FaceSession(FaceService service, int userId, ISessionCallback callback) {
        this.service = service;
        this.userId = userId;
        this.callback = callback;
        try { callback.asBinder().linkToDeath(death, 0); }
        catch (RemoteException e) { closeFromService(); }
    }

    boolean isClosed() { return closed; }

    void restoreLockout() {
        if (closed) return;
        try {
            FaceState state = service.state(userId);
            if (state.policy.permanentlyLocked()) return;
            if (state.policy.timedLockout(System.currentTimeMillis()) > 0) {
                service.scheduleLockoutClear(userId, state);
            } else {
                if (state.policy.lockedUntilMillis != 0) {
                    state.policy.expireLockout(state.policy.lockedUntilMillis);
                    state.save();
                }
                // Clear the framework's cached timed mode after a session change.
                onLockoutClearedFromService();
            }
        } catch (Exception e) { Slog.e("RubyFace", "Restoring lockout state", e); }
    }

    void onLockoutClearedFromService() {
        if (!closed) reply(callback::onLockoutCleared);
    }

    private interface Work { void run() throws Exception; }
    private interface Reply { void run() throws RemoteException; }

    private void reply(Reply action) {
        try { action.run(); }
        catch (RemoteException e) { closeFromService(); }
    }

    private void submit(Work work) {
        FaceService.checkCaller();
        if (closed) throw new IllegalStateException("Session closed");
        service.worker.post(() -> {
            if (closed) return;
            try {
                if (operation != null) throw new IllegalStateException("Face operation already active");
                work.run();
            } catch (Exception | LinkageError e) {
                Slog.e("RubyFace", "Face operation failed", e);
                reply(() -> callback.onError(Error.UNABLE_TO_PROCESS, 0));
            }
        });
    }

    private StockEngine openEngine(FaceState state) throws Exception {
        StockEngine engine = new StockEngine(state.directory);
        try {
            int[] ids = engine.ids();
            long sid = GateKeeper.getService().getSecureUserId(userId);
            if (ids.length > 0 && (state.secureUserId == 0 || sid != state.secureUserId
                    || state.authenticatorId == 0)) {
                // A removed credential or an uncommitted enrollment never authorizes unlock.
                for (int id : ids) engine.remove(id);
                ids = engine.ids();
                if (ids.length != 0) throw new IllegalStateException("Stale enrollment removal failed");
            }
            if (ids.length == 0 && state.authenticatorId != 0) state.newAuthenticator(false);
            engine.setAttention(state.attention);
            return engine;
        } catch (Exception | LinkageError e) {
            try { engine.close(); }
            catch (Exception | LinkageError cleanup) { e.addSuppressed(cleanup); }
            throw e;
        }
    }

    @Override public void generateChallenge() {
        submit(() -> {
            FaceState state = service.state(userId);
            long challenge = FaceState.randomId();
            state.policy.addChallenge(challenge, SystemClock.elapsedRealtime());
            reply(() -> callback.onChallengeGenerated(challenge));
        });
    }

    @Override public void revokeChallenge(long challenge) {
        submit(() -> {
            service.state(userId).policy.consumeChallenge(challenge);
            reply(() -> callback.onChallengeRevoked(challenge));
        });
    }

    @Override public EnrollmentStageConfig[] getEnrollmentConfig(byte type) {
        FaceService.checkCaller();
        return new EnrollmentStageConfig[0];
    }

    @Override public ICancellationSignal enroll(HardwareAuthToken token, byte type,
            byte[] features, NativeHandle preview) {
        return begin(true, token, null, preview != null);
    }

    @Override public ICancellationSignal enrollWithContext(HardwareAuthToken token, byte type,
            byte[] features, NativeHandle preview, OperationContext context) {
        return enroll(token, type, features, preview);
    }

    @Override public ICancellationSignal enrollWithOptions(FaceEnrollOptions options) {
        if (options == null) throw new IllegalArgumentException("Missing enrollment options");
        return begin(true, options.hardwareAuthToken, options.surfacePreview,
                options.nativeHandlePreview != null);
    }

    @Override public ICancellationSignal authenticate(long operationId) {
        // Convenience faces cannot authorize a Keystore crypto operation.
        return begin(false, null, null, operationId != 0);
    }

    @Override public ICancellationSignal authenticateWithContext(long operationId,
            OperationContext context) { return authenticate(operationId); }

    @Override public ICancellationSignal detectInteraction() {
        return begin(false, null, null, true);
    }

    @Override public ICancellationSignal detectInteractionWithContext(OperationContext context) {
        return detectInteraction();
    }

    @Override public void onContextChanged(OperationContext context) { FaceService.checkCaller(); }

    private synchronized ICancellationSignal begin(boolean enroll, HardwareAuthToken token,
            Surface preview, boolean unsupported) {
        FaceService.checkCaller();
        if (closed || operation != null) throw new IllegalStateException("Session unavailable");
        Operation next = new Operation(enroll, token, preview, unsupported);
        operation = next;
        service.worker.post(next::start);
        return new ICancellationSignal.Stub() {
            @Override public void cancel() {
                FaceService.checkCaller();
                next.cancel();
            }
            @Override public int getInterfaceVersion() { return ICancellationSignal.VERSION; }
            @Override public String getInterfaceHash() { return ICancellationSignal.HASH; }
        };
    }

    @Override public void enumerateEnrollments() {
        submit(() -> {
            try (StockEngine engine = openEngine(service.state(userId))) {
                int[] ids = engine.ids();
                for (int i = 0; i < ids.length; i++) ids[i] = TemplateStatus.toFrameworkId(ids[i]);
                reply(() -> callback.onEnrollmentsEnumerated(ids));
            }
        });
    }

    @Override public void removeEnrollments(int[] enrollments) {
        if (enrollments == null) throw new IllegalArgumentException("Missing enrollment IDs");
        int[] requested = enrollments.clone();
        submit(() -> {
            FaceState state = service.state(userId);
            try (StockEngine engine = openEngine(state)) {
                int[] current = engine.ids();
                for (int id : requested) {
                    int nativeId = TemplateStatus.fromFrameworkId(id);
                    if (contains(current, nativeId)) engine.remove(nativeId);
                }
                int[] remaining = engine.ids();
                for (int id : requested) {
                    if (contains(remaining, TemplateStatus.fromFrameworkId(id))) {
                        reply(() -> callback.onError(Error.UNABLE_TO_REMOVE, 0));
                        return;
                    }
                }
                state.newAuthenticator(remaining.length > 0);
                if (remaining.length == 0) {
                    state.secureUserId = 0;
                    state.policy.reset();
                    state.save();
                }
                reply(() -> callback.onEnrollmentsRemoved(requested));
            }
        });
    }

    @Override public void getFeatures() {
        submit(() -> {
            byte[] features = service.state(userId).attention
                    ? new byte[] {Feature.REQUIRE_ATTENTION} : new byte[0];
            reply(() -> callback.onFeaturesRetrieved(features));
        });
    }

    @Override public void setFeature(HardwareAuthToken token, byte feature, boolean enabled) {
        submit(() -> {
            FaceState state = service.state(userId);
            if (feature != Feature.REQUIRE_ATTENTION || token == null
                    || !state.policy.hasChallenge(token.challenge, SystemClock.elapsedRealtime())
                    || !CredentialVerifier.verify(userId, token, true)) {
                reply(() -> callback.onError(Error.UNABLE_TO_PROCESS, 0));
                return;
            }
            // Settings reuses the confirmed credential for attention changes and
            // enrollment until it revokes the challenge. TEE also checks HAT age.
            state.attention = enabled;
            state.save();
            reply(() -> callback.onFeatureSet(feature));
        });
    }

    @Override public void getAuthenticatorId() {
        submit(() -> {
            long id = service.state(userId).authenticatorId;
            reply(() -> callback.onAuthenticatorIdRetrieved(id));
        });
    }

    @Override public void invalidateAuthenticatorId() {
        submit(() -> {
            FaceState state = service.state(userId);
            state.newAuthenticator(state.authenticatorId != 0);
            reply(() -> callback.onAuthenticatorIdInvalidated(state.authenticatorId));
        });
    }

    @Override public void resetLockout(HardwareAuthToken token) {
        submit(() -> {
            if (!CredentialVerifier.verify(userId, token, false)) {
                reply(() -> callback.onError(Error.UNABLE_TO_PROCESS, 0));
                return;
            }
            FaceState state = service.state(userId);
            state.policy.reset();
            state.save();
            reply(callback::onLockoutCleared);
        });
    }

    @Override public void close() {
        FaceService.checkCaller();
        closeFromService();
    }

    void closeFromService() {
        closeFromService(true);
    }

    private void closeFromService(boolean notifyFramework) {
        closed = true;
        Operation current = operation;
        if (current != null) current.cancel();
        service.worker.post(() -> {
            if (!deathUnlinked) {
                deathUnlinked = true;
                callback.asBinder().unlinkToDeath(death, 0);
            }
            if (!notifyFramework || closeDelivered) return;
            closeDelivered = true;
            reply(callback::onSessionClosed);
        });
    }

    private final class Operation implements CameraCapture.Listener {
        final boolean enrolling;
        final HardwareAuthToken token;
        final Surface preview;
        final boolean unsupported;
        final Runnable timeout = () -> finishError(Error.TIMEOUT);
        FaceState state;
        StockEngine engine;
        CameraCapture camera;
        int[] originalIds;
        volatile boolean cancelled;
        boolean terminal;
        boolean delivered;
        int rejectedFrames;
        byte lastAcquired = AcquiredInfo.UNKNOWN;
        long lastGuidance;

        Operation(boolean enrolling, HardwareAuthToken token, Surface preview,
                boolean unsupported) {
            this.enrolling = enrolling;
            this.token = token;
            this.preview = preview;
            this.unsupported = unsupported;
        }

        void start() {
            if (closed || cancelled) { finishError(Error.CANCELED); return; }
            if (unsupported) { finishError(Error.UNABLE_TO_PROCESS); return; }
            try {
                state = service.state(userId);
                if (enrolling) {
                    if (token == null || !state.policy.hasChallenge(token.challenge,
                            SystemClock.elapsedRealtime())
                            || !CredentialVerifier.verify(userId, token, true)) {
                        finishError(Error.UNABLE_TO_PROCESS);
                        return;
                    }
                    state.policy.reset();
                    state.save();
                } else if (state.policy.permanentlyLocked()) {
                    if (claimTerminal()) { cleanup(); delivered = true; reply(callback::onLockoutPermanent); }
                    return;
                } else {
                    long remaining = state.policy.timedLockout(System.currentTimeMillis());
                    if (remaining > 0) {
                        if (claimTerminal()) { cleanup(); delivered = true; reply(() -> callback.onLockoutTimed(remaining)); }
                        return;
                    }
                }
                engine = openEngine(state);
                originalIds = engine.ids();
                if (enrolling && originalIds.length != 0) { finishError(Error.NO_SPACE); return; }
                if (!enrolling && originalIds.length == 0) {
                    finishError(Error.REENROLL_REQUIRED);
                    return;
                }
                engine.reset();
                if (closed || cancelled) { finishError(Error.CANCELED); return; }
                camera = new CameraCapture(service.context, service.worker, preview, this);
                service.worker.postDelayed(timeout, enrolling ? 60_000 : 5_000);
            } catch (Exception | LinkageError e) {
                Slog.e("RubyFace", "Starting face capture", e);
                finishError(Error.HW_UNAVAILABLE);
            }
        }

        @Override public void onFrame(byte[] nv21) throws Exception {
            if (cancelled || closed) { finishError(Error.CANCELED); return; }
            if (terminal) return;
            StockEngine.Result result = engine.frame(nv21, CameraCapture.WIDTH,
                    CameraCapture.HEIGHT, CameraCapture.SDK_ROTATION, enrolling);
            if (cancelled || closed) { finishError(Error.CANCELED); return; }
            if (result.status < 0) {
                finishError(result.status == -1001 ? Error.REENROLL_REQUIRED : Error.UNABLE_TO_PROCESS);
            } else if (result.success) {
                if (result.id < 0 || !contains(engine.ids(), result.id)
                        || (!enrolling && !contains(originalIds, result.id))) {
                    finishError(Error.UNABLE_TO_PROCESS);
                    return;
                }
                int frameworkId = TemplateStatus.toFrameworkId(result.id);
                if (!claimTerminal()) { finishError(Error.CANCELED); return; }
                if (enrolling) {
                    state.secureUserId = token.userId;
                    // Attention defaults to enabled and changes through setFeature.
                    // The framework enrollment list contains pose/debug features,
                    // so absence of REQUIRE_ATTENTION must not disable eye checks.
                    state.newAuthenticator(true);
                    cleanup();
                    delivered = true;
                    reply(() -> callback.onEnrollmentProgress(frameworkId, 1));
                    reply(() -> callback.onEnrollmentProgress(frameworkId, 0));
                } else {
                    state.policy.reset();
                    state.save();
                    cleanup();
                    delivered = true;
                    // Convenience strength supplies an empty, unsigned AIDL container.
                    reply(() -> callback.onAuthenticationSucceeded(
                            frameworkId, AuthenticationTokens.empty()));
                }
            } else if (!enrolling && (result.status == 202 || result.status == 2)) {
                if (++rejectedFrames >= 3 || result.status == 2) failAuthentication();
            } else if (result.status == 203 || result.status == 204) {
                finishError(Error.REENROLL_REQUIRED);
            } else {
                rejectedFrames = 0;
                guidance(acquired(result.status));
            }
        }

        private void guidance(byte acquired) {
            long now = SystemClock.elapsedRealtime();
            if (acquired == lastAcquired && now - lastGuidance < 1000) return;
            if (now - lastGuidance < 200) return;
            lastAcquired = acquired;
            lastGuidance = now;
            BaseFrame base = new BaseFrame();
            base.acquiredInfo = acquired;
            base.isCancellable = true;
            if (enrolling) {
                EnrollmentFrame frame = new EnrollmentFrame();
                frame.data = base;
                reply(() -> callback.onEnrollmentFrame(frame));
            } else {
                AuthenticationFrame frame = new AuthenticationFrame();
                frame.data = base;
                reply(() -> callback.onAuthenticationFrame(frame));
            }
        }

        private void failAuthentication() throws Exception {
            if (!claimTerminal()) { finishError(Error.CANCELED); return; }
            state.policy.fail(System.currentTimeMillis());
            state.save();
            cleanup();
            delivered = true;
            if (state.policy.permanentlyLocked()) reply(callback::onLockoutPermanent);
            else {
                long remaining = state.policy.timedLockout(System.currentTimeMillis());
                if (remaining > 0) {
                    service.scheduleLockoutClear(userId, state);
                    reply(() -> callback.onLockoutTimed(remaining));
                } else reply(callback::onAuthenticationFailed);
            }
        }

        synchronized boolean claimTerminal() {
            if (terminal || cancelled || closed) return false;
            terminal = true;
            return true;
        }

        void cancel() {
            synchronized (this) {
                if (terminal) return;
                cancelled = true;
            }
            service.worker.post(() -> finishError(Error.CANCELED));
        }

        void finishError(byte error) {
            synchronized (this) {
                if (delivered) return;
                terminal = true;
                delivered = true;
                if (cancelled || closed) error = Error.CANCELED;
            }
            if (enrolling && engine != null && originalIds != null) {
                try {
                    for (int id : engine.ids()) {
                        if (!contains(originalIds, id)) engine.remove(id);
                    }
                    if (originalIds.length == 0 && state != null) {
                        state.secureUserId = 0;
                        state.newAuthenticator(false);
                    }
                } catch (Exception | LinkageError e) {
                    Slog.e("RubyFace", "Rolling back incomplete enrollment", e);
                }
            }
            cleanup();
            byte result = error;
            // The framework discards its session on HW_UNAVAILABLE without
            // calling close(). Retire ours before it can request a replacement.
            // onSessionClosed is only for an explicit close/user-stop request.
            if (result == Error.HW_UNAVAILABLE) closeFromService(false);
            reply(() -> callback.onError(result, 0));
        }

        @Override public void onError(Throwable error) {
            Slog.e("RubyFace", "Face capture failed", error);
            finishError(Error.HW_UNAVAILABLE);
        }

        void cleanup() {
            service.worker.removeCallbacks(timeout);
            if (camera != null) {
                try { camera.close(); }
                catch (RuntimeException e) { Slog.e("RubyFace", "Releasing camera", e); }
                camera = null;
            }
            if (engine != null) {
                try { engine.close(); }
                catch (Exception | LinkageError e) { Slog.e("RubyFace", "Releasing face engine", e); }
                engine = null;
            }
            if (preview != null) {
                try { preview.release(); }
                catch (RuntimeException e) { Slog.e("RubyFace", "Releasing preview", e); }
            }
            synchronized (FaceSession.this) {
                if (operation == this) operation = null;
            }
        }
    }

    private static byte acquired(int status) {
        switch (status) {
            case 1: return AcquiredInfo.NOT_DETECTED;
            case 3: return AcquiredInfo.POOR_GAZE;
            case 103: return AcquiredInfo.FACE_OBSCURED;
            case 104: return AcquiredInfo.MOUTH_COVERING_DETECTED;
            case 113: case 114: return AcquiredInfo.TILT_TOO_EXTREME;
            case 115: case 116: return AcquiredInfo.PAN_TOO_EXTREME;
            case 120: return AcquiredInfo.TOO_FAR;
            case 121: return AcquiredInfo.TOO_CLOSE;
            case 123: return AcquiredInfo.TOO_DARK;
            case 124: case 7: return AcquiredInfo.TOO_BRIGHT;
            case 129: return AcquiredInfo.INSUFFICIENT;
            default: return AcquiredInfo.INSUFFICIENT;
        }
    }

    private static boolean contains(int[] ids, int target) {
        for (int id : ids) if (id == target) return true;
        return false;
    }

    @Override public int getInterfaceVersion() { return ISession.VERSION; }
    @Override public String getInterfaceHash() { return ISession.HASH; }
}
