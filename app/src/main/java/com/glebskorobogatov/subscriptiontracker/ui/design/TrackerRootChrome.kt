package com.glebskorobogatov.subscriptiontracker.ui.design

import android.content.ContentResolver
import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.*
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlin.math.abs
import kotlin.math.roundToInt

/** Pure gesture hysteresis. Programmatic/side-effect scrolling is deliberately excluded by the adapter. */
internal class RootScrollPolicy(private val threshold: Float = TrackerChrome.scrollThresholdDp) {
    var hidden = false; private set
    private var scrollable = false
    private var top = true
    private var pinned = false
    private var travel = 0f
    fun viewport(canScroll: Boolean,atTop: Boolean,keepVisible: Boolean = false) {
        scrollable = canScroll; top = atTop; pinned = keepVisible
        if(!canScroll || atTop || keepVisible) { hidden = false; travel = 0f }
    }
    fun scroll(x: Float,y: Float) {
        if(!scrollable || top || pinned || !x.isFinite() || !y.isFinite()) return
        if(abs(x) >= abs(y) || y == 0f) { travel = 0f; return }
        if(travel*y < 0f) travel = 0f
        travel += y
        if(abs(travel) >= threshold) { hidden = travel < 0; travel = 0f }
    }
    fun stop() { travel = 0f }
}
internal data class RootViewport(val scrollable: Boolean,val top: Boolean)
internal class RootPanelController(val tab: TrackerTab) {
    private val policy = RootScrollPolicy()
    var hidden by mutableStateOf(false); private set
    var readViewport: (() -> RootViewport)? = null
    var keepVisible = false
    fun refresh() {
        val viewport = readViewport?.invoke() ?: RootViewport(false,true)
        policy.viewport(viewport.scrollable,viewport.top,keepVisible); hidden = policy.hidden
    }
    fun scroll(x: Float,y: Float) { refresh(); policy.scroll(x,y); hidden = policy.hidden }
    fun stop() = policy.stop()
}
@Composable internal fun trackerPageHorizontalPadding() = with(LocalDensity.current) {
    TrackerDimensions.horizontalPadding(LocalWindowInfo.current.containerSize.width.toDp())
}

internal val LocalRootPanels = staticCompositionLocalOf<RootPanelController?> { null }
/** User preference from settings. System animator scale is read separately by trackerMotionDuration. */
internal val LocalTrackerAnimations = staticCompositionLocalOf { true }
internal val LocalRootContentPadding = staticCompositionLocalOf<PaddingValues> { PaddingValues(0.dp) }
/** Spec that was current before the island inset. Horizontal containers restore it. */
internal val LocalVerticalSafeBringIntoViewParent = staticCompositionLocalOf<BringIntoViewSpec?> { null }

internal fun verticalSafeBringIntoViewDistance(parentDistance: Float, offset: Float, size: Float, containerSize: Float, inset: Float): Float {
    if (inset <= 0f) return parentDistance
    val safeEnd = (containerSize - inset).coerceAtLeast(0f)
    val newOffset = offset - parentDistance
    val newEnd = newOffset + size
    val extra = if (size <= safeEnd && newEnd > safeEnd) newEnd - safeEnd else 0f
    return parentDistance + extra
}

/** The island inset is vertical. A nested horizontal scroller must keep the spec from outside the chrome. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun WithoutVerticalSafeBringIntoView(content: @Composable () -> Unit) {
    val parent = LocalVerticalSafeBringIntoViewParent.current
    if (parent == null) content()
    else CompositionLocalProvider(LocalBringIntoViewSpec provides parent, content = content)
}

@Composable internal fun BindRootList(tab: TrackerTab,state: LazyListState,canHide: Boolean = true) {
    BindRootViewport(tab,canHide) { RootViewport(state.canScrollBackward || state.canScrollForward,!state.canScrollBackward) }
}
@Composable internal fun BindRootScroll(tab: TrackerTab,state: ScrollState,canHide: Boolean = true) {
    BindRootViewport(tab,canHide) { RootViewport(state.maxValue > 0,state.value == 0) }
}
@Composable private fun BindRootViewport(tab: TrackerTab,canHide: Boolean,viewport: () -> RootViewport) {
    val controller = LocalRootPanels.current?.takeIf { it.tab == tab } ?: return
    val allowed by rememberUpdatedState(canHide)
    val current by rememberUpdatedState(viewport)
    val read = remember(controller) { { if(allowed) current() else RootViewport(false,true) } }
    DisposableEffect(controller,read) {
        controller.readViewport = read; controller.refresh()
        onDispose { if(controller.readViewport === read) controller.readViewport = null }
    }
    LaunchedEffect(controller,read) { snapshotFlow { read() }.distinctUntilChanged().collect { controller.refresh() } }
}

@Composable internal fun trackerMotionDuration(animationsEnabled: Boolean = true): Int {
    val resolver = LocalContext.current.contentResolver
    val scope = rememberCoroutineScope()
    // The window recomposer publishes this scale. A test recomposer omits it, and a positive
    // value must not hide animator_duration_scale = 0, so the global setting is read as well.
    val composed = scope.coroutineContext[MotionDurationScale]?.scaleFactor
    var stored by remember(resolver) { mutableFloatStateOf(animatorDurationScale(resolver)) }
    DisposableEffect(resolver) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { stored = animatorDurationScale(resolver) }
        }
        resolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE),false,observer)
        stored = animatorDurationScale(resolver)
        onDispose { resolver.unregisterContentObserver(observer) }
    }
    val scale = if(!stored.isFinite() || stored <= 0f) stored else composed ?: stored
    return TrackerMotion.durationMillis(animationsEnabled,scale)
}
private fun animatorDurationScale(resolver: ContentResolver): Float =
    Settings.Global.getFloat(resolver,Settings.Global.ANIMATOR_DURATION_SCALE,1f)
@Composable private fun touchExplorationEnabled(): Boolean {
    val context = LocalContext.current
    val manager = remember(context) { context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager }
    var enabled by remember(manager) { mutableStateOf(manager?.isTouchExplorationEnabled ?: false) }
    DisposableEffect(manager) {
        val listener = AccessibilityManager.TouchExplorationStateChangeListener { enabled = it }
        manager?.addTouchExplorationStateChangeListener(listener)
        onDispose { manager?.removeTouchExplorationStateChangeListener(listener) }
    }
    return enabled
}
private enum class RootSlot { HEADER, ISLAND, CONTENT }

/** Stable full-size content slot. Measure overlays before content in the same layout pass;
 * padding does not depend on visibility, so animations never move the list's scroll position. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun TrackerRootChrome(selected: TrackerTab?,onSelect: (TrackerTab) -> Unit,onAdd: () -> Unit,
    modifier: Modifier = Modifier,animationsEnabled: Boolean = true,visibleOverride: Boolean? = null,
    content: @Composable () -> Unit) {
    val controller = remember(selected) { selected?.let(::RootPanelController) }
    val pinned = touchExplorationEnabled()
    SideEffect { controller?.let { it.keepVisible = pinned; it.refresh() } }
    val visible = visibleOverride ?: (pinned || controller?.hidden != true)
    val duration = trackerMotionDuration(animationsEnabled)
    val shown by animateFloatAsState(if(visible) 1f else 0f,tween(duration),label = "root panels")
    val density = LocalDensity.current
    val connection = remember(controller,density) { object : NestedScrollConnection {
        override fun onPostScroll(consumed: Offset,available: Offset,source: NestedScrollSource): Offset {
            if(source == NestedScrollSource.UserInput) controller?.scroll(consumed.x/density.density,consumed.y/density.density)
            return Offset.Zero
        }
        override suspend fun onPostFling(consumed: Velocity,available: Velocity): Velocity { controller?.stop(); return Velocity.Zero }
    } }
    SubcomposeLayout(modifier.fillMaxSize().nestedScroll(connection)) { constraints ->
        val panels = constraints.copy(minHeight = 0)
        val header = subcompose(RootSlot.HEADER) {
            if(selected != null) RootHeader(selected,onAdd,visible)
        }.map { it.measure(panels) }
        val island = subcompose(RootSlot.ISLAND) {
            if(selected != null) TrackerBottomNavigation(selected,onSelect,enabled = visible)
        }.map { it.measure(panels) }
        val top = header.maxOfOrNull { it.height } ?: 0
        val bottom = island.maxOfOrNull { it.height } ?: 0
        val insets = PaddingValues(top = top.toDp(),bottom = bottom.toDp())
        val body = subcompose(RootSlot.CONTENT) {
            val parentBringIntoView = LocalBringIntoViewSpec.current
            // Content still draws behind the island. A child requested into view must finish above it,
            // otherwise a center click lands on the middle tab.
            val safeBringIntoView = remember(bottom,parentBringIntoView) {
                object : BringIntoViewSpec {
                    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
                        val base = parentBringIntoView.calculateScrollDistance(offset, size, containerSize)
                        return verticalSafeBringIntoViewDistance(base, offset, size, containerSize, bottom.toFloat())
                    }
                }
            }
            CompositionLocalProvider(LocalRootPanels provides controller, LocalRootContentPadding provides insets,
                LocalBringIntoViewSpec provides safeBringIntoView,
                LocalVerticalSafeBringIntoViewParent provides parentBringIntoView, content = content)
        }.map { it.measure(constraints) }
        layout(constraints.maxWidth,constraints.maxHeight) {
            body.forEach { it.placeRelative(0,0) }
            if(shown > 0f) {
                header.forEach { it.placeRelativeWithLayer(0,(-top*(1-shown)).roundToInt(),zIndex = 1f) }
                island.forEach { it.placeRelativeWithLayer(0,constraints.maxHeight-bottom+(bottom*(1-shown)).roundToInt(),zIndex = 1f) }
            }
        }
    }
}

@Composable private fun RootHeader(tab: TrackerTab,onAdd: () -> Unit,enabled: Boolean) {
    Surface(color = TrackerDesign.colors.background.copy(alpha = TrackerChrome.headerAlpha),tonalElevation = 0.dp,
        modifier = Modifier.testTag("root_header").then(if(!enabled) Modifier.clearAndSetSemantics {} else Modifier)) {
        if(tab == TrackerTab.Subscriptions) com.glebskorobogatov.subscriptiontracker.ui.subscriptions.SubscriptionsTopBar(onAdd,enabled)
        else TrackerPageHeader(title = androidx.compose.ui.res.stringResource(tab.label))
    }
}
