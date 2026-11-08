package com.unitrading.rithmic

import kotlinx.serialization.Serializable

/**
 * Which Rithmic system a login targets. "Rithmic Test" has its own development gateway; the
 * Chicago-area R | Protocol gateway serves every production system (Rithmic 01, Paper Trading and
 * the prop-firm systems; checked 2026-11-05 with the system-list request). Rithmic may hand out a
 * regional gateway after conformance, which the user can type instead.
 */
@Serializable
enum class RithmicEnvironment(val wire: String, val label: String, val defaultSystemName: String, val defaultGateway: String?, val live: Boolean) {
    TEST("test", "Rithmic Test", "Rithmic Test", "wss://rituz00100.rithmic.com:443", false),
    PAPER("paper", "Rithmic Paper Trading", "Rithmic Paper Trading", PRODUCTION_GATEWAY, false),
    LIVE("live", "Rithmic Live", "Rithmic 01", PRODUCTION_GATEWAY, true),
}

/** Chicago-area production gateway; the public R | Protocol clients use the same host. */
const val PRODUCTION_GATEWAY = "wss://rprotocol.rithmic.com:443"

/** Everything a plant login needs. The password is only persisted when the user asks to remember it. */
@Serializable
class RithmicCredentials(
    val environment: RithmicEnvironment,
    val gateway: String,
    val systemName: String,
    val user: String,
    val password: String,
    val appName: String = DEFAULT_APP_NAME,
    val appVersion: String = DEFAULT_APP_VERSION,
) {
    init {
        validateRithmicGateway(gateway)
        require(systemName.trim().length in 1..64) { "请选择 Rithmic 系统名称" }
        require(user.trim().length in 1..128) { "请输入 Rithmic 用户名" }
        require(password.length in 1..256) { "请输入 Rithmic 密码" }
        require(appName.length in 1..64 && appVersion.length in 1..32)
    }
    val normalizedGateway: String get() = validateRithmicGateway(gateway)
    /** Stable id per environment, system and user; never derived from the password. */
    val brokerId: String get() = "rithmic-${environment.wire}-${slug(systemName)}-${slug(user)}"
    override fun toString() = "RithmicCredentials(${environment.wire}, $systemName, credentials=redacted)"

    companion object {
        const val DEFAULT_APP_NAME = "UniTrading"
        const val DEFAULT_APP_VERSION = "1.3.1"
        internal fun slug(value: String): String = value.trim().lowercase().map { if (it.isLetterOrDigit()) it else '-' }.joinToString("")
    }
}
