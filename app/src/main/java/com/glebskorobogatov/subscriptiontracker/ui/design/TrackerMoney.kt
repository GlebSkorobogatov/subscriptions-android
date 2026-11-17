package com.glebskorobogatov.subscriptiontracker.ui.design

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import com.glebskorobogatov.subscriptiontracker.domain.model.CurrencyCode
import com.glebskorobogatov.subscriptiontracker.domain.model.Money

/** Presentation only: reuse the exact formatter, no conversion, floating point or rounding. */
fun formatTrackerRub(amountMinor: Long): String {
    val fixed = Money(amountMinor,CurrencyCode.of("RUB")).format()
    val parts = fixed.split(',')
    val integer = parts[0].reversed().chunked(3).joinToString(" ").reversed()
    return integer + (if(parts[1] == "00") "" else ",${parts[1]}") + " ₽"
}

@Composable
fun TrackerMoney(amountMinor: Long,modifier: Modifier = Modifier,style: TextStyle = TrackerMoneyStyle) {
    Text(formatTrackerRub(amountMinor),modifier,style = style.copy(fontFeatureSettings = "tnum"))
}
