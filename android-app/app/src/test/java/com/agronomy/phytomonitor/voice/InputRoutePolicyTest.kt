package com.agronomy.phytomonitor.voice

import org.junit.Assert.*
import org.junit.Test

class InputRoutePolicyTest {
    private val phone = InputDevice(1, InputKind.PHONE)
    private val wired = InputDevice(2, InputKind.WIRED)
    private val usb = InputDevice(3, InputKind.USB)
    private val bluetooth = InputDevice(4, InputKind.BLUETOOTH)

    @Test
    fun existingCallsOrCommunicationSessionsAreNotTakenOver() {
        assertTrue(InputRoutePolicy.canAcquireMode(0))
        assertFalse(InputRoutePolicy.canAcquireMode(1)) // MODE_RINGTONE
        assertFalse(InputRoutePolicy.canAcquireMode(2)) // MODE_IN_CALL
        assertFalse(InputRoutePolicy.canAcquireMode(3)) // MODE_IN_COMMUNICATION
    }

    @Test
    fun wiredAndUsbMicrophonesTakePriorityOverBluetoothAndPhone() {
        assertEquals(wired, InputRoutePolicy.choose(listOf(phone, bluetooth, wired), true))
        assertEquals(usb, InputRoutePolicy.choose(listOf(phone, usb), false))
    }

    @Test
    fun bluetoothNeedsPermissionAndAbsenceDoesNotInventAHeadset() {
        assertEquals(bluetooth, InputRoutePolicy.choose(listOf(phone, bluetooth), true))
        assertEquals(phone, InputRoutePolicy.choose(listOf(phone, bluetooth), false))
        assertEquals(phone, InputRoutePolicy.choose(listOf(phone), true))
        assertNull(InputRoutePolicy.choose(emptyList(), true))
    }

    @Test
    fun selectedCommunicationOutputDoesNotConfirmTheRecorderInput() {
        assertFalse(InputRoutePolicy.confirmed(bluetooth, null))
        assertFalse(InputRoutePolicy.confirmed(bluetooth, phone))
        assertTrue(InputRoutePolicy.confirmed(bluetooth, InputDevice(99, InputKind.BLUETOOTH)))
        assertFalse(InputRoutePolicy.confirmed(wired, InputDevice(99, InputKind.WIRED)))
        assertTrue(InputRoutePolicy.confirmed(wired, wired))
        assertTrue(InputRoutePolicy.confirmed(phone, InputDevice(91, InputKind.PHONE)))
    }
}
