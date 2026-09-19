package com.tiramisu.deepseekwidget

import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Device-free guard for the failure mode that fills round 3's regression:
 *
 * `RemoteViewsInflater` only inflates classes annotated with `@RemoteView`. A single stray
 * `<View>` (or `Space` / `ViewGroup`) anywhere in a widget layout makes the LAUNCHER reject the
 * whole RemoteViews at runtime (`AppWidgetHostView` → VIEW_MODE_ERROR, "An error occurred when
 * loading widget") — while the APK still builds and all unit tests stay green.
 *
 * The allowlist below was verified against `android-34/android.jar` with `javap -v`
 * (`RemoteViews$RemoteView` annotation present):
 *   LinearLayout/FrameLayout/RelativeLayout/GridLayout/TextView/ImageView/Button/ImageButton/
 *   ProgressBar/ViewStub/ListView/GridView/StackView/ViewFlipper/AdapterViewFlipper/
 *   Chronometer/AnalogClock  → annotated
 *   android.view.View / android.view.ViewGroup / android.widget.Space → NOT annotated
 */
class RemoteViewsLayoutGuardTest {

    private val allowedTags = setOf(
        "LinearLayout", "FrameLayout", "RelativeLayout", "GridLayout",
        "TextView", "ImageView", "Button", "ImageButton", "ProgressBar",
        "ViewStub", "ListView", "GridView", "StackView", "ViewFlipper",
        "AdapterViewFlipper", "Chronometer", "AnalogClock"
    )

    @Test
    fun widgetLayouts_onlyUseRemoteViewInflatableClasses() {
        for (name in listOf("widget_detailed_layout.xml", "widget_layout.xml")) {
            val file = resolveLayout(name)
            val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
            val elements = doc.getElementsByTagName("*")

            val offenders = LinkedHashMap<String, Int>()
            for (i in 0 until elements.length) {
                val tag = (elements.item(i) as Element).tagName
                if (tag !in allowedTags) offenders[tag] = (offenders[tag] ?: 0) + 1
            }

            assertTrue(
                "$name contains classes RemoteViewsInflater cannot inflate: $offenders " +
                    "(file: ${file.absolutePath})",
                offenders.isEmpty()
            )
        }
    }

    /** Walks up from the working directory so the test is not tied to one Gradle layout. */
    private fun resolveLayout(name: String): File {
        val relative = "src/main/res/layout/$name"
        val prefixed = "app/src/main/res/layout/$name"
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            File(dir, relative).takeIf { it.isFile }?.let { return it }
            File(dir, prefixed).takeIf { it.isFile }?.let { return it }
            dir = dir.parentFile
        }
        throw AssertionError("$name not found from cwd=${File("").absolutePath}")
    }
}
