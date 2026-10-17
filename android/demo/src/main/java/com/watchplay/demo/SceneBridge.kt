package com.watchplay.demo

import android.webkit.JavascriptInterface

/**
 * The bridge a scene uses to send a **command** back to the app.
 *
 * The touch path stays native (`05-input.md` §2.4): the watch's `TOUCH` becomes a real `MotionEvent`, the
 * WebView dispatches it, and the scene turns the resulting click into `window.watchplayHost.onControl(…)`.
 * That keeps hit-testing inside the scene — where the 7 mm targets actually are — instead of duplicating
 * the geometry in the app.
 *
 * The class is public because `addJavascriptInterface` reflects over public methods; a private or
 * anonymous type would silently expose nothing.
 */
class SceneBridge(private val onControl: (String) -> Unit) {

    @JavascriptInterface
    fun onControl(control: String) {
        onControl(control)
    }
}
