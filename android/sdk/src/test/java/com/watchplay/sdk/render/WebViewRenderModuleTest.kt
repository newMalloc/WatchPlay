package com.watchplay.sdk.render

import com.watchplay.sdk.protocol.ScreenShape
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the injection of `06-rendering.md` §2.3 R1/R3 and §3.4.
 *
 * The interesting property is not "the string appears" but **which carrier wins the cascade** on a square
 * panel: a scene keys its fallback on shape (`html[data-screen-shape="square"]{--wp-safe-inset:4vmin}`,
 * specificity `0,1,1`), which outranks `:root` (`0,1,0`). The negotiated value therefore has to be an
 * inline declaration, and the shape attribute has to be overwritten rather than merely added.
 * §8's checklist states the same requirement in prose: "注入值……未被任何本地默认值覆盖".
 */
class WebViewRenderModuleTest {

    private val round = WatchLayoutSpec.round()
    private val square = WatchLayoutSpec.rectangular(insetPermille = 40)

    /** A scene written the way §3.3/§3.4 show: shape-keyed fallbacks plus the marker. */
    private val typicalScene = """
        <html lang="zh-CN" data-screen-shape="round">
        <style>
        :root{ --wp-safe-inset:14vmin; ${WebViewRenderModule.INJECTION_MARKER} }
        html[data-screen-shape="square"]{ --wp-safe-inset:4vmin; }
        </style>
        </html>
    """.trimIndent()

    private fun openTag(html: String): String =
        Regex("<html[^>]*>", RegexOption.IGNORE_CASE).find(html)?.value ?: error("no <html> tag")

    @Test
    fun `the negotiated inset is declared inline so no stylesheet rule can outrank it`() {
        val injected = injectNegotiatedCss(typicalScene, round)

        assertTrue(
            "inline declaration missing from <html>: ${openTag(injected)}",
            openTag(injected).contains("style=\"--wp-safe-inset:14vmin;\""),
        )
    }

    @Test
    fun `the marker is also substituted for scenes that expect it`() {
        val injected = injectNegotiatedCss(typicalScene, round)

        assertTrue(!injected.contains(WebViewRenderModule.INJECTION_MARKER))
        assertTrue(injected.contains(":root{ --wp-safe-inset:14vmin; --wp-safe-inset:14vmin; }"))
    }

    @Test
    fun `a scene default shape is overwritten with the reported one`() {
        val injected = injectNegotiatedCss(typicalScene, square)

        val tag = openTag(injected)
        assertTrue("expected square, got: $tag", tag.contains("data-screen-shape=\"square\""))
        assertEquals(1, Regex("data-screen-shape").findAll(tag).count())
        assertTrue(injected.contains("--wp-safe-inset:4vmin;"))
    }

    @Test
    fun `a missing shape attribute is added`() {
        val injected = injectNegotiatedCss("<html lang=\"zh\">x</html>", square)

        assertEquals("<html lang=\"zh\" data-screen-shape=\"square\" style=\"--wp-safe-inset:4vmin;\">x</html>", injected)
    }

    @Test
    fun `an existing style attribute is merged and a stale inset is replaced`() {
        val injected = injectNegotiatedCss("<html style=\"--wp-safe-inset:9vmin; color:red\">x</html>", round)

        assertEquals("<html style=\"--wp-safe-inset:14vmin; color:red\" data-screen-shape=\"round\">x</html>", injected)
    }

    @Test
    fun `single-quoted style attributes are understood`() {
        val injected = injectNegotiatedCss("<html style='color:red'>x</html>", round)

        assertEquals("<html style=\"--wp-safe-inset:14vmin; color:red\" data-screen-shape=\"round\">x</html>", injected)
    }

    @Test
    fun `a mention of data-screen-shape outside the html tag does not block injection`() {
        val html = "<html>\n<style>html[data-screen-shape=\"square\"]{ --wp-safe-inset:4vmin; }</style>\n</html>"

        val injected = injectNegotiatedCss(html, round)

        assertEquals("<html data-screen-shape=\"round\" style=\"--wp-safe-inset:14vmin;\">", openTag(injected))
    }

    @Test
    fun `a fragment without an html element is still given a root`() {
        val injected = injectNegotiatedCss("<div>scene</div>", round)

        assertEquals(
            "<html data-screen-shape=\"round\" style=\"--wp-safe-inset:14vmin;\"><div>scene</div></html>",
            injected,
        )
    }

    @Test
    fun `rounded rectangles are announced as square`() {
        val rounded = WatchLayoutSpec(shape = ScreenShape.ROUNDED_RECT, safeInsetPermille = 40)

        val injected = injectNegotiatedCss("<html></html>", rounded)

        assertEquals("<html data-screen-shape=\"square\" style=\"--wp-safe-inset:4vmin;\"></html>", injected)
    }

    @Test
    fun `a reported inset that is not a whole vmin keeps its tenth`() {
        val odd = WatchLayoutSpec.round(insetPermille = 146)

        val injected = injectNegotiatedCss("<html></html>", odd)

        assertTrue(openTag(injected).contains("style=\"--wp-safe-inset:14.6vmin;\""))
    }
}
