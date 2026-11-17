package com.glebskorobogatov.subscriptiontracker.ui.editor

import androidx.lifecycle.*
import com.glebskorobogatov.subscriptiontracker.R
import com.glebskorobogatov.subscriptiontracker.data.catalog.OfflineIconCatalog
import com.glebskorobogatov.subscriptiontracker.data.integration.*
import com.glebskorobogatov.subscriptiontracker.data.lifecycle.LifecycleConflict
import com.glebskorobogatov.subscriptiontracker.data.mapping.toDomain
import com.glebskorobogatov.subscriptiontracker.data.room.*
import com.glebskorobogatov.subscriptiontracker.domain.calendar.*
import com.glebskorobogatov.subscriptiontracker.domain.model.*
import com.glebskorobogatov.subscriptiontracker.domain.subscription.*
import com.glebskorobogatov.subscriptiontracker.ui.subscriptions.SubscriptionsViewModel
import java.time.*
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class EditorState(val fields: Map<String,String> = emptyMap(), val loading: Boolean = true,
    val saving: Boolean = false, val dirty: Boolean = false, val done: Boolean = false,
    val errors: Map<String,Int> = emptyMap(), val error: Int? = null, val conflict: Boolean = false,
    val loadFailure: Boolean = false, val notFound: Boolean = false,
    val logoAssetId: UUID? = null, val pendingImageId: UUID? = null, val imageRemoved: Boolean = false,
    val importingImage: Boolean = false, val catalogLogoId: String? = null, val scheduleEditable: Boolean = true)

class EditorViewModel(private val saved: SavedStateHandle, private val owner: SubscriptionsViewModel,
    private val id: UUID?) : ViewModel() {
    init {
        if (saved.get<Int>("draftVersion") != 5) {
            listOf("fields", "scheduleDraft", "expected", "pendingImage", "imageRemoved", "catalogChoice", "generation").forEach { saved.remove<Any>(it) }
            saved["draftVersion"] = 5
        }
    }
    private var expected: SubscriptionEntity? = null
    private val mutable = MutableStateFlow(EditorState(fields = saved.get<HashMap<String,String>>("fields") ?: emptyMap(),dirty = saved.contains("fields")))
    val state = mutable.asStateFlow()
    private val newId = UUID.fromString(saved.get<String>("newId") ?: UUID.randomUUID().toString().also { saved["newId"] = it })
    private var pendingImage = saved.get<String>("pendingImage")?.let(UUID::fromString)
    private var imageRemoved = saved.get<Boolean>("imageRemoved") ?: false
    private var catalogChoice = saved.get<String>("catalogChoice")
    private var imageEpoch = 0
    val savedSubscriptionId: UUID get() = id ?: newId
    init { reload(false) }
    fun change(key: String, value: String) {
        if (state.value.saving || key !in FIELD_KEYS) return
        if (state.value.fields[key] == value) return
        var fields = state.value.fields + (key to value)
        if (key in setOf("date", "unit", "count")) {
            val before = saved.get<HashMap<String,String>>("scheduleDraft")
                ?: HashMap(state.value.fields).also { saved["scheduleDraft"] = it }
            fields = fields - setOf("anchorDay", "start", "periodDue")
            // Keep refinements with their draft series. A temporary edit followed by a return
            // restores them, including an explicit clear, without changing other edited fields.
            if (listOf("date", "unit", "count").all { fields[it] == before[it] }) {
                fields = fields + before.filterKeys { it in setOf("anchorDay", "start", "periodDue") }
                saved.remove<HashMap<String,String>>("scheduleDraft")
            }
        }
        if (key == "start") fields = if (value.isBlank()) fields - "periodDue"
            else fields + ("periodDue" to fields.getValue("date"))
        saved["fields"] = HashMap(fields)
        mutable.value = state.value.copy(fields = fields, dirty = true, errors = emptyMap(), error = null)
    }
    fun selectImage(uri: android.net.Uri) {
        if (state.value.saving || state.value.importingImage) return
        saved["fields"] = HashMap(state.value.fields)
        val epoch = ++imageEpoch
        mutable.value = state.value.copy(importingImage = true, dirty = true, error = null)
        viewModelScope.launch {
            try {
                val staged = owner.perform { it.stageImage(uri) }
                if (epoch != imageEpoch) { owner.perform { it.discardImage(staged) }; return@launch }
                val previous = pendingImage
                pendingImage = staged; saved["pendingImage"] = staged.toString()
                catalogChoice = null; saved.remove<String>("catalogChoice")
                imageRemoved = false; saved["imageRemoved"] = false
                mutable.value = state.value.copy(pendingImageId = staged, catalogLogoId = null, imageRemoved = false, dirty = true, error = null, importingImage = false)
                if (previous != null) owner.perform { it.discardImage(previous) }
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { mutable.value = state.value.copy(error = R.string.editor_image_error, importingImage = false) }
        }
    }
    fun removeImage() {
        if (state.value.saving || state.value.importingImage) return
        saved["fields"] = HashMap(state.value.fields)
        val previous = pendingImage
        pendingImage = null; saved.remove<String>("pendingImage")
        catalogChoice = null; saved.remove<String>("catalogChoice")
        imageRemoved = true; saved["imageRemoved"] = true
        mutable.value = state.value.copy(pendingImageId = null, catalogLogoId = null, imageRemoved = true, dirty = true)
        if (previous != null) viewModelScope.launch { owner.perform { it.discardImage(previous) } }
    }
    fun selectCatalog(catalogId: String) {
        if (state.value.saving || state.value.importingImage || OfflineIconCatalog.find(catalogId) == null) return
        saved["fields"] = HashMap(state.value.fields)
        imageEpoch++
        val previous = pendingImage
        pendingImage = null; saved.remove<String>("pendingImage")
        catalogChoice = catalogId; saved["catalogChoice"] = catalogId
        imageRemoved = false; saved["imageRemoved"] = false
        mutable.value = state.value.copy(pendingImageId = null, catalogLogoId = catalogId,
            imageRemoved = false, dirty = true, error = null)
        if (previous != null) viewModelScope.launch { owner.perform { it.discardImage(previous) } }
    }
    fun discardImageDraft() {
        imageEpoch++
        pendingImage?.let { id -> viewModelScope.launch { runCatching { owner.perform { it.discardImage(id) } } } }
        pendingImage = null; imageRemoved = false; catalogChoice = null
        saved.remove<String>("pendingImage"); saved.remove<Boolean>("imageRemoved"); saved.remove<String>("catalogChoice")
    }
    fun reload(discard: Boolean = true) { viewModelScope.launch {
        mutable.value = state.value.copy(loading = true)
        try {
            val fields = if (id == null) defaults() else {
                if(owner.state.value is com.glebskorobogatov.subscriptiontracker.ui.subscriptions.SubscriptionsUiState.Error) owner.retry()
                check(owner.state.first { it !is com.glebskorobogatov.subscriptiontracker.ui.subscriptions.SubscriptionsUiState.Loading }.let {
                    it !is com.glebskorobogatov.subscriptiontracker.ui.subscriptions.SubscriptionsUiState.Error && it !is com.glebskorobogatov.subscriptiontracker.ui.subscriptions.SubscriptionsUiState.StorageBlocked
                })
                val detail = owner.details(id).first()
                if(detail == null) {
                    mutable.value = state.value.copy(loading = false,notFound = true,loadFailure = false,error = null)
                    return@launch
                }
                expected = detail.subscription
                fromRow(detail.subscription)
            }
            val oldDraft = if (discard) null else saved.get<HashMap<String,String>>("fields")
            val restored = oldDraft?.filterKeys { it in FIELD_KEYS }
            restored?.let { saved["fields"] = HashMap(it) }
            val fingerprint = expected.toString()
            val hasDraft = !discard && (restored != null || pendingImage != null || imageRemoved || catalogChoice != null)
            val stale = hasDraft && (saved.get<String>("expected")?.let { it != fingerprint } == true ||
                saved.get<String>("generation")?.let { it != owner.activeDatasetId()?.toString() } == true)
            if (!stale) {
                saved["expected"] = fingerprint
                owner.activeDatasetId()?.let { saved["generation"] = it.toString() }
            }
            if (discard) { discardImageDraft(); saved.remove<HashMap<String,String>>("scheduleDraft") }
            mutable.value = EditorState(restored ?: fields, loading = false, dirty = restored != null || pendingImage != null || imageRemoved || catalogChoice != null,
                errors = emptyMap(), scheduleEditable = expected?.state?.let { it == SubscriptionState.ACTIVE } ?: true,
                conflict = stale, error = if (stale) R.string.editor_conflict else null,
                logoAssetId = expected?.logoAssetId, pendingImageId = pendingImage, imageRemoved = imageRemoved,
                catalogLogoId = catalogChoice ?: if (imageRemoved || pendingImage != null) null else expected?.catalogLogoId)
            if (discard) saved.remove<HashMap<String,String>>("fields")
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { mutable.value = state.value.copy(loading = false, error = R.string.editor_load_error, conflict = false, loadFailure = true) }
    } }
    fun save() {
        if (state.value.loading || state.value.saving || state.value.importingImage || state.value.conflict || state.value.loadFailure || state.value.notFound || state.value.done) return
        val fields = state.value.fields
        val errors = validate(fields)
        if (errors.isNotEmpty()) { mutable.value = state.value.copy(errors = errors); return }
        mutable.value = state.value.copy(saving = true, error = null)
        viewModelScope.launch {
            try {
                owner.perform { use ->
                    if (saved.get<String>("generation")?.let { it != owner.activeDatasetId()?.toString() } == true) throw LifecycleConflict()
                    val price = RubPrice.parse(fields.getValue("amount"))
                    val schedule = scheduleEdit(fields)
                    val uuid = id ?: newId
                    val start = fields["start"]?.takeIf { it.isNotBlank() }?.let(LocalDate::parse)
                    val label = fields["label"]?.trim()?.takeIf { it.isNotEmpty() }
                    val old = expected
                    if (old == null) {
                        val initial = requireNotNull(schedule)
                        use.createSubscription(SubscriptionDraft(uuid, fields.getValue("name").trim(), label,
                            price, initial, start?.let { PeriodStart(it, initial.firstDue) }, logoAssetId = pendingImage, catalogLogoId = catalogChoice), Instant.now())
                    } else {
                        val periodStart = start?.let { PeriodStart(it, fields["periodDue"]?.takeIf(String::isNotBlank)?.let(LocalDate::parse)
                            ?: LocalDate.parse(fields.getValue("date"))) }
                        val imageEdit = pendingImage?.let { ImageEdit.Set(it) }
                            ?: catalogChoice?.let { ImageEdit.Catalog(it) }
                            ?: if (imageRemoved) ImageEdit.Remove else ImageEdit.Keep
                        use.editSubscription(old, SubscriptionEdit(fields.getValue("name").trim(), label, price, schedule, PeriodStartEdit.Set(periodStart), imageEdit), Instant.now())
                    }
                }
                saved.remove<HashMap<String,String>>("fields")
                saved.remove<HashMap<String,String>>("scheduleDraft")
                pendingImage = null; catalogChoice = null
                saved.remove<String>("pendingImage"); saved.remove<Boolean>("imageRemoved"); saved.remove<String>("catalogChoice")
                mutable.value = state.value.copy(saving = false, dirty = false, done = true)
            } catch (e: CancellationException) { throw e }
            catch (_: LifecycleConflict) { mutable.value = state.value.copy(saving = false, conflict = true, error = R.string.editor_conflict) }
            catch (_: Exception) { mutable.value = state.value.copy(saving = false, error = R.string.editor_save_error) }
        }
    }
    private fun defaults() = mapOf("name" to "", "amount" to "", "unit" to "MONTH", "count" to "1",
        "date" to owner.today().toString())
    private fun fromRow(row: SubscriptionEntity): Map<String,String> {
        val record = row.toDomain()
        val due = calculationOrNull { SubscriptionDomain().nextDue(record.schedule, owner.today()) }?.toString().orEmpty()
        return defaults() + mapOf("name" to row.name, "amount" to record.price.format(),
            "label" to (row.accountLabel ?: ""), "unit" to row.intervalUnit.name, "count" to row.intervalCount.toString(),
            "date" to due, "displayedDue" to due, "anchorDay" to row.anchorDay.toString(),
            "start" to (row.currentPeriodStartOverride?.toString() ?: ""), "periodDue" to (row.currentPeriodEnd?.toString() ?: ""))
    }
    fun validate(f: Map<String,String>): Map<String,Int> {
        val result = mutableMapOf<String,Int>()
        fun check(key: String, block: () -> Unit) { try { block() } catch (_: Exception) { result[key] = R.string.editor_invalid } }
        check("name") { require(!f["name"].isNullOrBlank()) }
        check("amount") { RubPrice.parse(f["amount"].orEmpty()) }
        try { scheduleEdit(f) }
        catch (error: ScheduleFieldError) { result[error.field] = R.string.editor_invalid }
        check("start") {
            f["start"]?.takeIf(String::isNotBlank)?.let {
                val start = LocalDate.parse(it)
                require(start <= owner.today() && start < LocalDate.parse(f["periodDue"].orEmpty().ifBlank { f.getValue("date") }))
            }
        }
        return result
    }
    /** The same decision and full new-series validation serve both validate() and save(). */
    private fun scheduleEdit(f: Map<String,String>): ScheduleEdit? = scheduleEdit(f, expected?.toDomain(), owner.today())

    private class ScheduleFieldError(val field: String) : IllegalArgumentException()
    companion object {
        private val FIELD_KEYS = setOf("name", "label", "amount", "unit", "count", "date", "displayedDue", "anchorDay", "start", "periodDue")
        private inline fun <T> scheduleField(key: String, block: () -> T): T = try { block() }
            catch (error: CancellationException) { throw error }
            catch (_: Exception) { throw ScheduleFieldError(key) }

        private fun scheduleEdit(f: Map<String,String>, original: SubscriptionRecord?, today: LocalDate): ScheduleEdit? {
            val frequency = scheduleField("count") {
                Frequency(FrequencyUnit.valueOf(f.getValue("unit")), f.getValue("count").toInt())
            }
            val date = scheduleField("date") {
                if (original != null && f["date"].isNullOrBlank() && f["displayedDue"] == "") null
                else LocalDate.parse(f.getValue("date"))
            }
            val day = scheduleField("anchorDay") { f["anchorDay"]?.takeIf(String::isNotBlank)?.toInt() }
            // displayedDue is the date originally loaded into this draft, not a new series anchor.
            // It remains stable on recreation and when today advances. An absent refinement inherits
            // the stored anchor if the user reverted the other schedule fields to their loaded values.
            val keep = original != null && (date?.toString() ?: "") == f["displayedDue"] && frequency == original.schedule.frequency &&
                (!f.containsKey("anchorDay") || (day ?: date?.dayOfMonth) == original.schedule.anchor.day)
            if (keep) return null
            val firstDue = scheduleField("date") {
                require(original == null || original.state == SubscriptionState.ACTIVE)
                requireNotNull(date).also { require(it >= today) }
            }
            scheduleField("anchorDay") { RecurringSchedule.starting(firstDue, frequency, day) }
            return ScheduleEdit(firstDue, frequency, day)
        }

        /** Explicit new-series input; unchanged edits must use the nullable decision above. */
        fun schedule(f: Map<String,String>): ScheduleEdit = requireNotNull(scheduleEdit(f, null, LocalDate.MIN))
    }
}
