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
import com.glebskorobogatov.subscriptiontracker.domain.lifecycle.*
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
    val importingImage: Boolean = false, val catalogLogoId: String? = null)

class EditorViewModel(private val saved: SavedStateHandle, private val owner: SubscriptionsViewModel,
    private val id: UUID?) : ViewModel() {
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
        if (state.value.saving || key in setOf("currency","lead","PAYMENT","CANCELLATION")) return
        val fields = state.value.fields + (key to value)
        saved["fields"] = HashMap(fields)
        mutable.value = state.value.copy(fields = fields, dirty = true, errors = emptyMap(), error = null)
    }
    fun selectImage(uri: android.net.Uri) {
        if (state.value.saving || state.value.importingImage) return
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
        val previous = pendingImage
        pendingImage = null; saved.remove<String>("pendingImage")
        catalogChoice = null; saved.remove<String>("catalogChoice")
        imageRemoved = true; saved["imageRemoved"] = true
        mutable.value = state.value.copy(pendingImageId = null, catalogLogoId = null, imageRemoved = true, dirty = true)
        if (previous != null) viewModelScope.launch { owner.perform { it.discardImage(previous) } }
    }
    fun selectCatalog(catalogId: String) {
        if (state.value.saving || state.value.importingImage || OfflineIconCatalog.find(catalogId) == null) return
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
                check(owner.state.first { it !is com.glebskorobogatov.subscriptiontracker.ui.subscriptions.SubscriptionsUiState.Loading } !is com.glebskorobogatov.subscriptiontracker.ui.subscriptions.SubscriptionsUiState.Error)
                val detail = owner.perform { it.details(id).first() }
                if(detail == null) {
                    mutable.value = state.value.copy(loading = false,notFound = true,loadFailure = false,error = null)
                    return@launch
                }
                expected = detail.subscription
                fromRow(detail.subscription)
            }
            val oldDraft = if (discard) null else saved.get<HashMap<String,String>>("fields")
            val currencyChanged = oldDraft?.get("currency")?.let { it != fields.getValue("currency") } == true
            val restored = oldDraft?.filterKeys { it !in setOf("lead","PAYMENT","CANCELLATION") }?.toMutableMap()?.apply {
                put("currency",fields.getValue("currency"))
                if (currencyChanged) put("amount","") // Never relabel a saved foreign-currency amount.
            }
            restored?.let { saved["fields"] = HashMap(it) }
            val fingerprint = expected.toString()
            val stale = restored != null && saved.get<String>("expected")?.let { it != fingerprint && !it.startsWith(fingerprint + "[") } == true
            if (!stale) saved["expected"] = fingerprint
            if (discard) discardImageDraft()
            mutable.value = EditorState(restored ?: fields, loading = false, dirty = restored != null || pendingImage != null || imageRemoved || catalogChoice != null,
                errors = if (currencyChanged) mapOf("amount" to R.string.editor_price_reentry) else emptyMap(),
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
                    val currency = expected?.currencyCode ?: CurrencyCode.of("RUB")
                    val price = Money.parse(fields.getValue("amount"), currency)
                    val schedule = schedule(fields)
                    val mode = RenewalMode.valueOf(fields.getValue("mode"))
                    val uuid = id ?: newId
                    val start = fields["start"]?.takeIf { it.isNotBlank() }?.let(LocalDate::parse)
                    val end = fields["end"]?.takeIf { it.isNotBlank() }?.let(LocalDate::parse)
                    val label = fields["label"]?.trim()?.takeIf { it.isNotEmpty() }
                    val old = expected
                    if (old == null) use.createSubscription(SubscriptionDraft(uuid, fields.getValue("name").trim(), label,
                        price, mode, schedule, start, end, logoAssetId = pendingImage, catalogLogoId = catalogChoice), Instant.now())
                    else {
                        val original = old.toDomain().schedule
                        val date = LocalDate.parse(fields.getValue("date"))
                        val changed = schedule.frequency != original.frequency || fields["anchorMonth"] != original.anchor.yearMonth.toString() || fields["anchorDay"] != original.anchor.day.toString()
                        val edit = when {
                            changed || (fields["series"] == "true" && date != DefaultRenewalCalendar().nextScheduledDate(original)) -> ScheduleEdit.FutureSeries(date, schedule.frequency, if (fields["anchorMonth"] != original.anchor.yearMonth.toString() || fields["anchorDay"] != original.anchor.day.toString()) schedule.anchor else null)
                            date != DefaultRenewalCalendar().nextScheduledDate(original) -> ScheduleEdit.NextOnly(date)
                            else -> ScheduleEdit.Keep
                        }
                        val imageEdit = pendingImage?.let { ImageEdit.Set(it) }
                            ?: catalogChoice?.let { ImageEdit.Catalog(it) }
                            ?: if (imageRemoved) ImageEdit.Remove else ImageEdit.Keep
                        use.editSubscription(old, SubscriptionEdit(fields.getValue("name").trim(), label, price, mode, edit,
                            SubscriptionExtras(start,end), imageEdit), Instant.now())
                    }
                }
                saved.remove<HashMap<String,String>>("fields")
                pendingImage = null; catalogChoice = null
                saved.remove<String>("pendingImage"); saved.remove<Boolean>("imageRemoved"); saved.remove<String>("catalogChoice")
                mutable.value = state.value.copy(saving = false, dirty = false, done = true)
            } catch (e: CancellationException) { throw e }
            catch (_: LifecycleConflict) { mutable.value = state.value.copy(saving = false, conflict = true, error = R.string.editor_conflict) }
            catch (_: Exception) { mutable.value = state.value.copy(saving = false, error = R.string.editor_save_error) }
        }
    }
    private fun defaults() = mapOf("name" to "", "amount" to "", "currency" to "RUB", "unit" to "MONTH", "count" to "1",
        "date" to LocalDate.now().toString(), "mode" to "AUTO", "series" to "false")
    private fun fromRow(row: SubscriptionEntity): Map<String,String> {
        val s = row.toDomain()
        return defaults() + mapOf("name" to row.name, "amount" to s.price.format(), "currency" to row.currencyCode.value,
            "label" to (row.accountLabel ?: ""), "unit" to row.intervalUnit.name, "count" to row.intervalCount.toString(),
            "date" to DefaultRenewalCalendar().nextScheduledDate(s.schedule).toString(), "mode" to row.renewalMode.name,
            "anchorMonth" to s.schedule.anchor.yearMonth.toString(), "anchorDay" to s.schedule.anchor.day.toString(),
            "start" to (row.currentPeriodStartOverride?.toString() ?: ""), "end" to (row.paidAccessEndDate?.toString() ?: ""))
    }
    fun validate(f: Map<String,String>): Map<String,Int> {
        val result = mutableMapOf<String,Int>()
        fun check(key: String, block: () -> Unit) { try { block() } catch (_: Exception) { result[key] = R.string.editor_invalid } }
        check("name") { require(!f["name"].isNullOrBlank()) }
        check("amount") { Money.parse(f["amount"].orEmpty(), expected?.currencyCode ?: CurrencyCode.of("RUB")) }
        check("count") { Frequency(FrequencyUnit.valueOf(f.getValue("unit")), f.getValue("count").toInt()) }
        check("date") { LocalDate.parse(f.getValue("date")) }
        check("anchorDay") { schedule(f) }
        check("start") {
            val start = f["start"]?.takeIf { it.isNotBlank() }?.let(LocalDate::parse)
            val end = f["end"]?.takeIf { it.isNotBlank() }?.let(LocalDate::parse)
            require(start == null || start < LocalDate.parse(f.getValue("date")))
            require(start == null || end == null || start < end)
        }
        check("end") { f["end"]?.takeIf { it.isNotBlank() }?.let(LocalDate::parse) }
        expected?.let { old ->
            val original = old.toDomain().schedule
            val changed = f["unit"] != original.frequency.unit.name || f["count"] != original.frequency.count.toString() ||
                f["anchorMonth"] != original.anchor.yearMonth.toString() || f["anchorDay"] != original.anchor.day.toString()
            if (changed && f["series"] != "true") result["series"] = R.string.editor_series_required
            if (!changed && f["series"] != "true") check("date") { DefaultRenewalCalendar().moveNextOnly(original, LocalDate.parse(f.getValue("date"))) }
        }
        return result
    }
    companion object {
        fun schedule(f: Map<String,String>): RenewalSchedule {
            val date = LocalDate.parse(f.getValue("date"))
            val anchor = RenewalAnchor(f["anchorMonth"]?.takeIf { it.isNotBlank() }?.let(YearMonth::parse) ?: YearMonth.from(date),
                f["anchorDay"]?.takeIf { it.isNotBlank() }?.toInt() ?: date.dayOfMonth)
            val base = RenewalSchedule(anchor, Frequency(FrequencyUnit.valueOf(f.getValue("unit")), f.getValue("count").toInt()))
            val calendar = DefaultRenewalCalendar()
            val index = calendar.firstCycleIndexOnOrAfter(base,date) ?: error("Invalid date")
            return calendar.moveNextOnly(base.copy(nextCycleIndex = index), date)
        }
    }
}
