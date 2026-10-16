package cn.market.assistant.domain.portfolio

import cn.market.assistant.domain.market.MarketQuote

/** Resolves only market feeds whose instrument and accounting unit match a portfolio asset. */
object PortfolioQuoteRouter {
    private const val SGE_AU9999_ASSET_ID = "GOLD.SGE.AU9999"
    private const val SGE_AU9999_QUOTE_ID = "GOLD.SGE.AU9999"
    private const val SGE_AU9999_SOURCE = "sge-au9999"
    private const val GOLD_ETF_ASSET_ID = "518880"
    private const val GOLD_ETF_QUOTE_ID = "1.518880"

    fun defaultQuoteSecid(asset: AssetInfo): String? = when {
        asset.kind == AssetKind.GOLD && asset.symbol.equals(SGE_AU9999_ASSET_ID, ignoreCase = true) ->
            SGE_AU9999_QUOTE_ID
        asset.kind == AssetKind.FUND && asset.symbol == GOLD_ETF_ASSET_ID -> GOLD_ETF_QUOTE_ID
        else -> null
    }

    fun isRouteAllowed(asset: AssetInfo, secid: String): Boolean = when {
        asset.kind == AssetKind.GOLD && asset.symbol.equals(SGE_AU9999_ASSET_ID, ignoreCase = true) ->
            secid == SGE_AU9999_QUOTE_ID
        asset.kind == AssetKind.FUND -> listedFundQuoteSecid(asset) == secid
        else -> false
    }

    /** Builds the exact EastMoney exchange route only for listed-fund code ranges. */
    fun listedFundQuoteSecid(asset: AssetInfo): String? {
        if (asset.kind != AssetKind.FUND || !asset.symbol.matches(Regex("\\d{6}"))) return null
        val exchange = when {
            asset.symbol.startsWith('5') -> "1"
            asset.symbol.startsWith("15") || asset.symbol.startsWith("16") || asset.symbol.startsWith("18") -> "0"
            else -> return null
        }
        return "$exchange.${asset.symbol}"
    }

    fun availableSecids(asset: AssetInfo, quotes: Collection<MarketQuote>): List<String> = buildList {
        asset.marketQuoteSecid?.takeIf { isRouteAllowed(asset, it) }?.let(::add)
        quotes.asSequence()
            .filter { quote -> isRouteAllowed(asset, quote.secid) && isQuoteCompatible(asset, quote) }
            .map { it.secid }
            .forEach(::add)
    }.distinct().sorted()

    fun quoteFor(asset: AssetInfo, quotes: Map<String, MarketQuote>): MarketQuote? {
        val secid = asset.marketQuoteSecid?.takeIf { isRouteAllowed(asset, it) } ?: return null
        val quote = quotes[secid]?.takeIf { it.secid == secid } ?: return null
        return quote.takeIf { isQuoteCompatible(asset, it) }
    }

    private fun isQuoteCompatible(asset: AssetInfo, quote: MarketQuote): Boolean = when {
        asset.kind == AssetKind.GOLD -> quote.secid == SGE_AU9999_QUOTE_ID && quote.source == SGE_AU9999_SOURCE
        asset.kind == AssetKind.FUND -> quote.name.contains("ETF", ignoreCase = true)
        else -> false
    }
}
