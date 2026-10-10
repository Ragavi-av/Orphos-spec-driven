package com.orphos.mobile.auth;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

/**
 * The biometric prompt must come back only when the user really leaves the app — never
 * for moving between the app's own screens. Android starts the next activity before it
 * stops the previous one, which is the ordering these tests replay.
 */
public class AppLockStateTest {

    private AppLockState lock;

    /** MainActivity started and the user unlocked. */
    @Before
    public void unlockedOnMain() {
        lock = new AppLockState();
        lock.activityStarted();
        lock.markUnlocked();
    }

    @Test
    public void openingAndClosingAnInAppScreenKeepsTheUnlock() {
        lock.activityStarted();          // DecisionDetailActivity starts
        lock.activityStopped(false);     // MainActivity stops behind it
        assertTrue(lock.isUnlocked());

        lock.activityStarted();          // back: MainActivity restarts
        lock.activityStopped(false);     // detail screen stops
        assertTrue(lock.isUnlocked());
    }

    @Test
    public void backgroundingTheAppLocksIt() {
        lock.activityStopped(false);     // Home button: nothing of ours is visible
        assertFalse(lock.isUnlocked());
    }

    @Test
    public void backgroundingFromAnInAppScreenLocksIt() {
        lock.activityStarted();
        lock.activityStopped(false);     // now on a detail screen
        lock.activityStopped(false);     // Home button from there
        assertFalse(lock.isUnlocked());
    }

    @Test
    public void rotationOrLanguageRecreateKeepsTheUnlock() {
        lock.activityStopped(true);      // old instance stops mid-recreate
        lock.activityStarted();          // new instance starts
        assertTrue(lock.isUnlocked());
    }

    @Test
    public void signOutForgetsTheUnlock() {
        lock.lock();
        assertFalse(lock.isUnlocked());
    }
}
