package com.glebskorobogatov.subscriptiontracker.ui.navigation

import com.glebskorobogatov.subscriptiontracker.notifications.NotificationNavigation
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal sealed interface NotificationNavigationDecision {
    data object Superseded : NotificationNavigationDecision
    data object AwaitEditor : NotificationNavigationDecision
    data object Home : NotificationNavigationDecision
    data object ReadFailed : NotificationNavigationDecision
    data object AlreadyVisible : NotificationNavigationDecision
    data class OpenDetails(val subscriptionId: UUID) : NotificationNavigationDecision
}

internal sealed interface NotificationNavigationEffect {
    data object Ignore : NotificationNavigationEffect
    data object ConfirmEditorExit : NotificationNavigationEffect
    data object OpenHome : NotificationNavigationEffect
    data object StayOnDetails : NotificationNavigationEffect
    data class OpenDetails(val subscriptionId: UUID) : NotificationNavigationEffect
}

internal fun NotificationNavigationDecision.toEffect(): NotificationNavigationEffect = when (this) {
    NotificationNavigationDecision.Superseded -> NotificationNavigationEffect.Ignore
    NotificationNavigationDecision.AwaitEditor -> NotificationNavigationEffect.ConfirmEditorExit
    NotificationNavigationDecision.Home, NotificationNavigationDecision.ReadFailed -> NotificationNavigationEffect.OpenHome
    NotificationNavigationDecision.AlreadyVisible -> NotificationNavigationEffect.StayOnDetails
    is NotificationNavigationDecision.OpenDetails -> NotificationNavigationEffect.OpenDetails(subscriptionId)
}

/**
 * Decides one notification request. The caller marks it consumed only after applying a terminal effect.
 * A newer sequence or cancellation leaves the current request untouched.
 */
internal object NotificationNavigationResolver {
    suspend fun decide(
        request: NotificationNavigation,
        editor: Boolean,
        visibleDetailsId: () -> UUID?,
        activeGeneration: () -> UUID?,
        latestSequence: () -> Long?,
        exists: suspend (UUID) -> Boolean,
    ): NotificationNavigationDecision {
        if (latestSequence() != request.sequence) return NotificationNavigationDecision.Superseded
        if (editor) return NotificationNavigationDecision.AwaitEditor
        val id = request.subscription ?: return NotificationNavigationDecision.Home
        val present = try {
            exists(id)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            // Room can wrap a cancelled read. That is not a missing subscription and not a successful open.
            currentCoroutineContext().ensureActive()
            if (latestSequence() != request.sequence) return NotificationNavigationDecision.Superseded
            if (request.generation == null || request.generation != activeGeneration()) return NotificationNavigationDecision.Home
            return NotificationNavigationDecision.ReadFailed
        }
        currentCoroutineContext().ensureActive()
        if (latestSequence() != request.sequence) return NotificationNavigationDecision.Superseded
        if (request.generation == null || request.generation != activeGeneration() || !present) {
            return NotificationNavigationDecision.Home
        }
        if (visibleDetailsId() == id) return NotificationNavigationDecision.AlreadyVisible
        return NotificationNavigationDecision.OpenDetails(id)
    }
}

internal fun applyNotificationNavigation(
    request: NotificationNavigation,
    decision: NotificationNavigationDecision,
    latestSequence: () -> Long?,
    navigateHome: () -> Unit,
    navigateDetails: (UUID) -> Unit,
    confirmExit: () -> Unit,
    consume: (Long) -> Unit,
) {
    // The sequence can move while the read is suspended. A stale completion must not consume the newer request.
    if (latestSequence() != request.sequence) return
    when (val effect = decision.toEffect()) {
        NotificationNavigationEffect.Ignore -> Unit
        NotificationNavigationEffect.ConfirmEditorExit -> confirmExit()
        NotificationNavigationEffect.OpenHome -> {
            navigateHome()
            consume(request.sequence)
        }
        NotificationNavigationEffect.StayOnDetails -> consume(request.sequence)
        is NotificationNavigationEffect.OpenDetails -> {
            navigateDetails(effect.subscriptionId)
            consume(request.sequence)
        }
    }
}
