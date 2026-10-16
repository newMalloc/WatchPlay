package com.watchplay.sdk.render

/**
 * A watch-screen "scene": one self-contained HTML document (plus its assets) that the phone renders
 * into a WebView and streams to the watch.
 *
 * The scene is the heart of WatchPlay's design insight (`docs/spec/00-overview.md` §1): the phone
 * renders at the watch's native resolution, 1:1, so HTML+CSS is enough to describe the UI and no
 * screen recording or rescaling is involved.
 *
 * A scene is immutable from the SDK's point of view. Live data — the next turn, the current track —
 * is pushed with `UIRenderModule.applyUpdate`, not by rebuilding the scene, so that the WebView never
 * reloads (and therefore never flashes) during streaming.
 *
 * @property id stable identifier, used for caching and for `applyUpdate` addressing. Convention:
 *   reverse-DNS-ish names such as `com.watchplay.apps.map` or `demo.scene`.
 * @property html the complete document. It should be self-contained: no network requests, no external
 *   fonts — a BLE-only watch has no internet path.
 * @property baseUrl base URL for relative references and `localStorage`. Use
 *   `file:///android_asset/` for scenes shipped in the APK's assets and `null` for fully inline HTML.
 * @property assets extra files to expose to the document (see [SceneAsset]).
 * @property transparentBackground `true` when the WebView should be cleared to transparent instead of
 *   white — useful for scenes that draw only a few elements and let the watch's own background show.
 * @property expectedUpdateIntervalMs hint for the pipeline's scheduling: how often the app intends to
 *   push new data. `0` means "on demand". Purely advisory.
 */
data class WatchScene(
    val id: String,
    val html: String,
    val baseUrl: String? = null,
    val assets: List<SceneAsset> = emptyList(),
    val transparentBackground: Boolean = false,
    val expectedUpdateIntervalMs: Long = 0L,
) {

    init {
        require(id.isNotBlank()) { "scene id must not be blank" }
        require(html.isNotBlank()) { "scene $id has empty HTML" }
    }
}

/**
 * A file a [WatchScene] needs, delivered as bytes rather than as an APK resource reference.
 *
 * Bytes keep the SDK free of resource ids so that scenes can also come from an app's own assets, from
 * a downloaded pack, or from a test. The demo module reads `assets/demo_scene.html` into one of these.
 *
 * @property path path relative to the scene root, e.g. `styles/watch.css`.
 * @property mimeType MIME type used when serving the asset, e.g. `text/css`.
 * @property bytes file contents.
 */
class SceneAsset(
    val path: String,
    val mimeType: String,
    val bytes: ByteArray,
) {

    init {
        require(path.isNotBlank()) { "asset path must not be blank" }
    }

    /** Size in bytes. */
    val sizeBytes: Int
        get() = bytes.size

    /** Equality by path, MIME type and content — needed because [bytes] is an array. */
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SceneAsset) return false
        return path == other.path && mimeType == other.mimeType && bytes.contentEquals(other.bytes)
    }

    /** Consistent with [equals]. */
    override fun hashCode(): Int {
        var result = path.hashCode()
        result = 31 * result + mimeType.hashCode()
        result = 31 * result + bytes.contentHashCode()
        return result
    }

    override fun toString(): String = "SceneAsset($path, $mimeType, ${bytes.size}B)"
}
