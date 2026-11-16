package com.glebskorobogatov.subscriptiontracker

import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/** JVM-only check of the launcher contract and its real Russian resources, not an Android UI test. */
class ScaffoldSmokeTest {
    @Test
    fun launcherHasRussianResourcesAndOnlyNotificationPermission() {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setAttribute("http://javax.xml.XMLConstants/property/accessExternalDTD", "")
            setAttribute("http://javax.xml.XMLConstants/property/accessExternalSchema", "")
        }
        val classLoader = checkNotNull(javaClass.classLoader)
        fun readResource(path: String) = checkNotNull(classLoader.getResourceAsStream(path)) {
            "Missing scaffold resource: $path"
        }.use { factory.newDocumentBuilder().parse(it) }

        val android = "http://schemas.android.com/apk/res/android"
        val manifest = readResource("AndroidManifest.xml")
        assertEquals(1, manifest.getElementsByTagName("uses-permission").length)
        assertEquals("android.permission.POST_NOTIFICATIONS", (manifest.getElementsByTagName("uses-permission").item(0) as Element).getAttributeNS(android, "name"))
        val activity = manifest.getElementsByTagName("activity").item(0) as Element
        assertEquals("true", activity.getAttributeNS(android, "exported"))
        val action = activity.getElementsByTagName("action").item(0) as Element
        val category = activity.getElementsByTagName("category").item(0) as Element
        assertEquals("android.intent.action.MAIN", action.getAttributeNS(android, "name"))
        assertEquals("android.intent.category.LAUNCHER", category.getAttributeNS(android, "name"))

        val nodes = readResource("res/values/strings.xml").getElementsByTagName("string")
        val strings = (0 until nodes.length).associate { index ->
            val element = nodes.item(index) as Element
            element.getAttribute("name") to element.textContent
        }
        val application = manifest.getElementsByTagName("application").item(0) as Element
        val label = application.getAttributeNS(android, "label").removePrefix("@string/")
        assertEquals("Менеджер подписок", strings[label])
        assertTrue(checkNotNull(strings["welcome_message"]).contains("Добро пожаловать"))
    }
}
