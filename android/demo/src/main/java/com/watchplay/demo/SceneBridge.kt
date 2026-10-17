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
 *
 * @property command what to do with the command. **Not** named `onControl`: a constructor property and a
 *   method may share a name, and then `onControl(control)` inside the method resolves to the *method* — an
 *   infinite recursion that a tap turns into thousands of log lines and a `StackOverflowError`, with the
 *   app never hearing a thing.
 */
class SceneBridge(private val command: (String) -> Unit) {

    @JavascriptInterface
    fun onControl(control: String) {
        command(control)
    }
}
