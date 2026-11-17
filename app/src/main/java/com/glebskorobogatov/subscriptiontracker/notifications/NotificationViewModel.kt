package com.glebskorobogatov.subscriptiontracker.notifications

import android.content.Context
import android.os.Build
import androidx.lifecycle.*
import com.glebskorobogatov.subscriptiontracker.app.ActiveDataset
import com.glebskorobogatov.subscriptiontracker.background.WorkManagerReminderScheduler
import com.glebskorobogatov.subscriptiontracker.data.integration.reminderInputs
import com.glebskorobogatov.subscriptiontracker.domain.reminders.ReminderRules
import com.glebskorobogatov.subscriptiontracker.ui.subscriptions.*
import java.time.*
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

interface NotificationEnvironment {
    val runtimePermissionRequired: Boolean
    fun access(): NotificationAccess
    fun generation(): UUID
    /** App-level request history, not an assertion that Android granted permission. */
    fun claimAutomaticOffer(): Boolean
    suspend fun reconcile()
    fun dismiss(route: NotificationRoute)
}
class AndroidNotificationEnvironment(context: Context,
    private val active: () -> UUID = { ActiveDataset(context.applicationContext.filesDir).getOrCreate() },
    private val request: suspend () -> Unit = { WorkManagerReminderScheduler(context.applicationContext).requestReconcile() },
    preferenceName: String = "notification-permission-history-v1",
) : NotificationEnvironment {
    private val context = context.applicationContext
    private val publisher = AndroidReminderPublisher(this.context)
    private val preferences = this.context.getSharedPreferences(preferenceName, Context.MODE_PRIVATE)
    override val runtimePermissionRequired get() = Build.VERSION.SDK_INT >= 33
    override fun access() = publisher.access()
    override fun generation() = active()
    @android.annotation.SuppressLint("UseKtx") // KTX edit(commit=true) discards commit's Boolean; durable offer history requires checking it.
    override fun claimAutomaticOffer(): Boolean = synchronized(offerGate) {
        if (preferences.getBoolean("offered", false)) false
        else {
            val change = preferences.edit()
            change.putBoolean("offered", true)
            // Persist before offering; a failed write must not masquerade as durable request history.
            check(change.commit())
            true
        }
    }
    override suspend fun reconcile() = request()
    override fun dismiss(route: NotificationRoute) = publisher.dismiss(route)
    companion object { private val offerGate = Any() }
}
data class NotificationNavigation(val sequence: Long, val subscription: UUID?)
data class NotificationUiState(val access: NotificationAccess? = null, val explanation: Boolean = false,
    val navigation: NotificationNavigation? = null)

/** Activity-scoped, no Activity references. Only an explicit post-save event may offer permission. */
class NotificationViewModel(private val saved: SavedStateHandle, private val owner: SubscriptionsViewModel,
    private val environment: NotificationEnvironment, private val now: () -> Instant = Instant::now,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val lookup: suspend (NotificationRoute) -> Boolean = { route -> owner.perform {
        check(owner.activeDatasetId()?.let { active -> active == route.generation } != false)
        it.details(route.subscription).first() != null
    } }) : ViewModel() {
    private val mutable = MutableStateFlow(NotificationUiState(explanation = saved["explanation"] ?: false))
    val state = mutable.asStateFlow()
    private var navigationJob: Job? = null
    private var visibleSubscription: UUID? = null
    fun visibleSubscription(id: UUID?) { visibleSubscription = id }
    init {
        refresh()
        if (saved.contains("pendingRoute")) open(saved["pendingRoute"])
    }
    fun refresh() {
        val before = state.value.access
        val access = runCatching { environment.access() }.getOrNull()
        mutable.value = state.value.copy(access = access)
        if (access?.usable == true && access != before) viewModelScope.launch {
            // A scheduler request is also deferred until the shared storage session is verified.
            val ready = owner.state.first { it !is SubscriptionsUiState.Loading }
            if (ready !is SubscriptionsUiState.StorageBlocked && ready !is SubscriptionsUiState.Error) {
                try { environment.reconcile() } catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) { /* The durable wake can retry later. */ }
            }
        }
    }
    fun afterSaved(id: UUID) { viewModelScope.launch {
        try {
            if (!environment.runtimePermissionRequired || environment.access().runtimeGranted) return@launch
            val eligible = owner.perform { cases ->
                val detail = cases.details(id).first() ?: return@perform false
                val settings = cases.settings.first()
                ReminderRules().eligibleForPermission(reminderInputs(listOf(detail.subscription), settings).single(),
                    now().atZone(zone()).toLocalDate())
            }
            if (eligible && withContext(io) { environment.claimAutomaticOffer() }) {
                saved["explanation"] = true
                mutable.value = state.value.copy(explanation = true)
            }
        } catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { /* A permission offer must never turn a committed save into an error. */ }
    } }
    fun dismissExplanation() {
        saved["explanation"] = false
        mutable.value = state.value.copy(explanation = false)
    }
    /** Caller invokes the Activity Result launcher only from the user's confirmation click. */
    fun confirmExplanation(): Boolean {
        if (!state.value.explanation) return false
        dismissExplanation()
        return environment.runtimePermissionRequired && !environment.access().runtimeGranted
    }
    fun open(raw: String?) {
        navigationJob?.cancel()
        saved["pendingRoute"] = raw ?: ""
        val incoming = NotificationRoute.parse(raw)
        val alreadyVisible = incoming != null && incoming.subscription == visibleSubscription && owner.activeDatasetId() == incoming.generation
        navigationJob = viewModelScope.launch {
            val route = NotificationRoute.parse(raw)
            val subscription = try {
                if (route == null || withContext(io) { environment.generation() } != route.generation) null
                else {
                    // Wait for the shared session; a loading database is not a missing subscription.
                    check(owner.state.first { it !is SubscriptionsUiState.Loading } !is SubscriptionsUiState.Error)
                    if (owner.activeDatasetId()?.let { it != route.generation } == true) {
                        owner.retry() // Cancels/joins the old composition before opening the active generation.
                        check(owner.state.first { it !is SubscriptionsUiState.Loading } !is SubscriptionsUiState.Error)
                    }
                    val exists = lookup(route)
                    if (exists && owner.activeDatasetId()?.let { it == route.generation } != false && withContext(io) { environment.generation() } == route.generation) route.subscription else null
                }
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { null }
            route?.let { runCatching { environment.dismiss(it) } }
            // A repeated tap on the visible target must not reopen it after the user pressed Back
            // while Room was being checked. Missing/stale targets still get the safe fallback.
            if (alreadyVisible && subscription != null) {
                saved.remove<String>("pendingRoute")
                return@launch
            }
            val sequence = (saved.get<Long>("navigationSequence") ?: 0) + 1
            saved["navigationSequence"] = sequence
            mutable.value = state.value.copy(navigation = NotificationNavigation(sequence, subscription))
        }
    }
    fun consumed(sequence: Long) {
        if (state.value.navigation?.sequence != sequence) return
        saved.remove<String>("pendingRoute")
        mutable.value = state.value.copy(navigation = null)
    }
}
