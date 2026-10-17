package com.watchplay.sdk.render

import com.watchplay.sdk.protocol.ScreenShape

/**
 * The outline of a watch panel, in watch pixels — what a phone-side preview must clip to.
 *
 * `06-rendering.md` §2.2 lets a scene draw full-bleed and leaves the clipping to the panel: on a round
 * display the corners of the circumscribed square physically do not exist. A preview that draws them
 * anyway shows pixels the watch can never emit, which is why the demo masks the mirrored scene, the frame
 * thumbnail and the 1:1 frame dialog with this outline.
 *
 * **The scene must not do this itself.** It cannot know the corner radius (§2.3 R1 forbids it from
 * inventing geometry), and a `border-radius` on the document would clip content that is legitimately
 * allowed to bleed while removing nothing that the panel would keep.
 */
object WatchScreenShape {

    /**
     * `true` when the panel is a circle, i.e. a `ROUND` panel with equal sides.
     *
     * A `ROUND` panel reporting unequal sides is contradictory; it becomes a stadium rather than a circle
     * (see [cornerRadiusPx]) so the preview still never shows a corner the panel lacks.
     */
    fun isCircular(layout: WatchLayoutSpec): Boolean =
        layout.shape == ScreenShape.ROUND && layout.widthPx == layout.heightPx

    /**
     * Corner radius in watch pixels.
     *
     *  * `ROUND` → half the shorter side, which on a square panel *is* the circle.
     *  * `ROUNDED_RECT` → the reported safe inset. The protocol carries no corner radius, and the safe
     *    inset is by definition the margin the watch keeps clear of its own corner, so using it as the
     *    radius neither invents a tighter corner than the panel has nor cuts into content the watch
     *    promised to show.
     *  * `RECTANGULAR` / `UNKNOWN` → `0`, i.e. a sharp rectangle: an unknown panel must not be guessed
     *    round (`07-capabilities.md` §4.1).
     */
    fun cornerRadiusPx(layout: WatchLayoutSpec): Float = when (layout.shape) {
        ScreenShape.ROUND -> layout.minSidePx / 2f
        // The protocol caps `safe_inset_permille` at 499, so this always stays below half the side and a
        // rounded-rect can never degrade into a stadium by accident.
        ScreenShape.ROUNDED_RECT -> layout.safeInsetPx
        ScreenShape.RECTANGULAR, ScreenShape.UNKNOWN -> 0f
    }

    /**
     * `true` when the outline clips any pixel away, i.e. when a preview needs a mask at all.
     *
     * A plain rectangle needs no `clipToOutline`, and skipping it keeps capture and read-back untouched.
     */
    fun hasClippedCorners(layout: WatchLayoutSpec): Boolean =
        isCircular(layout) || cornerRadiusPx(layout) > 0f

    /** One-line description for logs and the demo's stats pane. */
    fun describe(layout: WatchLayoutSpec): String =
        "${layout.shape.name.lowercase()} ${layout.widthPx}x${layout.heightPx}" +
            if (isCircular(layout)) ", circle" else ", radius=%.1fpx".format(cornerRadiusPx(layout))
}
