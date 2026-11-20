package com.andharness.ru.data.ssh

/** What the stored known-hosts entry says about the key a server just presented. */
enum class HostKeyVerdict {
    /** The presented key is exactly the one we remembered: proceed. */
    MATCH,

    /** Nothing is remembered for this `host:port`: the user must confirm the fingerprint first. */
    UNKNOWN,

    /** A key IS remembered and it differs — refuse; this is the MITM signal. */
    CHANGED;
}
