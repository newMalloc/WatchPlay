package com.watchplay.sdk.render

import com.watchplay.sdk.protocol.ScreenShape
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the safe-area injection of `06-rendering.md` §2.3 R1/R2 and §3.4.
 *
 * The three carriers are the whole point: the value must reach the document *before the first paint*
 * and must not be overridden by the scene's own fallback. That makes ordering observable, so it is
 * asserted directly instead of trusting that the CSS cascade "just works".
 */
class WebViewRenderModuleTest {

    private val round = WatchLayoutSpec.round()
    private val square = WatchLayoutSpec.rectangular(insetPermille = 40)

    private fun scene(html: String): String = injectNegotiatedCss(html, round)

    @Test
    fun `marker is replaced with the negotiated inset`() {
        val html = "<html lang=\"zh\">\n<style>:root{ --wp-safe-inset:14vmin; ${WebViewRenderModule.INJECTION_MARKER} }</style>"

        val injected = scene(html)

        assertFalse(injected.contains(WebViewRenderModule.INJECTION_MARKER))
        assertTrue(injected.contains("--wp-safe-inset:14vmin; --wp-safe-inset:14vmin;"))
    }

    @Test
    fun `marker replacement lands after the scene's own fallback`() {
        val html = "<html><style>:root{ --wp-safe-inset:40vmin; ${WebViewRenderModule.INJECTION_MARKER} }</style>"

        val injected = scene(html)

        val fallback = injected.indexOf("--wp-safe-inset:40vmin")
        val negotiated = injected.lastIndexOf("--wp-safe-inset:14vmin")
        assertTrue("fallback must come first, otherwise it wins the cascade", fallback in 0 until negotiated)
    }

    @Test
    fun `a scene without a marker gets the value as an inline style`() {
        val html = "<html lang=\"zh\">\n<style>:root{ --wp-safe-inset:40vmin; }</style>\n</html>"

        val injected = scene(html)

        assertTrue(injected.contains("<html lang=\"zh\" data-screen-shape=\"round\" style=\"--wp-safe-inset:14vmin;\">"))
    }

    @Test
    fun `an existing style attribute is merged, not replaced`() {
        val html = "<html style=\"color:red\">x</html>"

        val injected = scene(html)

        assertEquals("<html style=\"--wp-safe-inset:14vmin; color:red\" data-screen-shape=\"round\">x</html>", injected)
    }

    @Test
    fun `square panels are announced as square`() {
        val injected = injectNegotiatedCss("<html><style>:root{ --wp-safe-inset:14vmin; }</style></html>", square)

        assertTrue(injected.contains("data-screen-shape=\"square\""))
        assertTrue(injected.contains("--wp-safe-inset:4vmin;"))
    }

    @Test
    fun `a shape the scene already declares is left alone`() {
        val html = "<html data-screen-shape=\"square\"><style>:root{ --wp-safe-inset:14vmin; }</style></html>"

        val injected = injectNegotiatedCss(html, round)

        assertEquals(1, Regex("data-screen-shape").findAll(injected).count())
        assertTrue(injected.contains("data-screen-shape=\"square\""))
    }

    @Test
    fun `a mention of data-screen-shape outside the html tag does not block injection`() {
        // A CSS selector or comment is not a declaration on <html>; scanning the whole document for the
        // attribute name (the previous behaviour) silently dropped the shape.
        val html = "<html>\n<style>html[data-screen-shape=\"square\"]{ --wp-safe-inset:4vmin; }</style>\n</html>"

        val injected = scene(html)

        assertTrue(injected.contains("<html data-screen-shape=\"round\" style=\"--wp-safe-inset:14vmin;\">"))
    }

    @Test
    fun `a fragment without an html element is still given a root`() {
        val injected = scene("<div>scene</div>")

        assertEquals(
            "<html data-screen-shape=\"round\" style=\"--wp-safe-inset:14vmin;\"><div>scene</div></html>",
            injected,
        )
    }

    @Test
    fun `rounded rectangles are announced as square too`() {
        val rounded = WatchLayoutSpec(shape = ScreenShape.ROUNDED_RECT, safeInsetPermille = 40)

        val injected = injectNegotiatedCss("<html></html>", rounded)

        assertTrue(injected.contains("data-screen-shape=\"square\""))
    }
}
