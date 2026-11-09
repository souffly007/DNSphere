package fr.bonobo.dnsphere.dns

/** NXDOMAIN, REFUSED and null-route answers are filtering results, not failover signals. */
object DohResponsePolicy {
    fun isUsable(query: ByteArray, answer: ByteArray): Boolean {
        if (query.size < 12 || answer.size < 12 || answer.size > 65535) return false
        if (query[0] != answer[0] || query[1] != answer[1]) return false
        if (answer[2].toInt() and 0x80 == 0) return false
        if (answer[3].toInt() and 0x0f == 2) return false // SERVFAIL: service failure
        // Match the uncompressed question echoed by RFC 8484 resolvers.
        var end = 12
        while (end < query.size && query[end].toInt() != 0) {
            val length = query[end].toInt() and 255
            if (length > 63) return false
            end += 1 + length
        }
        end += 5
        return end <= query.size && end <= answer.size &&
            query.copyOfRange(4, 6).contentEquals(answer.copyOfRange(4, 6)) &&
            query.copyOfRange(12, end).contentEquals(answer.copyOfRange(12, end))
    }
}
