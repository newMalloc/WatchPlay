package com.watchplay.demo

import android.graphics.drawable.GradientDrawable
import android.view.View
import com.watchplay.sdk.render.WatchLayoutSpec
import com.watchplay.sdk.render.WatchScreenShape

/**
 * Masks [view] with the outline of the watch panel it is previewing.
 *
 * On a round panel the corners of the square picture physically do not exist, so drawing it as a rounded
 * square is a lie the phone can see: this clips the view to the panel's own shape — a circle for a round
 * panel, the panel's corner radius for a rounded-rect, plain corners for a rectangular one
 * ([WatchScreenShape] explains where each radius comes from).
 *
 * For the mirrored scene the mask is also the only thing that keeps the captured frame honest: `PixelCopy`
 * copies whatever the window composited inside the view's rect, so the clipped corners come back as the
 * card colour instead of scene pixels. Those are exactly the pixels the panel cannot display, which is why
 * the trade is invisible on the watch.
 *
 * @param fillColor backdrop behind the mask; what the clipped corners become.
 * @param strokeWidthPx outline width in pixels, `0` for no stroke.
 */
internal fun maskToPanel(
    view: View,
    layout: WatchLayoutSpec,
    fillColor: Int,
    strokeWidthPx: Int = 0,
    strokeColor: Int = 0,
) {
    val background = GradientDrawable().apply {
        if (WatchScreenShape.isCircular(layout)) {
            shape = GradientDrawable.OVAL
        } else {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = WatchScreenShape.cornerRadiusPx(layout)
        }
        setColor(fillColor)
        if (strokeWidthPx > 0) setStroke(strokeWidthPx, strokeColor)
    }
    view.background = background
    // A plain rectangle has no outline worth clipping to, and staying unclipped keeps read-back untouched.
    view.clipToOutline = WatchScreenShape.hasClippedCorners(layout)
}

/**
 * Gives [this] the panel's aspect ratio, keeping its width.
 *
 * The thumbnail is a square view by default, which would letterbox a 410×502 panel inside a square hole and
 * make its corners look wrong even when the outline is right.
 */
internal fun View.fitPanelAspect(layout: WatchLayoutSpec) {
    val params = layoutParams ?: return
    if (params.width <= 0) return
    params.height = (params.width * layout.heightPx.toFloat() / layout.widthPx).toInt().coerceAtLeast(1)
    layoutParams = params
}
