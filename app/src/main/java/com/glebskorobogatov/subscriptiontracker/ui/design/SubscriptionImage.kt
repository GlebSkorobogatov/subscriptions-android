package com.glebskorobogatov.subscriptiontracker.ui.design

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import com.glebskorobogatov.subscriptiontracker.ui.subscriptions.SubscriptionsViewModel
import com.glebskorobogatov.subscriptiontracker.data.catalog.OfflineIconCatalog
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

val LocalSubscriptionAssets = staticCompositionLocalOf<SubscriptionsViewModel?> { null }

@Composable
fun SubscriptionImage(name: String, assetId: UUID?, size: Dp = TrackerDimensions.logoSize,
    pendingId: UUID? = null, catalogId: String? = null) {
    val owner = LocalSubscriptionAssets.current
    val generation = owner?.activeDatasetId()
    val bitmap by produceState<android.graphics.Bitmap?>(null, assetId, pendingId, generation, owner) {
        value = null
        if ((assetId != null || pendingId != null) && generation != null) {
            val source = owner ?: return@produceState
            value = runCatching {
                val file = source.perform { if (pendingId != null) it.pendingImage(pendingId) else it.assetFile(requireNotNull(assetId)) }
                withContext(Dispatchers.IO) { file?.let { BitmapFactory.decodeFile(it.path) } }
            }.getOrNull()
        }
    }
    val iconSize = size
    Box(Modifier.size(iconSize).clip(TrackerShapes.logo).clearAndSetSemantics { }, Alignment.Center) {
        if (bitmap != null) Image(bitmap!!.asImageBitmap(), null, Modifier.size(iconSize), contentScale = ContentScale.Crop)
        else {
            SubscriptionThemeIcon(catalogId, iconSize)
        }
    }
}

/** Decorative, offline vector shared by the editor and every subscription row. */
@Composable
fun SubscriptionThemeIcon(catalogId: String?, size: Dp = TrackerDimensions.logoSize) {
    val icon = OfflineIconCatalog.resolve(catalogId)
    Box(Modifier.size(size).clip(TrackerShapes.logo).background(TrackerDesign.colors.surfaceSubtle)
        .clearAndSetSemantics {}, Alignment.Center) {
        Image(painterResource(icon.drawable), null, Modifier.size(size * 0.56f),
            colorFilter = ColorFilter.tint(TrackerDesign.colors.textPrimary))
    }
}
