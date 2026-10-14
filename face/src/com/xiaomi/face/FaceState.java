// SPDX-License-Identifier: Apache-2.0
package com.xiaomi.face;

import android.system.Os;
import android.util.AtomicFile;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.SecureRandom;

final class FaceState {
    final File directory;
    final long userSerial;
    final FacePolicy policy = new FacePolicy();
    private final AtomicFile file;
    long authenticatorId;
    long secureUserId;
    boolean attention = true;

    FaceState(File directory, long userSerial) throws Exception {
        this.directory = directory;
        this.userSerial = userSerial;
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("Cannot create face user storage");
        }
        Os.chmod(directory.getPath(), 0700);
        file = new AtomicFile(new File(directory, "state"));
        if (file.exists()) {
            try (DataInputStream input = new DataInputStream(file.openRead())) {
                if (input.readInt() != 1 || input.readLong() != userSerial) {
                    throw new IOException("Invalid face state version/user identity");
                }
                authenticatorId = input.readLong();
                secureUserId = input.readLong();
                attention = input.readBoolean();
                policy.failedAttempts = input.readInt();
                policy.lockedUntilMillis = input.readLong();
                if (policy.failedAttempts < 0 || policy.failedAttempts > FacePolicy.PERMANENT_THRESHOLD
                        || input.read() != -1) throw new IOException("Invalid face state data");
            }
        } else {
            save();
        }
    }

    static long readSerial(File directory) throws IOException {
        AtomicFile state = new AtomicFile(new File(directory, "state"));
        if (!state.exists()) return -1;
        try (DataInputStream input = new DataInputStream(state.openRead())) {
            if (input.readInt() != 1) throw new IOException("Invalid face state version");
            return input.readLong();
        }
    }

    void newAuthenticator(boolean hasEnrollments) throws IOException {
        authenticatorId = hasEnrollments ? randomId() : 0;
        save();
    }

    void save() throws IOException {
        FileOutputStream stream = file.startWrite();
        try {
            DataOutputStream output = new DataOutputStream(stream);
            output.writeInt(1);
            output.writeLong(userSerial);
            output.writeLong(authenticatorId);
            output.writeLong(secureUserId);
            output.writeBoolean(attention);
            output.writeInt(policy.failedAttempts);
            output.writeLong(policy.lockedUntilMillis);
            output.flush();
            file.finishWrite(stream);
        } catch (IOException | RuntimeException e) {
            file.failWrite(stream);
            throw e;
        }
    }

    static long randomId() {
        long id;
        SecureRandom random = new SecureRandom();
        do { id = random.nextLong(); } while (id == 0);
        return id;
    }
}
