package com.forddiag.android.core

sealed class FordException(message: String) : Exception(message) {
    class Timeout : FordException("adapter timeout waiting for prompt")
    class Adapter(msg: String) : FordException("adapter error: $msg")
    class Unexpected(msg: String) : FordException("unexpected response: $msg")
    class Isotp(msg: String) : FordException("ISO-TP: $msg")
    class Uds(msg: String) : FordException("UDS: $msg")
    class UdsNegative(val nrc: Int, description: String) :
        FordException("UDS negative response: NRC=0x${nrc.toString(16)} ($description)")
    class Profile(msg: String) : FordException("profile: $msg")
    class NotConnected : FordException("not connected")
    class Other(msg: String) : FordException(msg)
}

fun nrcDescription(nrc: Int): String = when (nrc) {
    0x10 -> "generalReject"
    0x11 -> "serviceNotSupported"
    0x12 -> "subFunctionNotSupported"
    0x13 -> "incorrectMessageLengthOrInvalidFormat"
    0x22 -> "conditionsNotCorrect"
    0x31 -> "requestOutOfRange"
    0x33 -> "securityAccessDenied"
    0x78 -> "requestCorrectlyReceivedResponsePending"
    0x7F -> "serviceNotSupportedInActiveSession"
    else -> "unknownNrc"
}
