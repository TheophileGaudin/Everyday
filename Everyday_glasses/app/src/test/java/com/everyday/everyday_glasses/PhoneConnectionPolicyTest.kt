package com.everyday.everyday_glasses

import com.everyday.everyday_glasses.controllers.PhoneConnectionCoordinator
import org.junit.Assert.assertEquals
import org.junit.Test

class PhoneConnectionPolicyTest {
    @Test
    fun `configured Wi-Fi Direct address wins among multiple peers`() {
        assertEquals(
            1,
            PhoneConnectionCoordinator.selectWifiDirectPeerIndex(
                listOf("AA:00:00:00:00:01", "AA:00:00:00:00:02"),
                "aa:00:00:00:00:02"
            )
        )
    }

    @Test
    fun `multiple peers without configured identity are rejected`() {
        assertEquals(
            -1,
            PhoneConnectionCoordinator.selectWifiDirectPeerIndex(
                listOf("AA:00:00:00:00:01", "AA:00:00:00:00:02"),
                preferredAddress = null
            )
        )
    }

    @Test
    fun `one peer remains compatible with phones that expose no identity`() {
        assertEquals(
            0,
            PhoneConnectionCoordinator.selectWifiDirectPeerIndex(
                listOf("AA:00:00:00:00:01"),
                preferredAddress = null
            )
        )
    }
}
