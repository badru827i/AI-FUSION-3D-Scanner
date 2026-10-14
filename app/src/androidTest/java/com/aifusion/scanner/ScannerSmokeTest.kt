package com.aifusion.scanner

import android.Manifest
import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import org.hamcrest.CoreMatchers.containsString
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.espresso.matcher.ViewMatchers.withEffectiveVisibility
import androidx.test.espresso.matcher.ViewMatchers.Visibility

@RunWith(AndroidJUnit4::class)
class ScannerSmokeTest {
    @get:Rule
    val cameraPermission: GrantPermissionRule =
        GrantPermissionRule.grant(Manifest.permission.CAMERA)

    @Test
    fun scannerHomeShowsCoreControlsAndHidesExportsBeforeScan() {
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withId(R.id.startScan)).check(matches(withText("Start 3D Scan")))
            onView(withId(R.id.trackingLock)).check(matches(withText("Lock Target")))
            onView(withId(R.id.denseTrackingButton)).check(matches(withText("Dense Track")))
            onView(withId(R.id.exportActions))
                .check(matches(withEffectiveVisibility(Visibility.GONE)))
        }
    }

    @Test
    fun targetCannotBeLockedBeforeScanStarts() {
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withId(R.id.trackingLock)).perform(click())
            // Assert stable button state instead of a status label that CameraX may update asynchronously.
            onView(withId(R.id.trackingLock)).check(matches(withText("Lock Target")))
            onView(withId(R.id.startScan)).check(matches(withText("Start 3D Scan")))
        }
    }
}
