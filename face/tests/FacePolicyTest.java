// SPDX-License-Identifier: Apache-2.0
package com.xiaomi.face;

public final class FacePolicyTest {
    private static int checks;
    public static void main(String[] arguments) {
        TemplateStatus.check(203, 0);
        TemplateStatus.check(204, 0);
        TemplateStatus.check(0, 0);
        TemplateStatus.check(0, 1);
        rejects(() -> TemplateStatus.check(-1001, 0));
        rejects(() -> TemplateStatus.check(204, 1));
        rejects(() -> TemplateStatus.check(-4, 0));
        rejects(() -> TemplateStatus.check(201, 0));
        for (int id : new int[] {0, 1, 7, 100, Integer.MAX_VALUE - 1}) {
            expect(TemplateStatus.fromFrameworkId(TemplateStatus.toFrameworkId(id)) == id);
        }
        rejects(() -> TemplateStatus.toFrameworkId(-1));
        rejects(() -> TemplateStatus.toFrameworkId(Integer.MAX_VALUE));
        rejects(() -> TemplateStatus.fromFrameworkId(0));

        FacePolicy first = new FacePolicy();
        FacePolicy second = new FacePolicy();
        first.addChallenge(9, 100);
        expect(first.hasChallenge(9, 100));
        expect(first.hasChallenge(9, 300099));
        expect(!first.hasChallenge(9, 300100));
        expect(!first.hasChallenge(0, 100));
        expect(!second.hasChallenge(9, 100));
        first.consumeChallenge(9);
        expect(!first.hasChallenge(9, 100));
        rejects(() -> first.addChallenge(0, 100));
        for (int i = 1; i <= 16; i++) first.addChallenge(i, 100);
        rejects(() -> first.addChallenge(17, 100));
        first.addChallenge(17, 300101);
        expect(first.hasChallenge(17, 300101));

        FacePolicy lockout = new FacePolicy();
        for (int i = 1; i <= 20; i++) {
            lockout.fail(i * 100000L);
            expect(lockout.failedAttempts == i);
            expect(lockout.permanentlyLocked() == (i == 20));
            expect(lockout.timedLockout(i * 100000L) == (i % 5 == 0 ? 30000 : 0));
        }
        expect(!lockout.expireLockout(lockout.lockedUntilMillis));
        expect(lockout.permanentlyLocked());
        lockout.reset();
        expect(!lockout.permanentlyLocked());
        expect(lockout.timedLockout(0) == 0);
        for (int i = 0; i < 5; i++) lockout.fail(1000);
        long oldDeadline = lockout.lockedUntilMillis;
        lockout.reset();
        for (int i = 0; i < 5; i++) lockout.fail(2000);
        expect(!lockout.expireLockout(oldDeadline));
        expect(lockout.timedLockout(2000) == 30000);
        expect(lockout.expireLockout(lockout.lockedUntilMillis));
        expect(lockout.timedLockout(2000) == 0);
        System.out.println("PASS: " + checks + " face policy/empty-template/ID checks");
    }

    private static void expect(boolean condition) {
        checks++;
        if (!condition) throw new AssertionError("Face policy check " + checks);
    }

    private static void rejects(Runnable action) {
        checks++;
        try { action.run(); }
        catch (IllegalArgumentException | IllegalStateException expected) { return; }
        throw new AssertionError("Invalid input accepted, check " + checks);
    }
}
