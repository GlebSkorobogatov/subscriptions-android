package com.glebskorobogatov.subscriptiontracker.ui.review

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue

/** Offline PNG index, never a hosted service or a production file. */
internal class ReviewArtifacts(private val group: String) {
    private val directory = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let(::File)
        ?: File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "g03-review")
    private val records = mutableListOf<JSONObject>()
    fun capture(compose: ComposeContentTestRule,scene: ReviewScene,theme: String,font: Float,density: Float,variant: String = "") {
        val dialog = compose.onAllNodes(isDialog()).fetchSemanticsNodes().isNotEmpty()
        val target = if(dialog) compose.onNode(isDialog()) else compose.onNodeWithTag("ui06_viewport")
        val node = target.fetchSemanticsNode()
        val slug = scene.name.replace(Regex("([a-z])([A-Z])"),"$1-$2").lowercase(Locale.ROOT)
        val viewport = compose.onNodeWithTag("ui06_viewport").fetchSemanticsNode()
        val width = (viewport.size.width / density).toInt(); val height = (viewport.size.height / density).toInt()
        val suffix = if(variant.isEmpty()) "" else "-$variant"
        val file = File(directory,"ui06-$group-$slug-$theme-${width}x${height}-f$font$suffix.png")
        file.parentFile!!.mkdirs()
        file.outputStream().use { target.captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG,100,it) }
        assertTrue("PNG must be written: ${file.name}",file.length() > 0)
        records += JSONObject().put("file",file.name).put("scene",scene.name).put("variant",variant).put("theme",theme).put("fontScale",font)
            .put("viewportDp","${width}x$height").put("imagePx","${node.size.width}x${node.size.height}").put("modalWindow",dialog)
    }
    fun finish() {
        directory.mkdirs()
        File(directory,"ui06-$group-manifest.json").writeText(JSONObject().put("source","fictional debug fixtures; no production dataset")
            .put("manualVisualReview","NOT RUN").put("images",JSONArray(records)).toString(2))
        val figures = records.joinToString("\n") { r ->
            val name = r.getString("file")
            "<figure><a href='$name'><img loading='lazy' src='$name' alt='${r.getString("scene")}'></a><figcaption>${r.getString("scene")} · ${r.getString("theme")} · ${r.getString("viewportDp")} dp · шрифт ×${r.getDouble("fontScale")}<br><code>$name</code></figcaption></figure>"
        }
        File(directory,"ui06-$group-index.html").writeText("""<!doctype html><html lang="ru"><meta charset="utf-8"><meta name="viewport" content="width=device-width"><title>UI-06 — $group</title>
            <style>body{font:16px system-ui;margin:24px;background:#f7f7f5;color:#171717}main{display:flex;flex-wrap:wrap;align-items:start;gap:24px}figure{margin:0;max-width:420px}img{display:block;max-width:100%;height:auto;border:1px solid #ccc}figcaption{padding:12px 0;overflow-wrap:anywhere}code{font-size:12px}</style>
            <h1>UI-06 — $group</h1><p>Автоматически полученные PNG. Ручной визуальный просмотр: NOT RUN. Подписи сцен см. в docs/UI-06-review-checklist.md.</p><main>$figures</main></html>""".trimIndent())
    }
}
