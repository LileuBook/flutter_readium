@file:OptIn(ExperimentalReadiumApi::class)

package dk.nota.flutter_readium

import android.os.Handler
import android.os.Looper
import android.util.Log
import dk.nota.flutter_readium.models.TextSearchResult
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.InternalReadiumApi
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Manifest
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.util.Try
import org.readium.r2.shared.util.getOrElse

private const val TAG = "PublicationChannel"

internal const val publicationChannelName = "dk.nota.flutter_readium/main"

private val publicationMainHandler = Handler(Looper.getMainLooper())

@ExperimentalCoroutinesApi
internal class PublicationMethodCallHandler() :
    MethodChannel.MethodCallHandler {

    @OptIn(InternalReadiumApi::class, ExperimentalReadiumApi::class)
    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        CoroutineScope(Dispatchers.IO).launch {
            Log.d(TAG, ":onMethodCall method:${call.method} args:${call.arguments}")

            fun finishOnMain(block: () -> Unit) {
                publicationMainHandler.post(block)
            }

            try {
                val res = handleMethodCallsQueue(
                    call.method,
                    call.arguments
                ).getOrElse { error ->
                    finishOnMain { result.publicationError(call.method, error) }
                    return@launch
                }

                finishOnMain {
                    when {
                        res == null || res is Unit -> result.success(null)
                        else -> result.success(res)
                    }
                }
            } catch (_: NotImplementedError) {
                finishOnMain { result.notImplemented() }
            } catch (e: Exception) {
                Log.e(TAG, "Exception: $e")
                Log.e(TAG, "${e.stackTrace}")

                // TODO: Handle unknown errors better.
                finishOnMain {
                    result.error(
                        e.javaClass.toString(),
                        e.toString(),
                        e.stackTraceToString()
                    )
                }
            }
        }
    }

    /**
     * This function can be used to handle method calls sequentially if needed.
     */
    private suspend fun handleMethodCallsQueue(
        method: String,
        arguments: Any?
    ): Try<Any?, PublicationError> {
        when (method) {
            "setCustomHeaders" -> {
                @Suppress("UNCHECKED_CAST")
                val args = arguments as? Map<String, Map<String, String>> ?: emptyMap()
                val httpHeaders = args["httpHeaders"] ?: emptyMap()

                ReadiumReader.setDefaultHttpHeaders(httpHeaders)
                return Try.success(null)
            }

            "setLcpPassphrase" -> {
                val args = arguments as? List<Any?> ?: emptyList()
                val passphrase = args[0] as String
                val preserveDrmScheme = (args.getOrNull(1) as? Boolean) == true
                ReadiumReader.setLcpPassphrase(passphrase, preserveDrmScheme = preserveDrmScheme)
                return Try.success(null)
            }

            "setDrmConfiguration" -> {
                @Suppress("UNCHECKED_CAST")
                val map = arguments as? Map<String, Any?> ?: emptyMap()
                val scheme = (map["scheme"] as? Number)?.toInt() ?: 0
                val passphrase = map["passphrase"] as? String
                Log.d(TAG, "setDrmConfiguration branch: scheme=$scheme passphrase=${passphrase != null}")
                ReadiumReader.setDrmConfiguration(scheme, passphrase)
                return Try.success(null)
            }

            "loadPublication" -> {
                val args = arguments as List<Any?>
                val pubUrlStr = args[0] as String
                return loadPublication(pubUrlStr)
            }

            "openPublication" -> {
                val args = arguments as List<Any?>
                val pubUrlStr = args[0] as String

                return openPublication(pubUrlStr)
            }

            "closePublication" -> {
                ReadiumReader.closePublication()
                return Try.success(null)
            }

            "ttsEnable" -> {
                val args = arguments as Map<*, *>?
                val ttsPrefs = FlutterTtsPreferences.fromMap(args, ReadiumReader.ttsGetAvailableVoices())
                return ttsEnable(ttsPrefs)
            }

            "ttsSetPreferences" -> {
                val args = arguments as Map<*, *>?
                val ttsPrefs = FlutterTtsPreferences.fromMap(args, ReadiumReader.ttsGetAvailableVoices())
                return ttsSetPreferences(ttsPrefs)
            }

            "setDecorationStyle" -> {
                val args = arguments as List<*>
                val uttDecoMap = args[0] as Map<*, *>?
                val rangeDecoMap = args[1] as Map<*, *>?
                val decorationPreferences = FlutterDecorationPreferences.fromMap(uttDecoMap, rangeDecoMap)

                return setDecorationStyle(decorationPreferences)
            }

            "ttsGetAvailableVoices" -> {
                return Try.success(ttsGetAvailableVoices())
            }

            "ttsSetVoice" -> {
                val args = arguments as List<*>
                val voiceId = args[0] as String?
                val language = args[1] as String?

                ReadiumReader.ttsSetPreferredVoice(voiceId, language)

                return Try.success(null)
            }

            "play" -> {
                val args = arguments as List<*>
                val fromLocator = (args[0] as? Map<*, *>)?.let {
                    Locator.fromJSON(JSONObject(it))
                }

                ReadiumReader.play(fromLocator)

                return Try.success(null)
            }

            "pause" -> {
                ReadiumReader.pause()

                return Try.success(null)
            }

            "resume" -> {
                ReadiumReader.resume()

                return Try.success(null)
            }

            "stop" -> {
                ReadiumReader.stop()

                return Try.success(null)
            }

            "next" -> {
                ReadiumReader.next()

                return Try.success(null)
            }

            "previous" -> {
                ReadiumReader.previous()

                return Try.success(null)
            }

            "goToLocator" -> {
                val args = arguments as List<*>
                val locator = (args[0] as? Map<*, *>)?.let {
                    Locator.fromJSON(JSONObject(it))
                }

                if (locator == null) {
                    throw Exception("goToLocator: failed to go to locator. Missing locator: ${args[0]} ")
                }

                ReadiumReader.goToLocator(locator)

                return Try.success(null)
            }

            "audioEnable" -> {
                val args = arguments as List<*>
                // 0 is AudioPreferences
                val prefs = args[0] as Map<*, *>?

                val preferences = prefs?.let { FlutterAudioPreferences.fromMap(it) }
                    ?: FlutterAudioPreferences()

                val locator = (args[1] as? Map<*, *>)?.let {
                    Locator.fromJSON(JSONObject(it))
                }

                return audioEnable(locator, preferences)
            }

            "audioSetPreferences" -> {
                val prefs = arguments as Map<*, *>?
                val preferences =
                    prefs?.let { FlutterAudioPreferences.fromMap(it) }
                        ?: FlutterAudioPreferences()

                ReadiumReader.audioUpdatePreferences(preferences)

                return Try.success(null)
            }

            "audioSeekBy" -> {
                val seekOffsetSeconds = arguments as Int
                ReadiumReader.audioSeek(seekOffsetSeconds.toDouble())
                return Try.success(null)
            }

            "searchInPublication" -> {
                ReadiumReader.currentPublication ?: return Try.failure(
                    PublicationError.Unavailable()
                )
                val query = arguments as String
                val searchResult = ReadiumReader.searchInPublication(query).getOrElse {
                    return Try.failure(PublicationError.Unknown(message = it.message ?: "Search failed"))
                }

                val textSearchResults = searchResult.flatMap { col ->
                    col.locators.map { TextSearchResult(locator = it, chapterTitle = it.title, pageNumbers = null) }
                }
                return Try.success(textSearchResults.map { it.toJSON().toString() })
            }

            else -> {
                Log.w(
                    TAG,
                    "handleMethodCallsQueue: no branch for method=[$method] len=${method.length} " +
                        "(if this is setDrmConfiguration, the running APK does not include that branch — run flutter clean and full rebuild)",
                )
                throw NotImplementedError()
            }
        }
    }

    /**
     * Serializes [publication.manifest] for the Flutter side.
     *
     * The native [EpubReaderFragment] uses [ReadiumReader.currentPublication], not this string.
     * The full RWPM includes a `resources` array with every packaged asset; serializing and sending
     * it over the MethodChannel can take a long time or effectively hang the reader on large EPUBs.
     * Dart only needs metadata, reading order, TOC, and top-level links for UI helpers.
     */
    private fun publicationManifestJsonStringForFlutter(publication: Publication): String {
        val full = publication.manifest
        // `Manifest.toJSON()` inclui `resources` na árvore — construir esse JSONArray gigante
        // bloqueia a UI mesmo antes de `remove`/`toString`. Usar cópia sem resources.
        val slimManifest: Manifest = full.copy(
            resources = emptyList(),
            subcollections = emptyMap(),
        )
        val json = slimManifest.toJSON()
        val slim = json.toString().replace("\\/", "/")
        Log.d(
            TAG,
            "manifestForFlutter: readingOrder=${full.readingOrder.size} " +
                "resourcesOmitted=${full.resources.size} slimJsonChars=${slim.length}",
        )
        return slim
    }

    /**
     * Load and return the publication manifest from a URL without opening it.
     */
    private suspend fun loadPublication(pubUrlStr: String): Try<String, PublicationError> {
        val publication =
            ReadiumReader.loadPublicationFromUrl(pubUrlStr).getOrElse { error ->
                return Try.failure(error)
            }

        val pubJsonManifest = publicationManifestJsonStringForFlutter(publication)

        // Close the publication to avoid leaks.
        publication.close()
        return Try.success(pubJsonManifest)
    }

    /**
     * Open a publication from a URL. If another publication is already opened, it will be closed first.
     *
     * There can be only one... opened publication at a time.
     */
    private suspend fun openPublication(pubUrlStr: String): Try<String, PublicationError> {
        val publication =
            ReadiumReader.openPublicationFromUrl(pubUrlStr).getOrElse { error ->
                return Try.failure(error)
            }

        val pubJsonManifest = publicationManifestJsonStringForFlutter(publication)

        return Try.success(pubJsonManifest)
    }

    /**
     * Enable TTS reading with the provided preferences.
     */
    private suspend fun ttsEnable(prefs: FlutterTtsPreferences): Try<Any?, PublicationError> {
        // This only makes sense if a publication is open.
        ReadiumReader.currentPublication ?: return Try.failure(
            PublicationError.Unavailable()
        )

        ReadiumReader.ttsEnable(prefs)
        return Try.success(null)
    }

    /**
     * Update the TTS preferences. The TTS must be enabled first.
     */
    private suspend fun ttsSetPreferences(ttsPrefs: FlutterTtsPreferences): Try<Any?, PublicationError> {
        // This only makes sense if a publication is open.
        ReadiumReader.currentPublication ?: return Try.failure(
            PublicationError.Unavailable()
        )

        ReadiumReader.ttsSetPreferences(ttsPrefs)
        return Try.success(null)
    }

    suspend fun setDecorationStyle(
        decorationPreferences: FlutterDecorationPreferences
    ): Try<Any?, PublicationError> {
        try {
            ReadiumReader.setDecorationStyle(decorationPreferences)
            return Try.success(null)
        } catch (_: Error) {
            return Try.failure(PublicationError.Unknown("Failed to set decoration style"))
        }
    }

    /**
     * Get the list of available TTS voices on the device.
     */
    suspend fun ttsGetAvailableVoices(): List<String> {
        // If no voices are available, return an empty list.
        val androidVoices = ReadiumReader.ttsGetAvailableVoices()

        val ttsPrefs = ReadiumReader.ttsGetPreferences()
        val voices = ttsPrefs?.voices?.values?.toSet() ?: setOf()

        val voicesJson = androidVoices.map {
            JSONObject().apply {
                put("identifier", it.id.value)
                put(
                    "name",
                    it.id.value
                ) // ID should be mapped to a readable name on Flutter side.
                put("quality", it.quality.name.lowercase())
                put("networkRequired", it.requiresNetwork)
                put("language", it.language.code)
                put("active", voices.contains(it.id.value))
            }.toString()
        }

        return voicesJson
    }

    /**
     * Enable audio (audiobook) reading with optional locator to start from and audio preferences.
     */
    private suspend fun audioEnable(
        locator: Locator?,
        preferences: FlutterAudioPreferences
    ): Try<Any?, PublicationError> {
        // This only makes sense if a publication is open.
        ReadiumReader.currentPublication ?: return Try.failure(
            PublicationError.Unavailable()
        )

        ReadiumReader.audioEnable(locator, preferences)
        return Try.success(null)
    }
}

/**
 * Send a PublicationError back to Flutter via MethodChannel.Result
 */
fun MethodChannel.Result.publicationError(method: String, error: PublicationError) {
    Log.e(
        TAG,
        "$method: PublicationError<${error.errorCode}>: ${error.message}, cause=${error.cause}"
    )

    // StandardMessageCodec only accepts null, bool, int, double, String, Uint8List, List, Map.
    // Readium [Error] / [ReadError] instances are not encodable — stringify to avoid crashing the app.
    val details: String? =
        error.cause?.let { c ->
            when (c) {
                is Throwable ->
                    "${c}\n${c.stackTraceToString()}"
                else ->
                    c.toString()
            }.take(12_000)
        }

    this.error(
        error.errorCode.name,
        error.message,
        details,
    )
}
