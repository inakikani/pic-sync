package com.inakikani.picsync.data

import java.io.FileNotFoundException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * A short explanation of a network or file error that says what to check.
 * The raw exception still goes to the log (see AppLog).
 */
fun Throwable.explain(): String {
    val m = message.orEmpty()
    return when {
        this is SecurityException || "EPERM" in m || "Operation not permitted" in m ->
            "blocked by Android: allow Nearby devices (local network) for pic-sync in Settings → Apps"
        this is SocketTimeoutException ->
            "timed out: check the phone is on the same Wi-Fi as the Mac and pic-sync is running"
        this is NoRouteToHostException || "EHOSTUNREACH" in m || "ENETUNREACH" in m ->
            "can't reach the Mac: same Wi-Fi? Guest networks often keep devices apart"
        "ECONNREFUSED" in m || (this is ConnectException && "refused" in m) ->
            "connection refused: pic-sync isn't running on the Mac, or the port is wrong"
        this is ConnectException -> "couldn't connect: is pic-sync running on the Mac? ($m)"
        this is UnknownHostException -> "unknown address: use the address shown by pic-sync on the Mac"
        this is SocketException && ("reset" in m || "Broken pipe" in m || "EPIPE" in m) ->
            "connection dropped: the Mac stopped or the Wi-Fi changed"
        this is FileNotFoundException -> "file not found on the phone (deleted or moved?)"
        m.isNotBlank() -> m
        else -> javaClass.simpleName
    }
}
