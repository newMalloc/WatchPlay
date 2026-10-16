package com.watchplay.sdk.render

import com.watchplay.sdk.protocol.Capabilities
import com.watchplay.sdk.protocol.DisplayCaps
import com.watchplay.sdk.protocol.ScreenShape

/**
 * The rectangle of a watch display a scene may rely on for **information**, in watch pixels.
 *
 * `06-rendering.md` §2.2 separates three nested regions:
 *
 * ```
 *   screen edge (0)  >  inscribed square (146.4‰)  >  recommended safe area (100–146‰, default 140‰)
 * ```
 *
 * Decorative full-bleed artwork may exceed the safe area, even filling the whole circumscribed square;
 * only *information* must stay inside. On a round panel the corners of the circumscribed square do not
 * exist physically, so [left]/[top]/[right]/[bottom] describe the box a scene can trust.
 *
 * @property left offset from the panel's left edge.
 * @property top offset from the panel's top edge.
 * @property right offset from the panel's left edge.
 * @property bottom offset from the panel's top edge.
 */
data class SafeArea(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {

    /** Width of the safe area in watch pixels. */
    val width: Float
        get() = right - left

    /** Height of the safe area in watch pixels. */
    val height: Float
        get() = bottom - top

    /** `true` when the point lies inside the safe area, edges included. */
    fun contains(x: Float, y: Float): Boolean = x >= left && x <= right && y >= top && y <= bottom

    override fun toString(): String = "SafeArea(%.2f, %.2f .. %.2f, %.2f)".format(left, top, right, bottom)
}

/**
 * Geometry and safe-area contract of one watch panel — the phone's model of `CAPS.DISPLAY`.
 *
 * ## Design coordinates
 *
 * Scenes are authored against a **192 design-unit** square (`06-rendering.md` §1.1), then rendered at
 * the panel's real pixel size. [cssPxPerDu] is therefore `minSidePx / 192`; the spec forbids baking
 * that factor (or any absolute pixel value) into scene CSS — sizes must be relative units.
 *
 * ## Safe-area defaults (`06-rendering.md` §2.4)
 *
 * | shape | recommended range (‰) | default (‰) |
 * | :--- | ---: | ---: |
 * | round | 100–146 | **140** |
 * | rectangular / rounded-rect | 25–50 | **40** |
 *
 * **The 14.645% (146.447‰) figure is a hard geometric limit, not a preference**: a centred, axis-aligned
 * square inside a circle of diameter `S` can have half-side at most `S/(2√2)`, so its inset is
 * `(1 − 1/√2)/2 = 0.146447·S`. Any smaller inset and pixels necessarily fall outside the circle. Do not
 * "fix" [ROUND_INSCRIBED_HARD_LIMIT_PERMILLE] upward.
 *
 * ## The inset is reported by the watch, not invented here
 *
 * `06-rendering.md` §2.3 R1/R2 are normative: the phone **MUST** inject the watch's
 * `safe_inset_permille` verbatim, and **MUST NOT** substitute its own idea of a good margin, because
 * only the watch knows its bezel and corner radius. [fromDisplay] therefore keeps a reported value
 * exactly (clamped only to the protocol's `0..499` field range) and falls back to the defaults above
 * only when the watch reports `0` or omits `DISPLAY` (`06-rendering.md` §2.3, third bullet).
 *
 * @property shape panel shape.
 * @property widthPx panel width in watch pixels.
 * @property heightPx panel height in watch pixels.
 * @property designBaselineDu design grid the scene is authored against; 192 by convention.
 * @property safeInsetPermille inset per edge, in per-mille of the shorter side (`0..499`).
 */
data class WatchLayoutSpec(
    val shape: ScreenShape = ScreenShape.ROUND,
    val widthPx: Int = DEFAULT_PANEL_PX,
    val heightPx: Int = DEFAULT_PANEL_PX,
    val designBaselineDu: Int = DESIGN_BASELINE_DU,
    val safeInsetPermille: Int = defaultSafeInsetPermille(shape),
) {

    init {
        require(widthPx > 0 && heightPx > 0) { "panel size must be positive: ${widthPx}x$heightPx" }
        require(designBaselineDu > 0) { "designBaselineDu must be positive" }
        // 500 would collapse the safe area to nothing and is explicitly invalid (`06-rendering.md` §2.3).
        require(safeInsetPermille in 0..MAX_SAFE_INSET_PERMILLE) {
            "safeInsetPermille must be 0..$MAX_SAFE_INSET_PERMILLE, was $safeInsetPermille"
        }
    }

    /** Shorter side in pixels; every safe-area figure is a fraction of this. */
    val minSidePx: Int
        get() = minOf(widthPx, heightPx)

    /** The inset as a fraction of the shorter side. */
    val safeInsetFraction: Float
        get() = safeInsetPermille / 1000f

    /** The inset in watch pixels. */
    val safeInsetPx: Float
        get() = safeInsetFraction * minSidePx

    /** The safe rectangle, in watch pixels. */
    val safeArea: SafeArea
        get() {
            val inset = safeInsetPx
            return SafeArea(inset, inset, widthPx - inset, heightPx - inset)
        }

    /** CSS pixels per design unit when the scene fills the panel (`minSidePx / 192`). */
    val cssPxPerDu: Float
        get() = minSidePx.toFloat() / designBaselineDu

    /** The value `06-rendering.md` §2.3 R1 requires the phone to inject, e.g. `"14vmin"`. */
    val cssSafeInsetValue: String
        get() = cssSafeInsetValue(safeInsetPermille)

    /** The value injected as `data-screen-shape`: `round` or `square`. */
    val cssScreenShape: String
        get() = shape.cssScreenShape

    override fun toString(): String =
        "WatchLayoutSpec(${shape.name} ${widthPx}x$heightPx, inset=${safeInsetPermille}‰ " +
            "(${safeInsetPx}px, ${cssSafeInsetValue}), baseline=${designBaselineDu}du, safeArea=$safeArea)"

    companion object {
        /** Design grid every scene is authored against (`06-rendering.md` §1.1). */
        const val DESIGN_BASELINE_DU: Int = 192

        /** Reference panel size: LilyGO T-Display S3 AMOLED 1.43", 466×466. */
        const val DEFAULT_PANEL_PX: Int = 466

        /** Largest legal `safe_inset_permille`; 500 would make the safe area empty. */
        const val MAX_SAFE_INSET_PERMILLE: Int = 499

        /** Recommended default inset on a round panel (`06-rendering.md` §2.4). */
        const val ROUND_DEFAULT_PERMILLE: Int = 140

        /** Recommended minimum inset on a round panel. */
        const val ROUND_RECOMMENDED_MIN_PERMILLE: Int = 100

        /**
         * Geometric hard limit on a round panel: `(1 − 1/√2)/2 = 0.146447`, i.e. **14.645%** or
         * 146.447‰ (`06-rendering.md` §2.1). Smaller insets clip content; this is geometry, not taste.
         */
        const val ROUND_INSCRIBED_HARD_LIMIT_PERMILLE: Int = 146

        /** Recommended default inset on a rectangular panel. */
        const val RECT_DEFAULT_PERMILLE: Int = 40

        /** Recommended minimum inset on a rectangular panel. */
        const val RECT_RECOMMENDED_MIN_PERMILLE: Int = 25

        /** Recommended maximum inset on a rectangular panel. */
        const val RECT_RECOMMENDED_MAX_PERMILLE: Int = 50

        /**
         * Default inset the phone applies when the watch reports `0` (`06-rendering.md` §2.4).
         *
         * [ScreenShape.UNKNOWN] maps to `0`: an unknown panel gets no invented margin, because guessing
         * round-screen margins on an unknown panel would waste a third of the width.
         */
        fun defaultSafeInsetPermille(shape: ScreenShape): Int = when (shape) {
            ScreenShape.ROUND -> ROUND_DEFAULT_PERMILLE
            ScreenShape.RECTANGULAR, ScreenShape.ROUNDED_RECT -> RECT_DEFAULT_PERMILLE
            ScreenShape.UNKNOWN -> 0
        }

        /**
         * Formats an inset for CSS injection exactly as `06-rendering.md` §2.3 R1 requires:
         * `--wp-safe-inset: (safe_inset_permille / 10)vmin`, e.g. `140 -> "14vmin"`, `146 -> "14.6vmin"`.
         *
         * `vmin` rather than `%` is mandatory: `%` padding resolves against the containing block's
         * *width*, which is wrong on a non-square panel, and `1vmin` already equals 1% of the shorter
         * side, which is exactly what per-mille-of-short-side means.
         */
        fun cssSafeInsetValue(permille: Int): String {
            val clamped = permille.coerceIn(0, MAX_SAFE_INSET_PERMILLE)
            val whole = clamped / 10
            val tenth = clamped % 10
            return if (tenth == 0) "${whole}vmin" else "$whole.${tenth}vmin"
        }

        /** A round layout, e.g. the 466×466 reference panel. */
        fun round(
            widthPx: Int = DEFAULT_PANEL_PX,
            heightPx: Int = widthPx,
            insetPermille: Int = ROUND_DEFAULT_PERMILLE,
        ): WatchLayoutSpec = WatchLayoutSpec(ScreenShape.ROUND, widthPx, heightPx, DESIGN_BASELINE_DU, insetPermille)

        /** A rectangular (or rounded-rectangular) layout. */
        fun rectangular(
            widthPx: Int = DEFAULT_PANEL_PX,
            heightPx: Int = widthPx,
            shape: ScreenShape = ScreenShape.RECTANGULAR,
            insetPermille: Int = RECT_DEFAULT_PERMILLE,
        ): WatchLayoutSpec = WatchLayoutSpec(shape, widthPx, heightPx, DESIGN_BASELINE_DU, insetPermille)

        /**
         * Builds a layout from the watch's own `DISPLAY` TLV, honouring `06-rendering.md` §2.3.
         *
         * The watch's value is used **verbatim** whenever it is non-zero (R1/R2). Only a reported `0`
         * falls back to the defaults of §2.4, and the value is clamped to the protocol's field range
         * `0..499` rather than to any recommended band — a 400‰ panel is legal, if unusual.
         */
        fun fromDisplay(display: DisplayCaps): WatchLayoutSpec = WatchLayoutSpec(
            shape = display.shape,
            widthPx = display.widthPx,
            heightPx = display.heightPx,
            designBaselineDu = DESIGN_BASELINE_DU,
            safeInsetPermille = if (display.safeInsetPermille > 0) {
                display.safeInsetPermille.coerceAtMost(MAX_SAFE_INSET_PERMILLE)
            } else {
                defaultSafeInsetPermille(display.shape)
            },
        )

        /** Convenience overload for a whole `CAPS` payload; falls back to the reference panel. */
        fun fromCapabilities(capabilities: Capabilities): WatchLayoutSpec =
            capabilities.display?.let { fromDisplay(it) } ?: WatchLayoutSpec()
    }
}
