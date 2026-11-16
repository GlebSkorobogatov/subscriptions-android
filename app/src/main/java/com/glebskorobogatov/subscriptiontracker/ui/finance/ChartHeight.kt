package com.glebskorobogatov.subscriptiontracker.ui.finance

import java.math.BigInteger

/** Pixel geometry only. Monetary totals remain untouched Long/Money values from ForecastResult. */
internal fun chartHeightPx(amountMinor: Long, maximumMinor: Long, plotHeightPx: Int): Int {
    require(amountMinor >= 0 && maximumMinor >= amountMinor && plotHeightPx >= 0)
    if(amountMinor == 0L || plotHeightPx == 0) return 0
    // Long.MAX_VALUE * a pixel height must not overflow. A positive value gets at least one pixel.
    return BigInteger.valueOf(amountMinor).multiply(BigInteger.valueOf(plotHeightPx.toLong()))
        .divide(BigInteger.valueOf(maximumMinor)).intValueExact().coerceAtLeast(1)
}
