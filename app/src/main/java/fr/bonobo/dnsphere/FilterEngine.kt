package fr.bonobo.dnsphere

/**
 * Décision centralisée du filtrage DNS standard.
 *
 * Le moteur VPN reste responsable du transport et de la réponse DNS ; ce
 * composant ne fait que décider si le domaine doit être autorisé ou bloqué,
 * et pourquoi. Les règles d'application et SafeSearch restent dans leur
 * chemin historique pour cette première étape.
 */
class FilterEngine(
    private val blockListManager: BlockListManager,
    private val parentalManager: ParentalManager
) {

    fun evaluate(
        hostname: String,
        blockAds: Boolean,
        blockTrackers: Boolean,
        blockMalware: Boolean,
        blockShopping: Boolean,
        profileBlockAdult: Boolean = false,
        profileBlockGambling: Boolean = false,
        profileBlockSocial: Boolean = false
    ): FilterDecision {
        val result = blockListManager.classifyForFiltering(hostname)

        if (result.exempted) {
            return FilterDecision.allow()
        }

        if (blockListManager.isDohBypass(hostname)) {
            return FilterDecision.block(BlockReason.DOH_BYPASS)
        }

        if (parentalManager.shouldBlockNow(
                hostname,
                profileBlockAdult,
                profileBlockGambling,
                profileBlockSocial
            )) {
            return FilterDecision.block(BlockReason.PARENTAL)
        }

        if (result.forced || result.userBlocked) {
            // FORCE_BLOCKED est conservé pour rester compatible avec les
            // journaux existants et les statistiques historiques.
            return FilterDecision.block(BlockReason.FORCE_BLOCKED)
        }

        if (result.stun) {
            return FilterDecision.block(BlockReason.WEBRTC_STUN)
        }

        return when {
            blockAds && result.isAd -> FilterDecision.block(BlockReason.ADS)
            blockTrackers && result.isTracker -> FilterDecision.block(BlockReason.TRACKER)
            blockMalware && result.isMalware -> FilterDecision.block(BlockReason.MALWARE)
            blockShopping && result.isShopping -> FilterDecision.block(BlockReason.SHOPPING)
            result.isExternal -> FilterDecision.block(BlockReason.EXTERNAL)
            else -> FilterDecision.allow()
        }
    }
}

enum class FilterAction {
    ALLOW,
    BLOCK
}

data class FilterDecision(
    val action: FilterAction,
    val reason: BlockReason? = null
) {
    companion object {
        fun allow() = FilterDecision(FilterAction.ALLOW)
        fun block(reason: BlockReason) = FilterDecision(FilterAction.BLOCK, reason)
    }
}
