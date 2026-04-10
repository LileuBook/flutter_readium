package dk.nota.flutter_readium

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.ViewGroup
import androidx.fragment.app.FragmentManager
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryOwner
import dk.nota.flutter_readium.altoral.AltoralContentProtection
import dk.nota.flutter_readium.events.ReadiumErrorEventChannel
import dk.nota.flutter_readium.events.ReadiumReaderStatus
import dk.nota.flutter_readium.events.ReadiumReaderStatusEventChannel
import dk.nota.flutter_readium.events.TextLocatorEventChannel
import dk.nota.flutter_readium.events.TimedBasedStateEventChannel
import dk.nota.flutter_readium.models.ReadiumTimebasedState
import dk.nota.flutter_readium.navigators.AudiobookNavigator
import dk.nota.flutter_readium.navigators.EpubNavigator
import dk.nota.flutter_readium.navigators.SyncAudiobookNavigator
import dk.nota.flutter_readium.navigators.TTSNavigator
import dk.nota.flutter_readium.navigators.TimebasedNavigator
import io.flutter.plugin.common.BinaryMessenger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.readium.navigator.media.tts.android.AndroidTtsEngine
import org.readium.navigator.media.tts.android.AndroidTtsPreferences
import org.readium.navigator.media.tts.android.AndroidTtsSettings
import org.readium.r2.navigator.Decoration
import org.readium.r2.navigator.epub.EpubPreferences
import org.readium.r2.navigator.extensions.time
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.InternalReadiumApi
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.LocatorCollection
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.html.cssSelector
import org.readium.r2.shared.publication.services.isRestricted
import org.readium.r2.shared.publication.services.search.SearchService
import org.readium.r2.shared.publication.services.search.search
import org.readium.r2.shared.util.AbsoluteUrl
import org.readium.r2.shared.util.DebugError
import org.readium.r2.shared.util.Language
import org.readium.r2.shared.util.ThrowableError
import org.readium.r2.shared.util.Try
import org.readium.r2.shared.util.Try.Companion.failure
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.asset.Asset
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.shared.util.getOrElse
import org.readium.r2.shared.util.http.DefaultHttpClient
import org.readium.r2.shared.util.http.HttpRequest
import org.readium.r2.shared.util.http.HttpResponse
import org.readium.r2.shared.util.http.HttpTry
import org.readium.r2.shared.util.resource.Resource
import org.readium.r2.shared.util.resource.TransformingContainer
import org.readium.r2.lcp.LcpError
import org.readium.r2.lcp.LcpService
import org.readium.r2.lcp.auth.LcpPassphraseAuthentication
import org.readium.r2.streamer.PublicationOpener
import org.readium.r2.streamer.PublicationOpener.OpenError
import org.readium.r2.streamer.parser.DefaultPublicationParser
import java.io.File
import java.lang.ref.WeakReference
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale
import org.json.JSONObject
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

private const val TAG = "ReadiumReader"

private const val stateKey = "dk.nota.flutter_readium.ReadiumReaderState"

private const val currentPublicationUrlKey = "currentPublicationUrl"
private const val ttsEnabledKey = "ttsEnabled"
private const val audioEnabledKey = "audioEnabled"
private const val syncAudioEnabledKey = "syncAudioEnabled"

private const val epubEnabledKey = "epubEnabled"
private const val ttsNavigatorStateKey = "ttsState"
private const val audioNavigatorStateKey = "audioState"
private const val syncAudioNavigatorStateKey = "syncAudioState"
private const val epubNavigatorStateKey = "epubState"
private const val decorationStyleKey = "decorationStyle"

private val redirectHopHeaderBlocklist =
    setOf(
        "host",
        "connection",
        "keep-alive",
        "proxy-authenticate",
        "proxy-authorization",
        "te",
        "trailers",
        "transfer-encoding",
        "upgrade",
        "content-length",
    )

/** Mirrors Dart [DrmScheme] indices. */
internal enum class FlutterDrmScheme {
    LCP,
    ALTORAL,
    DUAL,
}

// TODO: Support custom headers and authentication header for content files.

@ExperimentalCoroutinesApi
@OptIn(ExperimentalReadiumApi::class)
object ReadiumReader : TimebasedNavigator.TimebasedListener, EpubNavigator.VisualListener {
    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val jobs = mutableListOf<Job>()

    private var appRef: WeakReference<Application>? = null

    private var timedBasedStateEventChannel: TimedBasedStateEventChannel? = null

    private var textLocatorEventChannel: TextLocatorEventChannel? = null

    private var readiumErrorEventChannel: ReadiumErrorEventChannel? = null

    private var readiumReaderStatusEventChannel: ReadiumReaderStatusEventChannel? = null

    private var readerViewRef: WeakReference<ReadiumReaderWidget>? = null

    private var savedStateRef: WeakReference<SavedStateRegistry>? = null

    // in-memory cached state
    private val state = mutableMapOf<String, Any?>()

    private val currentTimebasedState = MutableStateFlow<TimebasedNavigator.TimebasedState?>(null)

    private val currentTimebasedDuration = MutableStateFlow<Double?>(null)

    private val currentTimebasedOffset = MutableStateFlow<Double?>(null)

    private val currentTimebasedBuffer = MutableStateFlow<Long?>(null)

    private val currentTimebasedLocator = MutableStateFlow<Locator?>(null)

    private val currentTextLocator = MutableStateFlow<Locator?>(null)

    private var defaultHttpHeaders = mutableMapOf<String, String>()

    var decorationStyle: FlutterDecorationPreferences
        get() = state[decorationStyleKey] as? FlutterDecorationPreferences
            ?: FlutterDecorationPreferences()
        set(value) {
            state[decorationStyleKey] = value
        }

    fun createCurrentTimebasedReaderState(): Flow<ReadiumTimebasedState?> {
        return combine(
            currentTimebasedLocator.throttleLatest(100.milliseconds).distinctUntilChanged(),
            currentTimebasedState.throttleLatest(100.milliseconds).distinctUntilChanged(),
            currentTimebasedOffset.throttleLatest(100.milliseconds).distinctUntilChanged(),
            currentTimebasedBuffer.throttleLatest(250.milliseconds).distinctUntilChanged(),
            currentTimebasedDuration.throttleLatest(100.milliseconds).distinctUntilChanged(),
        ) { locator, state, offset, buffer, duration ->
            if (state == null) {
                return@combine null
            }

            ReadiumTimebasedState(locator, state, offset, buffer, duration ?: 0.0)
        }.throttleLatest(100.milliseconds).distinctUntilChanged()
    }

    private val httpClient by lazy {
        DefaultHttpClient(callback = object : DefaultHttpClient.Callback {
            override suspend fun onStartRequest(request: HttpRequest): HttpTry<HttpRequest> {
                val requestWithHeaders = request.copy {
                    defaultHttpHeaders.toMap().forEach { (key, value) ->
                        setHeader(key, value)
                    }
                }
                return Try.success(requestWithHeaders)
            }

            override suspend fun onFollowUnsafeRedirect(
                request: HttpRequest,
                response: HttpResponse,
                newRequest: HttpRequest,
            ): HttpTry<HttpRequest> {
                val merged =
                    newRequest.copy {
                        request.headers.forEach { (key, values) ->
                            val k = key.lowercase(Locale.ROOT)
                            if (k in redirectHopHeaderBlocklist || k == "cookie") {
                                return@forEach
                            }
                            values.forEach { addHeader(key, it) }
                        }
                    }
                Log.d(TAG, "HTTP unsafe redirect: ${request.url} -> ${merged.url}")
                return Try.success(merged)
            }
        })
    }

    private var _assetRetriever: AssetRetriever? = null

    private val assetRetriever: AssetRetriever
        get() {
            if (_assetRetriever == null) {
                _assetRetriever = AssetRetriever(context.contentResolver, httpClient)
            }

            return _assetRetriever!!
        }

    private var _lcpPassphrase: String? = null

    private var drmScheme: FlutterDrmScheme = FlutterDrmScheme.LCP

    private var _publicationOpener: PublicationOpener? = null

    private var ttsNavigator: TTSNavigator? = null

    private var audiobookNavigator: AudiobookNavigator? = null
    private var syncAudiobookNavigator: SyncAudiobookNavigator? = null

    private val timebasedNavigator: TimebasedNavigator<*>?
        get() = audiobookNavigator ?: syncAudiobookNavigator ?: ttsNavigator

    private var epubNavigator: EpubNavigator? = null

    private var _audioPreferences: FlutterAudioPreferences = FlutterAudioPreferences()

    /** Current audio preferences (defaults if audio hasn't been enabled yet). */
    val audioPreferences: FlutterAudioPreferences
        get() = _audioPreferences

    fun setLcpPassphrase(passphrase: String, preserveDrmScheme: Boolean = false) {
        _lcpPassphrase = passphrase
        if (!preserveDrmScheme) {
            drmScheme = FlutterDrmScheme.LCP
        }
        _publicationOpener = null
        Log.d(
            TAG,
            "LCP: passphrase updated (len=${passphrase.length}, fp=${fingerprint(passphrase)}" +
                (if (preserveDrmScheme) ", preserveDrmScheme=true drmScheme=$drmScheme" else "") +
                ")",
        )
    }

    fun setDrmConfiguration(
        schemeOrdinal: Int,
        passphrase: String?,
    ) {
        drmScheme =
            when (schemeOrdinal) {
                1 -> FlutterDrmScheme.ALTORAL
                2 -> FlutterDrmScheme.DUAL
                else -> FlutterDrmScheme.LCP
            }
        if (passphrase != null) {
            _lcpPassphrase = passphrase
        }
        _publicationOpener = null
        Log.d(TAG, "DRM: scheme=$drmScheme passphrase=${if (passphrase != null) "set" else "unchanged"}")
    }

    private fun fingerprint(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(8)
    }

    /**
     * The PublicationFactory is used to open publications.
     */
    private val publicationOpener: PublicationOpener
        get() {
            if (_publicationOpener == null) {
                val contentProtections = buildList {
                    val passphrase = _lcpPassphrase
                    fun addLcpProtection() {
                        Log.d(
                            TAG,
                            "LCP: building content protection, passphrase=${if (passphrase != null) "set (${passphrase.length} chars)" else "null"}",
                        )
                        if (passphrase == null) {
                            Log.w(TAG, "LCP: no passphrase set, skipping LCP content protection")
                            return
                        }
                        try {
                            val lcpService = LcpService(
                                context = context,
                                assetRetriever = assetRetriever,
                            )
                            if (lcpService == null) {
                                Log.w(TAG, "LCP: LcpService is null - LCP DRM will NOT be applied")
                            } else {
                                val lcpAuth = LcpPassphraseAuthentication(passphrase)
                                val cp = lcpService.contentProtection(lcpAuth)
                                if (cp == null) {
                                    Log.w(TAG, "LCP: contentProtection() returned null")
                                } else {
                                    Log.d(TAG, "LCP: ContentProtection registered successfully")
                                    add(cp)
                                }
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "LCP: Failed to create content protection: $e")
                        }
                    }
                    when (drmScheme) {
                        FlutterDrmScheme.LCP -> addLcpProtection()
                        FlutterDrmScheme.ALTORAL -> {
                            add(
                                AltoralContentProtection(
                                    assetRetriever,
                                    acceptReadiumBasicProfileAsAltoral = true,
                                    eagerHttpPublicationDownloader = {
                                        downloadAltoralPublicationPreservingAuthAcrossRedirects(it)
                                    },
                                ),
                            )
                            Log.d(TAG, "Altoral: ContentProtection registered (basic-profile OK)")
                        }
                        FlutterDrmScheme.DUAL -> {
                            add(
                                AltoralContentProtection(
                                    assetRetriever,
                                    acceptReadiumBasicProfileAsAltoral = false,
                                    eagerHttpPublicationDownloader = {
                                        downloadAltoralPublicationPreservingAuthAcrossRedirects(it)
                                    },
                                ),
                            )
                            addLcpProtection()
                        }
                    }
                }
                Log.d(TAG, "DRM: contentProtections count = ${contentProtections.size}")
                _publicationOpener = PublicationOpener(
                    publicationParser = DefaultPublicationParser(
                        context,
                        assetRetriever = assetRetriever,
                        httpClient = httpClient,
                        // Only required if you want to support PDF files using the PDFium adapter.
                        pdfFactory = null, //PdfiumDocumentFactory(context)
                    ),
                    contentProtections = contentProtections,
                )
            }

            return _publicationOpener!!
        }

    // Initialize from plugin or anywhere you have an Application or Context.
    fun attach(activity: Activity, messenger: BinaryMessenger) {
        unwrapToApplication(activity)?.let { appRef = WeakReference(it) }

        timedBasedStateEventChannel?.dispose()
        timedBasedStateEventChannel = TimedBasedStateEventChannel(messenger)

        textLocatorEventChannel = TextLocatorEventChannel(messenger)
        readiumErrorEventChannel = ReadiumErrorEventChannel(messenger)
        readiumReaderStatusEventChannel = ReadiumReaderStatusEventChannel(messenger)

        // store weak ref only
        (activity as? SavedStateRegistryOwner)?.savedStateRegistry?.let {
            savedStateRef = WeakReference(it)
            it.registerSavedStateProvider(stateKey) {
                storeState()
            }

            restoreState(it.consumeRestoredStateForKey(stateKey))
        }

        createCurrentTimebasedReaderState().onEach {
            Log.d(
                TAG, "currentTimebasedReaderState: ${
                    jsonEncode(
                        it?.toJSON()
                    )
                }"
            )

            if (it != null) {
                timedBasedStateEventChannel?.sendEvent(it)
            }
        }.launchIn(mainScope).let { jobs.add(it) }
    }

    private fun storeState(): Bundle {
        if (currentPublicationUrl == null) {
            // No current publication, no state.
            return Bundle()
        }

        return Bundle().apply {
            putString(currentPublicationUrlKey, currentPublicationUrl)
            putBoolean(epubEnabledKey, epubNavigator != null)
            putBundle(epubNavigatorStateKey, epubNavigator?.storeState())
            putBoolean(ttsEnabledKey, ttsNavigator != null)
            putBundle(ttsNavigatorStateKey, ttsNavigator?.storeState())
            putBoolean(audioEnabledKey, audiobookNavigator != null)
            putBundle(audioNavigatorStateKey, audiobookNavigator?.storeState())
            putBoolean(syncAudioEnabledKey, syncAudiobookNavigator != null)
            putBundle(syncAudioNavigatorStateKey, syncAudiobookNavigator?.storeState())
            putParcelable(decorationStyleKey, decorationStyle)
        }
    }

    private fun restoreState(bundle: Bundle?) {
        if (bundle == null) {
            Log.d(TAG, ":restoreState nothing to restore")
            return
        }

        Log.d(TAG, ":restoreState $bundle")
        val pubUrl = bundle.getString(currentPublicationUrlKey)
        if (pubUrl == null) {
            Log.d(TAG, ":storeState - currentPublicationUrl - not restored")
            return
        }

        Log.d(TAG, ":restoreState - currentPublicationUrl - $pubUrl")
        mainScope.launch {
            val pub = openPublication(pubUrl).getOrElse {
                Log.d(TAG, ":restoreState - failed to restore publication")
                // TODO: Handle this somehow
                return@launch
            }

            decorationStyle =
                bundle.getParcelable(decorationStyleKey) as? FlutterDecorationPreferences
                    ?: FlutterDecorationPreferences()

            if (bundle.getBoolean(epubEnabledKey)) {
                Log.d(TAG, ":storeState - restore epub navigator")
                bundle.getBundle(epubNavigatorStateKey)?.let { state ->
                    epubNavigator =
                        EpubNavigator.restoreState(pub, this@ReadiumReader, state).apply {
                            initNavigator()
                            Log.d(TAG, ":storeState - epubNavigator restored")
                        }
                }
            }

            if (bundle.getBoolean(ttsEnabledKey)) {
                // Restore TTS navigator
                Log.d(TAG, ":storeState - restore tts navigator")
                bundle.getBundle(ttsNavigatorStateKey)?.let { state ->
                    ttsNavigator = TTSNavigator.restoreState(pub, this@ReadiumReader, state).apply {
                        initNavigator()
                        Log.d(TAG, ":storeState - ttsNavigator restored")
                    }
                }
            }

            if (bundle.getBoolean(audioEnabledKey)) {
                // Restore Audio navigator
                Log.d(TAG, ":storeState - restore audio navigator")
                bundle.getBundle(audioNavigatorStateKey)?.let { state ->
                    audiobookNavigator =
                        AudiobookNavigator.restoreState(pub, this@ReadiumReader, state).apply {
                            initNavigator()
                            Log.d(TAG, ":storeState - audioNavigator restored")
                        }
                }
            } else if (bundle.getBoolean(syncAudioEnabledKey)) {
                // Restore Sync Audio navigator
                Log.d(TAG, ":storeState - restore sync audio navigator")
                val (ap, mediaOverlays) = pub.makeSyncAudiobook()
                if (mediaOverlays != null) {
                    bundle.getBundle(syncAudioNavigatorStateKey)?.let { state ->
                        syncAudiobookNavigator =
                            SyncAudiobookNavigator.restoreState(
                                ap,
                                mediaOverlays,
                                this@ReadiumReader,
                                state
                            )
                                .apply {
                                    initNavigator()
                                    Log.d(TAG, ":storeState - syncAudioNavigator restored")
                                }
                    }
                } else {
                    Log.e(TAG, ":storeState - no media overlays for sync audio navigator")
                }
            }

            Log.d(TAG, "consumeRestoredStateForKey - 2 - $currentPublication")
        }
    }

    fun detach() {
        mainScope.launch {
            closePublication()
        }

        appRef?.clear()
        appRef = null

        savedStateRef?.clear()
        savedStateRef = null

        _assetRetriever = null
        _publicationOpener = null

        readerViewRef?.clear()
        readerViewRef = null

        timedBasedStateEventChannel?.dispose()
        timedBasedStateEventChannel = null

        textLocatorEventChannel?.dispose()
        textLocatorEventChannel = null

        readiumErrorEventChannel?.dispose()
        readiumErrorEventChannel = null

        readiumReaderStatusEventChannel?.dispose()
        readiumReaderStatusEventChannel = null

        jobs.forEach { it.cancel() }
        jobs.clear()
        mainScope.coroutineContext.cancelChildren()
    }

    // Safe getter — returns applicationContext or throws if not available.
    val application: Application
        get() = appRef?.get()
            ?: throw IllegalStateException("Application not initialized. Call ReadiumReader.attach(...) first.")

    var currentReaderWidget: ReadiumReaderWidget?
        get() = readerViewRef?.get()
        set(value) {
            readerViewRef = value?.let { WeakReference(it) }
        }

    private val context: Context
        get() = application.applicationContext

    private var _currentPublication: Publication? = null
    val currentPublication: Publication?
        get() = _currentPublication
    var currentPublicationUrl
        get() = state[currentPublicationUrlKey] as String?
        set(value) {
            state[currentPublicationUrlKey] = value
        }

    /***
     * For EPUB profile, maps document [Url] to a list of all the cssSelectors in the document.
     *
     * This is used to find the current toc item.
     */
    private var currentPublicationCssSelectorMap: MutableMap<Url, List<String>>? = null

    /**
     * Sets the headers used in the HTTP requests for fetching publication resources, including
     * resources in already created `Publication` objects.
     *
     * @param headers a map of HTTP header key value pairs.
     */
    fun setDefaultHttpHeaders(headers: Map<String, String>) {
        defaultHttpHeaders.clear()
        defaultHttpHeaders.putAll(headers)
    }

    /**
     * Fetches the encrypted EPUB with [HttpURLConnection.setInstanceFollowRedirects] disabled and
     * re-sends [defaultHttpHeaders] on every redirect hop. JDK auto-follow drops `Authorization` on
     * cross-origin redirects, which breaks LCP publication links that bounce via CDN/storage.
     */
    private suspend fun downloadAltoralPublicationPreservingAuthAcrossRedirects(url: AbsoluteUrl): File {
        val sessionHeaders = defaultHttpHeaders.toMap()
        return withContext(Dispatchers.IO) {
            var current = URL(url.toString())
            repeat(16) {
                val conn =
                    (current.openConnection() as HttpURLConnection).apply {
                        instanceFollowRedirects = false
                        requestMethod = "GET"
                        sessionHeaders.forEach { (k, v) -> setRequestProperty(k, v) }
                        connectTimeout = 30_000
                        readTimeout = 120_000
                    }
                val code =
                    try {
                        conn.responseCode
                    } catch (e: Exception) {
                        conn.disconnect()
                        throw e
                    }
                when {
                    code in 300..399 -> {
                        val loc = conn.getHeaderField("Location")
                        conn.disconnect()
                        if (loc.isNullOrBlank()) {
                            throw java.io.IOException("HTTP $code redirect without Location")
                        }
                        current = URL(conn.url, loc)
                    }
                    code >= 400 -> {
                        val snippet =
                            conn.errorStream?.use { stream -> stream.readBytes() }
                                ?.decodeToString()
                                ?.take(400)
                        conn.disconnect()
                        throw java.io.IOException("HTTP $code ${conn.responseMessage}: $snippet")
                    }
                    else -> {
                        val out = File.createTempFile("altoral_pub_", ".epub", context.cacheDir)
                        conn.inputStream.use { input ->
                            out.outputStream().use { input.copyTo(it) }
                        }
                        conn.disconnect()
                        return@withContext out
                    }
                }
            }
            throw java.io.IOException("Too many HTTP redirects")
        }
    }

    private suspend fun assetToPublication(
        asset: Asset
    ): Try<Publication, OpenError> {
        val publication: Publication =
            publicationOpener.open(
                asset,
                credentials = _lcpPassphrase,
                allowUserInteraction = true,
                onCreatePublication = {
                val tocIds = manifest.tableOfContents.flattenChildren()
                    .mapNotNull { it.href.resolve().fragment }
                container = TransformingContainer(container) { _: Url, resource: Resource ->
                    resource.injectScriptsAndStyles(tocIds)
                }
            },
            ).getOrElse { err: OpenError ->
                Log.e(TAG, "Error opening publication: $err")
                asset.close()
                return failure(err)
            }
        Log.d(TAG, "Open publication success: $publication")
        return Try.success(publication)
    }

    /**
     * Load a publication from a String url.
     * Note: Remember to close the publication to avoid leaks.
     */
    suspend fun loadPublication(
        pubUrl: String?
    ): Try<Publication, PublicationError> {
        if (pubUrl == null) {
            return failure(
                PublicationError.Unexpected(
                    DebugError("missing argument")
                )
            )
        }

        return AbsoluteUrl.invoke(pubUrl)?.let { pubUrl -> loadPublication(pubUrl) } ?: failure(
            PublicationError.Unexpected(
                DebugError("Invalid Url")
            )
        )
    }

    /**
     * Acquires a publication from an LCP License Document (.lcpl).
     * Downloads the encrypted epub, injects the license, and opens it.
     */
    private suspend fun acquireAndLoadLcpl(lcplUrl: AbsoluteUrl): Try<Publication, PublicationError> {
        val lcpService = LcpService(context = context, assetRetriever = assetRetriever) ?: run {
            Log.e(TAG, "acquireAndLoadLcpl: LcpService unavailable - org.readium.lcp.sdk.Lcp not found")
            return failure(PublicationError.Unexpected(DebugError("LcpService unavailable")))
        }

        val lcplPath = android.net.Uri.parse(lcplUrl.toString()).path ?: run {
            return failure(PublicationError.Unexpected(DebugError("Invalid lcpl path: $lcplUrl")))
        }
        val lcplFile = java.io.File(lcplPath)
        if (!lcplFile.exists()) {
            return failure(PublicationError.Unexpected(DebugError("LCPL file not found: $lcplPath")))
        }

        Log.d(TAG, "acquireAndLoadLcpl: acquiring publication from $lcplFile")
        val acquired: LcpService.AcquiredPublication =
            lcpService.acquirePublication(lcplFile).getOrElse { error: LcpError ->
                Log.e(TAG, "acquireAndLoadLcpl: acquisition failed: $error")
                return failure(PublicationError.Unexpected(DebugError("LCP acquisition failed: $error")))
            }

        Log.d(TAG, "acquireAndLoadLcpl: acquired epub at ${acquired.localFile}")
        val epubUri = acquired.localFile.toURI().toString()
        val epubUrl = AbsoluteUrl(epubUri) ?: run {
            return failure(PublicationError.Unexpected(DebugError("Invalid acquired epub URL: $epubUri")))
        }

        val asset = assetRetriever.retrieve(epubUrl).getOrElse { error ->
            Log.e(TAG, "acquireAndLoadLcpl: error retrieving acquired epub: $error")
            return failure(PublicationError.invoke(error))
        }

        val pub = assetToPublication(asset).getOrElse { error ->
            Log.e(TAG, "acquireAndLoadLcpl: error opening acquired epub: $error")
            return failure(PublicationError.invoke(error))
        }

        return Try.success(pub)
    }

    /**
     * Opens a standalone Altoral license file (same extension `.lcpl` may be used) via [AltoralContentProtection]
     * without LCP acquisition / injection.
     */
    private suspend fun openAltoralLicenseAt(lcplUrl: AbsoluteUrl): Try<Publication, PublicationError> {
        val asset: Asset =
            assetRetriever.retrieve(lcplUrl).getOrElse { error: AssetRetriever.RetrieveUrlError ->
                Log.e(TAG, "openAltoralLicenseAt: retrieve failed: $error")
                return failure(PublicationError.invoke(error))
            }
        val pub =
            assetToPublication(asset).getOrElse { error ->
                Log.e(TAG, "openAltoralLicenseAt: open failed: $error")
                return failure(PublicationError.invoke(error))
            }
        return Try.success(pub)
    }

    /**
     * Load a publication from an AbsoluteUrl
     *
     * Note: Remember to close the publication to avoid leaks.
     */
    suspend fun loadPublication(
        pubUrl: AbsoluteUrl
    ): Try<Publication, PublicationError> {
        if (currentPublicationUrl == pubUrl.toString()) {
            // Current publication is the same as the one we are trying to load, return it.
            currentPublication?.let {
                return Try.success(it)
            }
        }

        return withContext(Dispatchers.IO) {
            try {
                if (pubUrl.toString().endsWith(".lcpl", ignoreCase = true)) {
                    return@withContext when (drmScheme) {
                        FlutterDrmScheme.LCP -> acquireAndLoadLcpl(pubUrl)
                        FlutterDrmScheme.ALTORAL -> openAltoralLicenseAt(pubUrl)
                        FlutterDrmScheme.DUAL -> {
                            val lcplPath =
                                android.net.Uri.parse(pubUrl.toString()).path
                                    ?: return@withContext failure(
                                        PublicationError.Unexpected(DebugError("Invalid lcpl path: $pubUrl")),
                                    )
                            val text =
                                try {
                                    File(lcplPath).readText(Charsets.UTF_8)
                                } catch (e: Exception) {
                                    return@withContext failure(PublicationError.Unexpected(ThrowableError(e)))
                                }
                            if (AltoralContentProtection.isAltoralLicenseJson(text)) {
                                openAltoralLicenseAt(pubUrl)
                            } else {
                                acquireAndLoadLcpl(pubUrl)
                            }
                        }
                    }
                }

                // TODO: should client provide mediaType to assetRetriever?
                val asset: Asset = assetRetriever.retrieve(pubUrl)
                    .getOrElse { error: AssetRetriever.RetrieveUrlError ->
                        Log.e(TAG, "Error retrieving asset: $error from url:$pubUrl")
                        return@withContext failure(PublicationError.invoke(error))
                    }
                val pub = assetToPublication(asset).getOrElse { error: OpenError ->
                    Log.e(TAG, "Error loading asset to Publication object: $error from url:$pubUrl")
                    return@withContext failure(PublicationError.invoke(error))
                }
                Log.d(TAG, "Opened publication = ${pub.metadata.identifier} from url:$pubUrl")
                return@withContext Try.success(pub)
            } catch (e: Throwable) {
                return@withContext failure(PublicationError.Unexpected(ThrowableError(e)))
            }
        }
    }

    /**
     * Open a publication and set it as the current publication.
     */
    suspend fun openPublication(
        pubUrl: String?
    ): Try<Publication, PublicationError> {
        if (pubUrl == null) {
            return failure(
                PublicationError.Unexpected(
                    DebugError("missing argument")
                )
            )
        }

        return AbsoluteUrl.invoke(pubUrl)?.let { pubUrl -> openPublication(pubUrl) } ?: failure(
            PublicationError.Unexpected(
                DebugError("Invalid Url")
            )
        )
    }

    /**
     * Open a publication and set it as the current publication.
     */
    suspend fun openPublication(
        pubUrl: AbsoluteUrl
    ): Try<Publication, PublicationError> {
        if (currentPublicationUrl == pubUrl.toString()) {
            // Current publication is the same as the one we are trying to open, return it.
            // If you need to reload the publication, you need to close it first.
            currentPublication?.let {
                return Try.success(it)
            }
        }

        // Close previously opened publication to avoid leaks.
        closePublication()

        val pub = loadPublication(pubUrl).getOrElse { e -> return failure(e) }

        // Altoral/basic-profile: o primeiro `read()` do spine pode bloquear indefinidamente após um
        // open bem-sucedido (LcpBasicProfile já validou a passphrase). A sonda impedia o retorno ao
        // Dart — MethodChannel nunca recebia o manifesto e o ecrã ficava no loading.
        val decryptProbeFailed =
            drmScheme != FlutterDrmScheme.ALTORAL && isPublicationRestrictedByProbe(pub)

        if (isPublicationRestricted(pub) || decryptProbeFailed) {
            val message =
                "The provided publication is restricted. Check that any DRM was properly unlocked using a Content Protection."
            Log.e(TAG, "openPublication: $message")
            sendErrorEvent(
                message = message,
                code = "incorrectCredentials",
                data = pubUrl.toString()
            )
            pub.close()
            return failure(PublicationError.IncorrectCredentials(message))
        }

        _currentPublication = pub
        currentPublicationUrl = pubUrl.toString()

        return Try.success(pub)
    }

    /**
     * Load a publication from a URL
     * Note: Remember to close the publication to avoid leaks.
     */
    suspend fun loadPublicationFromUrl(urlStr: String): Try<Publication, PublicationError> {
        val pubUrl = resolvePubUrl(urlStr).getOrElse {
            return failure(PublicationError.InvalidPublicationUrl(urlStr))
        }

        Log.d(TAG, "loadPublicationFromUrl: $pubUrl")

        return loadPublication(pubUrl)
    }

    /**
     * Open a publication from a URL.
     *
     * Note: This sets the publication as the current publication.
     */
    suspend fun openPublicationFromUrl(urlStr: String): Try<Publication, PublicationError> {
        val pubUrl = resolvePubUrl(urlStr).getOrElse {
            return failure(PublicationError.InvalidPublicationUrl(urlStr))
        }

        Log.d(TAG, "openPublicationFromUrl: $pubUrl")

        return openPublication(pubUrl)
    }

    /**
     * Helper function for resolving a URL and make sure a file path is turned into a URL.
     */
    private fun resolvePubUrl(urlStr: String): Try<AbsoluteUrl, PublicationError> {
        var pubUrlStr = urlStr
        // If URL is neither http nor file, assume it is a local file reference.
        if (!pubUrlStr.startsWith("http") && !pubUrlStr.startsWith("file")) {
            pubUrlStr = "file://$pubUrlStr"
        }
        // Create AbsoluteUrl, return PublicationError.InvalidPublicationUrl if null
        val pubUrl = AbsoluteUrl(pubUrlStr) ?: return failure(
            PublicationError.InvalidPublicationUrl(pubUrlStr)
        )

        return Try.success(pubUrl)
    }

    suspend fun closePublication() {
        mainScope.async {
            _currentPublication?.close()
            _currentPublication = null
            currentPublicationCssSelectorMap = null

            ttsNavigator?.dispose()
            ttsNavigator = null
            audiobookNavigator?.dispose()
            audiobookNavigator = null
            syncAudiobookNavigator?.dispose()
            syncAudiobookNavigator = null

            _audioPreferences = FlutterAudioPreferences()

            currentTextLocator.value = null
            currentTimebasedLocator.value = null
            currentTimebasedState.value = null
            currentTimebasedBuffer.value = null
            currentTimebasedDuration.value = null
            currentTimebasedOffset.value = null

            state.clear()
        }.await()
    }

    override fun onTimebasedPlaybackStateChanged(timebasedState: TimebasedNavigator.TimebasedState) {
        Log.d(TAG, ":onTimebasedPlaybackStateChanged $timebasedState")
        currentTimebasedState.value = timebasedState
    }

    override fun onTimebasedBufferChanged(buffer: Duration?) {
        Log.d(TAG, ":onTimebasedBufferChanged $buffer")
        currentTimebasedBuffer.value = buffer?.inWholeMilliseconds
    }

    override fun onTimebasedPlaybackFailure(error: PublicationError) {
        Log.d(TAG, ":onTimebasedPlaybackFailure $error")
        sendErrorEvent(
            message = error.message,
            code = "timebasedPlaybackFailure",
            data = error.toString()
        )
    }

    fun isCurrentPublicationRestricted(): Boolean {
        val publication = currentPublication ?: return false
        return isPublicationRestricted(publication)
    }

    fun reportRestrictedPublicationError(
        message: String = "The provided publication is restricted. Check that any DRM was properly unlocked using a Content Protection."
    ) {
        sendErrorEvent(
            message = message,
            code = "incorrectCredentials",
            data = currentPublicationUrl
        )
    }

    private fun isPublicationRestricted(publication: Publication): Boolean {
        // Readium 3: restriction comes from ContentProtectionService (LCP license missing / locked).
        // Older reflection on Publication never matched, so openPublication wrongly "succeeded"
        // while NavigatorFragment still refused to render (blank EPUB).
        return publication.isRestricted
    }

    private suspend fun isPublicationRestrictedByProbe(publication: Publication): Boolean {
        return withTimeoutOrNull(10_000L) {
            isPublicationRestrictedByProbeBody(publication)
        } ?: run {
            Log.w(
                TAG,
                "openPublication: restricted probe timed out after 10s — treating as unlocked " +
                    "(LCP/network/decrypt pipeline may be slow; navigator will surface real errors).",
            )
            false
        }
    }

    private suspend fun isPublicationRestrictedByProbeBody(publication: Publication): Boolean {
        val firstReadingOrderLink = publication.readingOrder.firstOrNull() ?: return false

        return try {
            Log.d(TAG, "openPublication: running restricted decrypt probe (first spine byte)")
            val firstResource = publication.get(firstReadingOrderLink) ?: run {
                Log.e(TAG, "openPublication: restricted probe missing first reading-order resource")
                return true
            }

            val probe = firstResource.read(0L..0L)
            probe.getOrElse { error ->
                Log.e(TAG, "openPublication: restricted probe read failed: $error")
                return true
            }
            false
        } catch (error: Exception) {
            Log.e(TAG, "openPublication: restricted probe threw exception: $error")
            true
        }
    }

    private fun sendErrorEvent(message: String, code: String? = null, data: Any? = null) {
        val payload = JSONObject().apply {
            put("message", message)
            if (code != null) {
                put("code", code)
            }
            if (data != null) {
                put("data", data.toString())
            }
        }
        readiumErrorEventChannel?.sendEvent(payload.toString())
    }

    @OptIn(InternalReadiumApi::class)
    override fun onTimebasedCurrentLocatorChanges(
        locator: Locator, currentReadingOrderLink: Link?
    ) {
        val duration = currentReadingOrderLink?.duration
        val timeOffset =
            locator.locations.time?.inWholeSeconds?.toDouble()
                ?: (duration?.let { duration ->
                    locator.locations.progression?.let { prog -> duration * prog }
                })

        Log.d(TAG, ":onTimebasedCurrentLocatorChanges $locator, timeOffset=$timeOffset")

        currentTimebasedOffset.value = timeOffset?.let { it * 1000 }
        currentTimebasedDuration.value = duration?.let { it * 1000 }
        currentTimebasedLocator.value = locator
    }

    override fun onTimebasedLocationChanged(locator: Locator) {
        Log.d(TAG, ":onTimebasedLocationChanged $locator")

        currentReaderWidget?.go(locator, true)
    }

    /**
     * Find the current table of content item from a locator.
     */
    suspend fun epubEnrichLocatorWithTocHref(locator: Locator): Locator {
        val publication = currentPublication ?: run {
            Log.e(TAG, ":epubFindCurrentToc - no currentPublication")
            return locator
        }

        if (!publication.conformsTo(Publication.Profile.EPUB)) {
            Log.e(TAG, ":epubFindCurrentToc - not an EPUB profile")
            return locator
        }

        // This locator already has a tocHref, add title and return it.
        locator.locations.tocHref?.let { tocHref ->
            return locator.copy(title = publication.getTitleFromTocHref(tocHref))
        }

        val cssSelector = locator.locations.cssSelector ?: run {
            Log.e(TAG, ":epubFindCurrentToc - missing cssSelector in locator")
            return locator
        }

        val resultLocator = locator.copy()

        val cleanHref = resultLocator.href.cleanHref()
        val tocLinks = publication.tableOfContents.flattenChildren().filter {
            it.href.resolve().cleanHref().path == cleanHref.path
        }

        val documentCssSelectors = epubGetAllDocumentCssSelectors(resultLocator.href)
        val idx = documentCssSelectors.indexOf(cssSelector).takeIf { it > -1 } ?: run {
            // cssSelector wasn't found in the list of document cssSelectors, best effort is to assume first
            Log.d(
                TAG,
                ":epubFindCurrentToc cssSelector:${cssSelector} not found in contentIds, assume idx = 0"
            )
            0
        }

        val toc =
            tocLinks.associateBy { documentCssSelectors.indexOf("#${it.href.resolve().fragment}") }

        val tocItem = toc.entries.lastOrNull { it.key <= idx }?.value
            ?: toc.entries.firstOrNull()?.value ?: run {
                Log.d(TAG, ":epubFindCurrentToc - no tocItem found")
                return resultLocator
            }

        return resultLocator.copy(
            title = tocItem.title
        ).copyWithTocHref(tocItem)
    }

    @OptIn(InternalReadiumApi::class)
    suspend fun epubEnable(
        initialLocator: Locator?,
        initialPreferences: EpubPreferences,
        fragmentManager: FragmentManager,
        viewGroup: ViewGroup,
        readerWidget: ReadiumReaderWidget
    ) {
        val pub = currentPublication ?: throw Exception("Publication not opened cannot enable epub")

        currentReaderWidget = readerWidget

        val isEpub = pub.conformsTo(Publication.Profile.EPUB)
        if (!isEpub) {
            throw Exception("Publication is not an EPUB, cannot enable epub navigator")
        }

        withScope(mainScope) {
            epubNavigator?.let {
                attachEpubNavigator(fragmentManager, viewGroup)
                return@withScope
            } // Already enabled - assume from restored state.

            EpubNavigator(pub, initialLocator, this@ReadiumReader, initialPreferences).apply {
                initNavigator()
                epubNavigator = this
                attachEpubNavigator(fragmentManager, viewGroup)
                return@withScope
            }
        }
    }

    suspend fun attachEpubNavigator(fragmentManager: FragmentManager?, viewGroup: ViewGroup?) {
        if (fragmentManager == null || viewGroup == null) {
            Log.d(TAG, "attachEpubNavigator: Missing fragmentManager or viewGroup")
            return
        }

        mainScope.async {
            epubNavigator?.attachNavigator(fragmentManager, viewGroup)
        }.await()
    }

    fun epubClose() {
        currentReaderWidget = null
        epubNavigator?.dispose()
        epubNavigator = null
    }

    suspend fun ttsEnable(ttsPrefs: FlutterTtsPreferences) {
        currentPublication?.let {
            ttsNavigator =
                TTSNavigator(it, this@ReadiumReader, currentTextLocator.value, ttsPrefs).apply {
                    initNavigator()
                }
        } ?: throw Exception("Publication not opened cannot enable tts")
    }

    suspend fun ttsSetPreferences(ttsPrefs: FlutterTtsPreferences) {
        ttsNavigator?.updatePreferences(ttsPrefs)
            ?: throw Exception("TTS is not enabled, can't set preferences")
    }

    suspend fun setDecorationStyle(style: FlutterDecorationPreferences) {
        decorationStyle = style

        ttsNavigator?.decorationsUpdated()
        syncAudiobookNavigator?.decorationsUpdated()
    }

    /**
     * Cached list of android tts voices.
     */
    private var availableTtsVoices: Set<AndroidTtsEngine.Voice>? = null

    /**
     * Get available tts voices
     */
    suspend fun ttsGetAvailableVoices(): Set<AndroidTtsEngine.Voice> {
        // Already loaded, return existing list.
        availableTtsVoices?.takeIf { it.isNotEmpty() }?.let { return it }

        // Get the available voices from the TTS navigator.
        // If the TTS navigator hasn't been initialized, create a dummy AndroidTtsEngine.
        availableTtsVoices = ttsNavigator?.voices ?: AndroidTtsEngine.invoke(
            context,
            {
                AndroidTtsSettings(
                    Language("C"),
                    false,
                    0.0,
                    0.0,
                    mapOf()
                )
            },
            { language, availableVoices -> null },
            AndroidTtsPreferences()
        )?.voices

        return availableTtsVoices ?: setOf()
    }

    fun ttsGetPreferences(): FlutterTtsPreferences? {
        return ttsNavigator?.preferences
    }

    suspend fun ttsSetPreferredVoice(voiceId: String?, language: String?) {
        if (voiceId == null) {
            Log.d(TAG, ":ttsSetPreferredVoice - missing voiceId")
            return
        }

        if (language == null) {
            Log.d(TAG, ":ttsSetPreferredVoice - missing language")
            return
        }

        ttsNavigator?.setPreferredVoice(voiceId, language)
    }

    suspend fun play(locator: Locator?) {
        val fromLocator = locator ?:
            currentTimebasedLocator.value ?:
            currentTextLocator.value ?:
            epubFirstVisibleElementLocator()

        Log.d(TAG, ":play($locator) - fromLocator:$fromLocator")

        timebasedNavigator?.play(fromLocator)
    }

    suspend fun pause() {
        timebasedNavigator?.pause()
    }

    suspend fun resume() {
        timebasedNavigator?.resume()
    }

    suspend fun stop() {
        audiobookNavigator?.apply {
            pause()
            dispose()

            audiobookNavigator = null
        }

        syncAudiobookNavigator?.apply {
            pause()
            dispose()

            audiobookNavigator = null
        }

        ttsNavigator?.apply {
            pause()
            dispose()

            ttsNavigator = null
        }
    }

    /**
     * Skip backwards.
     */
    suspend fun previous() {
        timebasedNavigator?.goBackward()
    }

    /**
     * Skip forwards.
     */
    suspend fun next() {
        timebasedNavigator?.goForward()
    }

    /**
     * Go to a specific locator.
     */
    suspend fun goToLocator(locator: Locator) {
        if (timebasedNavigator != null) {
            Log.d(TAG, "::goToLocator - timebased $locator")
            timebasedNavigator!!.goToLocator(locator.copy(
                text = Locator.Text()
            ))
        } else {
            epubGoToLocator(locator, true)
        }
    }

    suspend fun searchInPublication(query: String): Try<List<LocatorCollection>, Error> {
        val pub = currentPublication ?: return failure(
            Error("no publication")
        )
        val resultIterator = pub.search(query, SearchService.Options()) ?: return failure(
            Error("SearchService unavailable")
        )
        var results = mutableListOf<LocatorCollection>()
        while (true) {
            val result = resultIterator.next()
            if (result.isFailure) break
            val collection = result.getOrNull() ?: break
            results.add(collection)
        }
        return Try.success(results.toList())
    }

    suspend fun audioSeek(offsetSeconds: Double) {
        timebasedNavigator?.seekTo(offsetSeconds)
    }

    @OptIn(InternalReadiumApi::class)
    suspend fun audioEnable(initialLocator: Locator?, preferences: FlutterAudioPreferences) {
        _audioPreferences = preferences

        currentPublication?.let { publication ->
            // Handle karaoke books - by creating a pseudo audio publication from the media overlays.
            val (ap, overlays) = publication.makeSyncAudiobook()

            audiobookNavigator?.dispose()
            syncAudiobookNavigator?.dispose()
            audiobookNavigator = null
            syncAudiobookNavigator = null

            if (overlays == null) {
                audiobookNavigator = AudiobookNavigator(
                    ap, this@ReadiumReader, initialLocator, preferences
                ).apply {
                    initNavigator()
                }
            } else {
                val ail = initialLocator ?: epubNavigator?.currentLocator?.value
                syncAudiobookNavigator = SyncAudiobookNavigator(
                    ap, overlays, this@ReadiumReader, ail, preferences
                ).apply {
                    initNavigator()
                }
            }
        } ?: throw Exception("Publication not opened")
    }

    suspend fun audioUpdatePreferences(preferences: FlutterAudioPreferences) {
        _audioPreferences = preferences

        mainScope.async {
            audiobookNavigator?.updatePreferences(preferences)
                ?: syncAudiobookNavigator?.updatePreferences(preferences)
                ?: throw Exception("Audio not enabled, cannot update preferences")
        }.await()
    }

    suspend fun applyDecorations(
        decorations: List<Decoration>, group: String
    ) {
        epubNavigator?.applyDecorations(decorations, group)
    }

    override fun onPageLoaded() {
        currentReaderWidget?.onPageLoaded()
    }

    override fun onPageChanged(
        pageIndex: Int, totalPages: Int, locator: Locator
    ) {
        currentReaderWidget?.onPageChanged(pageIndex, totalPages, locator)
    }

    override fun onExternalLinkActivated(url: AbsoluteUrl) {
        currentReaderWidget?.onExternalLinkActivated(url)
    }

    override fun onVisualCurrentLocationChanged(locator: Locator) {
        currentReaderWidget?.onVisualCurrentLocationChanged(locator)
    }

    override fun onVisualReaderIsReady() {
        currentReaderWidget?.onVisualReaderIsReady()
    }

    suspend fun epubFirstVisibleElementLocator(): Locator? {
        return epubNavigator?.firstVisibleElementLocator()
    }

    suspend fun epubEvaluateJavascript(script: String): String? {
        return epubNavigator?.evaluateJavascript(script)
    }

    /**
     * Update EPUB navigator preferences.
     */
    fun epubUpdatePreferences(preferences: EpubPreferences) {
        epubNavigator?.updatePreferences(preferences)
    }

    /** Snapshot do locator atual (ex.: sincronizar Flutter em `onVisualReaderIsReady`). */
    fun epubCurrentLocatorSnapshot(): Locator? =
        epubNavigator?.currentLocator?.value

    /**
     * Navigate backward in the EPUB navigator.
     */
    suspend fun epubGoBackward(animated: Boolean) {
        epubNavigator?.goBackward(animated)
    }

    /**
     * Navigate forward in the EPUB navigator.
     */
    suspend fun epubGoForward(animated: Boolean) {
        epubNavigator?.goForward(animated)
    }

    /**
     * Go to a specific locator in the EPUB navigator, this scrolls to the locator position if needed.
     */
    fun epubGoToLocator(locator: Locator, animated: Boolean) {
        mainScope.launch {
            epubNavigator?.goToLocator(locator, animated)
        }
    }

    /**
     * Get all cssSelectors for an EPUB file.
     * Note: These only includes text elements, so body, page breaks etc are not included.
     */
    suspend fun epubGetAllDocumentCssSelectors(href: Url): List<String> {
        val cssSelectorMap = currentPublicationCssSelectorMap ?: mutableMapOf()
        currentPublicationCssSelectorMap = cssSelectorMap

        val cleanHref = href.cleanHref()
        return cssSelectorMap.getOrPut(cleanHref) {
            currentPublication?.findAllCssSelectors(
                cleanHref
            ) ?: listOf()
        }
    }

    /**
     * Emit reader status update to the flutter layer.
     */
    fun emitReaderStatusUpdate(statusUpdate: ReadiumReaderStatus) {
        readiumReaderStatusEventChannel?.sendEvent(statusUpdate)
    }

    /**
     * Emit text locator to the flutter layer
     */
    fun emitTextLocatorUpdate(locator: Locator) {
        textLocatorEventChannel?.sendEvent(locator)

        currentTextLocator.value = locator
    }
}
