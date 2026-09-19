package com.tiramisu.deepseekwidget

import android.widget.RemoteViews
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Device-free guard for the failure mode behind the round-3 regression:
 *
 * `RemoteViewsInflater` only inflates classes annotated `@RemoteView`. A single stray `<View>`
 * (or `Space` / `ViewGroup`) anywhere in a widget layout makes the LAUNCHER reject the entire
 * RemoteViews at runtime (AppWidgetHostView → VIEW_MODE_ERROR, "An error occurred when loading
 * widget") — while the APK still builds and every other test stays green.
 *
 * The rule is NOT hardcoded: it is read from the platform classes themselves
 * (`@RemoteView` is @Retention(RUNTIME), verified with `javap -v` on android-34/android.jar).
 * A self-check assertion guards the rule engine, so this test cannot silently degrade into a
 * no-op if the platform annotations ever become unreadable.
 *
 * All `widget*` layouts under res/layout are discovered automatically, so a newly added widget
 * is covered without editing this file.
 */
class RemoteViewsLayoutGuardTest {

    /** Packages an Android layout tag may live in (tags are simple class names). */
    private val candidatePackages = listOf("android.widget.", "android.view.", "android.webkit.")

    @Test
    fun ruleEngine_readsRemoteViewFromPlatform() {
        // Guards the guard: if annotation reading breaks, fail loudly instead of passing blindly.
        assertTrue("FrameLayout must be @RemoteView", isInflatable("FrameLayout"))
        assertTrue("TextView must be @RemoteView", isInflatable("TextView"))
        assertFalse("android.view.View must NOT be @RemoteView", isInflatable("View"))
        assertFalse("Space must NOT be @RemoteView", isInflatable("Space"))
    }

    @Test
    fun widgetLayouts_onlyUseRemoteViewInflatableClasses() {
        val dir = resolveLayoutDir()
        val layouts = dir.listFiles { f -> f.isFile && f.name.startsWith("widget") && f.name.endsWith(".xml") }
            ?.sortedBy { it.name }
            ?: emptyList()

        assertTrue("no widget* layouts found in ${dir.absolutePath}", layouts.isNotEmpty())
        val found = layouts.map { it.name }
        assertTrue(
            "expected widget layouts missing from ${dir.absolutePath}: found=$found",
            found.containsAll(
                listOf("widget_layout.xml", "widget_detailed_layout.xml", "widget_full_layout.xml")
            )
        )

        for (file in layouts) {
            val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
            val elements = doc.getElementsByTagName("*")

            val offenders = LinkedHashMap<String, Int>()
            for (i in 0 until elements.length) {
                val tag = (elements.item(i) as Element).tagName
                if (!isInflatable(tag)) offenders[tag] = (offenders[tag] ?: 0) + 1
            }

            assertTrue(
                "${file.name} contains classes RemoteViewsInflater cannot inflate: $offenders " +
                    "(file: ${file.absolutePath})",
                offenders.isEmpty()
            )
        }
    }

    /** True only when the platform class exists AND carries the @RemoteView annotation. */
    private fun isInflatable(tag: String): Boolean {
        val loader = RemoteViews::class.java.classLoader
        val names = if (tag.contains('.')) listOf(tag) else candidatePackages.map { it + tag }
        for (fqcn in names) {
            try {
                val clazz = Class.forName(fqcn, false, loader)
                return clazz.isAnnotationPresent(RemoteViews.RemoteView::class.java)
            } catch (_: ClassNotFoundException) {
                // try the next candidate package
            } catch (_: Throwable) {
                return false
            }
        }
        return false
    }

    /** Walks up from the working directory so the test is not tied to one Gradle layout. */
    private fun resolveLayoutDir(): File {
        val relative = "src/main/res/layout"
        val prefixed = "app/$relative"
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            File(dir, relative).takeIf { it.isDirectory }?.let { return it }
            File(dir, prefixed).takeIf { it.isDirectory }?.let { return it }
            dir = dir.parentFile
        }
        throw AssertionError("res/layout not found from cwd=${File("").absolutePath}")
    }
}
