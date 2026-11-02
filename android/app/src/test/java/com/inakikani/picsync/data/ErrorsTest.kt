package com.inakikani.picsync.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.FileNotFoundException
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class ErrorsTest {
    private fun says(e: Throwable, part: String) =
        assertTrue("${e.javaClass.simpleName}(${e.message}) -> ${e.explain()}", e.explain().contains(part))

    @Test fun networkErrorsSayWhatToCheck() {
        says(SocketTimeoutException("connect timed out"), "same Wi-Fi")
        says(ConnectException("failed to connect to /192.168.1.5 (port 8080): connect failed: ECONNREFUSED (Connection refused)"), "isn't running")
        says(ConnectException("connect failed: EPERM (Operation not permitted)"), "Nearby devices")
        says(SecurityException("denied"), "Nearby devices")
        says(NoRouteToHostException("No route to host"), "Guest networks")
        says(ConnectException("connect failed: EHOSTUNREACH (No route to host)"), "Guest networks")
        says(UnknownHostException("nope.local"), "unknown address")
        says(SocketException("Connection reset"), "connection dropped")
        says(FileNotFoundException("/x"), "not found on the phone")
    }

    @Test fun otherErrorsKeepTheirMessage() {
        assertEquals("something odd", IOException("something odd").explain())
        assertEquals("IOException", IOException().explain())
    }

    @Test fun serverSideErrorsSayTheMacFailed() {
        assertEquals(
            "the Mac couldn't save it: writing file: no space left on device",
            parseServerError("""{"error":"writing file: no space left on device"}""", 500),
        )
        assertEquals("upload interrupted", parseServerError("""{"error":"upload interrupted"}""", 400))
    }
}
