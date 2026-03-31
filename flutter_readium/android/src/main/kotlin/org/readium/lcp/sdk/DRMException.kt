package org.readium.lcp.sdk

class DRMException(val drmError: DRMError, message: String = "") : Exception(message)
