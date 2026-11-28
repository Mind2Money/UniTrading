package com.unitrading.app

/** Per-account sizing for the new execution-based copier. */
internal data class CopyFollower(val accountId: String, val multiplier: Int, val maxContracts: Int, val fixedContracts: Int? = null)
