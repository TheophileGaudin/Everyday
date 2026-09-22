package com.everyday.everyday_glasses.test

import com.everyday.everyday_glasses.runtime.DashboardChangeCoordinator
import org.junit.Assert
import org.junit.Test

class DashboardChangeCoordinatorTest {
    @Test
    fun `repaint does not notify observers`() {
        val fixture = Fixture()
        fixture.coordinator.requestRepaint()
        Assert.assertEquals(listOf("repaint"), fixture.calls)
        Assert.assertEquals(0L, fixture.coordinator.notificationGeneration)
    }

    @Test
    fun `persistent uses container observer contract without transient callback`() {
        val fixture = Fixture()
        fixture.coordinator.notifyPersistent()
        Assert.assertEquals(listOf("repaint", "persistent"), fixture.calls)
        Assert.assertEquals(1L, fixture.coordinator.notificationGeneration)
    }

    @Test
    fun `nested batches emit one strongest change`() {
        val fixture = Fixture()
        fixture.coordinator.batch {
            fixture.coordinator.notifyTransient()
            fixture.coordinator.batch {
                fixture.coordinator.notifyTransient()
                fixture.coordinator.notifyPersistent()
            }
            fixture.coordinator.notifyTransient()
        }
        Assert.assertEquals(listOf("repaint", "persistent"), fixture.calls)
        Assert.assertEquals(1L, fixture.coordinator.notificationGeneration)
    }

    @Test
    fun `exception closes batch and later notifications still work`() {
        val fixture = Fixture()
        Assert.assertThrows(IllegalStateException::class.java) {
            fixture.coordinator.batch {
                fixture.coordinator.notifyTransient()
                error("expected")
            }
        }
        fixture.coordinator.notifyTransient()
        Assert.assertEquals(
            listOf("repaint", "transient", "repaint", "transient"),
            fixture.calls,
        )
    }

    @Test
    fun `nested suppression discards changes and recovers afterward`() {
        val fixture = Fixture()
        fixture.coordinator.withNotificationsSuppressed {
            fixture.coordinator.notifyTransient()
            fixture.coordinator.withNotificationsSuppressed {
                fixture.coordinator.notifyPersistent()
            }
        }
        fixture.coordinator.notifyTransient()
        Assert.assertEquals(listOf("repaint", "transient"), fixture.calls)
    }

    @Test
    fun `lifecycle block discards callbacks and recovers afterward`() {
        val fixture = Fixture()
        fixture.blocked = true
        fixture.coordinator.notifyPersistent()
        fixture.blocked = false
        fixture.coordinator.notifyPersistent()
        Assert.assertEquals(listOf("repaint", "persistent"), fixture.calls)
    }

    @Test
    fun `persistent mutation replaces widget callbacks with one classified event`() {
        val fixture = Fixture()
        fixture.coordinator.runPersistentMutation(
            action = {
                fixture.coordinator.notifyTransient()
                fixture.coordinator.notifyPersistent()
                true
            },
            changed = { it },
        )
        Assert.assertEquals(listOf("repaint", "persistent"), fixture.calls)
    }

    @Test
    fun `unchanged persistent mutation emits nothing`() {
        val fixture = Fixture()
        fixture.coordinator.runPersistentMutation(
            action = {
                fixture.coordinator.notifyTransient()
                false
            },
            changed = { it },
        )
        Assert.assertEquals(emptyList<String>(), fixture.calls)
    }

    @Test
    fun `restore redraws and reports completion without save`() {
        val fixture = Fixture()
        fixture.coordinator.notifyDashboardRestored()
        Assert.assertEquals(listOf("repaint", "restored"), fixture.calls)
        Assert.assertEquals(0L, fixture.coordinator.notificationGeneration)
    }

    private class Fixture {
        val calls = mutableListOf<String>()
        var blocked = false
        val coordinator = DashboardChangeCoordinator(
            repaint = { calls += "repaint" },
            transient = { calls += "transient" },
            persistent = { calls += "persistent" },
            restored = { calls += "restored" },
            notificationsBlocked = { blocked },
        )
    }
}