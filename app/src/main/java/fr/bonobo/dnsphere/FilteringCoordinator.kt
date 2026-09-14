package fr.bonobo.dnsphere

import fr.bonobo.dnsphere.data.AppRuleType

/** Coordonne les décisions spécifiques aux applications et à SafeSearch. */
class FilteringCoordinator(
    private val appFilterManager: AppFilterManager,
    private val parentalManager: ParentalManager
) {
    sealed class Decision {
        data class BlockForApp(val appName: String) : Decision()
        data class AllowForApp(val appName: String) : Decision()
        data class SafeSearchRedirect(val ip: ByteArray) : Decision()
        data object StandardFiltering : Decision()
        data class StandardBlock(val reason: BlockReason) : Decision()
    }

    fun evaluate(
        packet: ByteArray,
        hostname: String,
        profileBlockAdult: Boolean = false
    ): Decision {
        val appRule = appFilterManager.getRuleForPacket(packet)
        when (appRule?.rule) {
            AppRuleType.BLOCK_ALL -> return Decision.BlockForApp(appRule.appName)
            AppRuleType.ALLOW_ALL -> return Decision.AllowForApp(appRule.appName)
            else -> Unit
        }

        val config = parentalManager.getConfig()
        if ((config.pinEnabled && config.blockAdult) || profileBlockAdult) {
            SafeSearchEnforcer.getSafeIp(hostname)?.let {
                return Decision.SafeSearchRedirect(it)
            }
        }
        return Decision.StandardFiltering
    }

    fun evaluateStandard(
        hostname: String,
        blockAds: Boolean,
        blockTrackers: Boolean,
        blockMalware: Boolean,
        blockShopping: Boolean,
        profileBlockAdult: Boolean = false,
        profileBlockGambling: Boolean = false,
        profileBlockSocial: Boolean = false,
        filterEngine: FilterEngine
    ): Decision {
        if (parentalManager.getConfig().pinEnabled &&
            SafeSearchEnforcer.isBlockedSearchEngine(hostname)) {
            return Decision.StandardBlock(BlockReason.PARENTAL)
        }
        val result = filterEngine.evaluate(
            hostname, blockAds, blockTrackers, blockMalware, blockShopping,
            profileBlockAdult, profileBlockGambling, profileBlockSocial
        )
        return if (result.action == FilterAction.BLOCK && result.reason != null) {
            Decision.StandardBlock(result.reason)
        } else {
            Decision.StandardFiltering
        }
    }
}
