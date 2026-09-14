package fr.bonobo.dnsphere

/**
 * Classification commune des raisons de blocage.
 *
 * Les codes restent compatibles avec les valeurs historiques de BlockLog.
 */
enum class BlockReason(val code: String) {
    ADS("AD"),
    TRACKER("TRACKER"),
    MALWARE("MALWARE"),
    SHOPPING("SHOPPING"),
    PARENTAL("PARENTAL"),
    EXTERNAL("EXTERNAL"),
    APP_BLOCK("APP_BLOCK"),
    USER_RULE("USER_RULE"),
    DOH_BYPASS("DOH_BYPASS"),
    WEBRTC_STUN("WEBRTC_STUN"),
    FORCE_BLOCKED("FORCE_BLOCKED"),
    OTHER("OTHER");

    companion object {
        fun fromCode(code: String): BlockReason =
            values().firstOrNull { it.code == code } ?: OTHER
    }
}
