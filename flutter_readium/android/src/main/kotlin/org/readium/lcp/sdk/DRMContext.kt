package org.readium.lcp.sdk

class DRMContext(
    val hashedPassphrase: String,
    val encryptedContentKey: String,
    val token: String,
    val profile: String,
)
