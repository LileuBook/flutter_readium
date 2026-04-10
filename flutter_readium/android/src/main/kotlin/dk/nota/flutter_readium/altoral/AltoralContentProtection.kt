@file:OptIn(ExperimentalReadiumApi::class)
@file:Suppress("ktlint:standard:max-line-length")

package dk.nota.flutter_readium.altoral

import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.readium.lcp.sdk.Lcp
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.InternalReadiumApi
import org.readium.r2.shared.publication.encryption.Encryption
import org.readium.r2.shared.publication.epub.EpubEncryptionParser
import org.readium.r2.shared.publication.protection.ContentProtection
import org.readium.r2.shared.publication.services.contentProtectionServiceFactory
import org.readium.r2.shared.util.AbsoluteUrl
import org.readium.r2.shared.util.DebugError
import org.readium.r2.shared.util.ThrowableError
import org.readium.r2.shared.util.Try
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.asset.Asset
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.shared.util.asset.ContainerAsset
import org.readium.r2.shared.util.asset.ResourceAsset
import org.readium.r2.shared.util.data.Container
import org.readium.r2.shared.util.data.ReadError
import org.readium.r2.shared.util.data.decodeXml
import org.readium.r2.shared.util.data.readDecodeOrElse
import org.readium.r2.shared.util.format.FormatHints
import org.readium.r2.shared.util.mediatype.MediaType
import org.readium.r2.shared.util.flatMap
import org.readium.r2.shared.util.getOrElse
import org.readium.r2.shared.util.resource.Resource
import org.readium.r2.shared.util.resource.TransformingContainer

/**
 * LCPL-like standalone license (JSON) with `encryption.profile` containing [ALTORAL_LICENSE_PROFILE_SUBSTRING].
 * Unlocks publications using [org.readium.lcp.sdk.Lcp] crypto without Readium LCP services (signatures/LSD).
 */
@OptIn(InternalReadiumApi::class)
internal class AltoralContentProtection(
    private val assetRetriever: AssetRetriever,
    /**
     * When true (Altoral-only DRM mode), accept licenses whose `encryption.profile` is still
     * [READIUM_LCP_BASIC_PROFILE_URI] if they contain a `publication` link — same JSON shape as LCPL.
     */
    private val acceptReadiumBasicProfileAsAltoral: Boolean = false,
    /**
     * When set, the encrypted publication is downloaded to a temp file with [instanceFollowRedirects]
     * disabled and session headers re-applied on each redirect hop. This avoids
     * [HttpURLConnection] silently following cross-origin redirects and dropping `Authorization`
     * (which surfaces as [ReadError.Access] when opening the EPUB over HTTP).
     */
    private val eagerHttpPublicationDownloader: (suspend (AbsoluteUrl) -> File)? = null,
) : ContentProtection {
    override suspend fun open(
        asset: Asset,
        credentials: String?,
        allowUserInteraction: Boolean,
    ): Try<ContentProtection.OpenResult, ContentProtection.OpenError> =
        when (asset) {
            is ContainerAsset -> Try.failure(ContentProtection.OpenError.AssetNotSupported())
            is ResourceAsset -> openLicense(asset, credentials)
        }

    private suspend fun openLicense(
        licenseAsset: ResourceAsset,
        credentials: String?,
    ): Try<ContentProtection.OpenResult, ContentProtection.OpenError> {
        val jsonString =
            licenseAsset.resource.read().getOrElse {
                return Try.failure(ContentProtection.OpenError.Reading(it))
            }.toString(StandardCharsets.UTF_8)

        if (!isAltoralLicenseJson(jsonString, acceptReadiumBasicProfileAsAltoral)) {
            return Try.failure(ContentProtection.OpenError.AssetNotSupported())
        }

        val pass = credentials?.trim()?.takeIf { it.isNotEmpty() }
        if (pass == null) {
            return Try.failure(
                ContentProtection.OpenError.Reading(
                    ReadError.Decoding(DebugError("Missing Altoral passphrase / user key material")),
                ),
            )
        }

        val unlock =
            try {
                buildUnlock(jsonString, pass)
            } catch (e: Exception) {
                return Try.failure(
                    ContentProtection.OpenError.Reading(
                        ReadError.Decoding(
                            DebugError("Invalid Altoral license or passphrase", ThrowableError(e)),
                        ),
                    ),
                )
            }

        val license = JSONObject(jsonString)
        val publicationLink = findPublicationLink(license)
            ?: return Try.failure(
                ContentProtection.OpenError.Reading(
                    ReadError.Decoding(DebugError("Altoral license missing publication link")),
                ),
            )

        val href = publicationLink.getString("href")
        val typeStr = publicationLink.optString("type", "").takeIf { it.isNotEmpty() }
        val mediaType = typeStr?.let { MediaType(it) }

        val url =
            AbsoluteUrl(href)
                ?: return Try.failure(
                    ContentProtection.OpenError.Reading(
                        ReadError.Decoding(DebugError("Invalid publication URL in Altoral license: $href")),
                    ),
                )

        val rawAsset =
            if (eagerHttpPublicationDownloader != null) {
                val localFile =
                    try {
                        eagerHttpPublicationDownloader.invoke(url)
                    } catch (e: Exception) {
                        return Try.failure(
                            ContentProtection.OpenError.Reading(
                                ReadError.Decoding(
                                    DebugError("Altoral publication HTTP download failed", ThrowableError(e)),
                                ),
                            ),
                        )
                    }
                val hints =
                    if (mediaType != null) {
                        FormatHints(mediaType = mediaType)
                    } else {
                        FormatHints()
                    }
                assetRetriever.retrieve(localFile, hints).getOrElse { err ->
                    return Try.failure(
                        when (err) {
                            is AssetRetriever.RetrieveError.Reading ->
                                ContentProtection.OpenError.Reading(err.cause)
                            is AssetRetriever.RetrieveError.FormatNotSupported ->
                                ContentProtection.OpenError.AssetNotSupported(
                                    DebugError("Altoral local publication format not supported: ${err.message}"),
                                )
                        },
                    )
                }
            } else {
                val retrieved =
                    if (mediaType != null) {
                        assetRetriever.retrieve(url, mediaType = mediaType)
                    } else {
                        assetRetriever.retrieve(url, FormatHints())
                    }
                retrieved.getOrElse { return Try.failure(it.toOpenError()) }
            }
        val containerAsset =
            rawAsset as? ContainerAsset
                ?: return Try.failure(
                    ContentProtection.OpenError.AssetNotSupported(
                        DebugError("Altoral license does not point to a container publication"),
                    ),
                )

        return createUnlockedPublication(containerAsset, jsonString, unlock)
    }

    private fun buildUnlock(
        jsonLicense: String,
        passphrase: String,
    ): AltoralUnlock {
        val lcp = Lcp()
        // org.readium.lcp.sdk.Lcp.createContext expects JSON array of hex-encoded SHA-256 hashes (64 chars),
        // not the plain user passphrase. Passing a UUID logs "Unsupported hashed passphrase format".
        val arr = JSONArray()
        for (h in hashedPassphraseCandidates(passphrase)) {
            arr.put(h)
        }
        val ctx = lcp.createContext(jsonLicense, arr.toString(), "")
        return AltoralUnlock(lcp, ctx)
    }

    private fun hashedPassphraseCandidates(passphrase: String): List<String> {
        val trimmed = passphrase.trim()
        if (trimmed.isEmpty()) return emptyList()
        val hex64 = Regex("^[0-9a-fA-F]{64}$")
        if (hex64.matches(trimmed)) {
            return listOf(trimmed.lowercase(Locale.ROOT))
        }
        val seen = linkedSetOf<String>()
        fun addHashOf(s: String) {
            val digest = MessageDigest.getInstance("SHA-256").digest(s.toByteArray(StandardCharsets.UTF_8))
            val hex = digest.joinToString("") { "%02x".format(it) }
            seen.add(hex)
        }
        addHashOf(trimmed)
        if (trimmed != trimmed.lowercase(Locale.ROOT)) {
            addHashOf(trimmed.lowercase(Locale.ROOT))
        }
        if (trimmed != trimmed.uppercase(Locale.ROOT)) {
            addHashOf(trimmed.uppercase(Locale.ROOT))
        }
        val compact = trimmed.replace("-", "")
        if (compact.length == 32 && compact != trimmed) {
            addHashOf(compact)
            addHashOf(compact.lowercase(Locale.ROOT))
        }
        return seen.toList()
    }

    private suspend fun createUnlockedPublication(
        asset: ContainerAsset,
        licenseJson: String,
        unlock: AltoralUnlock,
    ): Try<ContentProtection.OpenResult, ContentProtection.OpenError> {
        val encryptionData =
            parseEncryptionData(asset.container).getOrElse {
                return Try.failure(ContentProtection.OpenError.Reading(it))
            }

        val decryptor = AltoralStreamDecryptor(unlock, encryptionData)
        val container = TransformingContainer(asset.container, decryptor::transform)

        val result =
            ContentProtection.OpenResult(
                asset =
                    ContainerAsset(
                        format = asset.format,
                        container = container,
                    ),
                onCreatePublication = {
                    servicesBuilder.contentProtectionServiceFactory = { _ ->
                        AltoralContentProtectionService(
                            licenseJson = licenseJson,
                            unlocked = true,
                            error = null,
                        )
                    }
                },
            )
        return Try.success(result)
    }

    private suspend fun parseEncryptionData(
        container: Container<Resource>,
    ): Try<Map<Url, Encryption>, ReadError> =
        withContext(Dispatchers.IO) {
            val encryptionResource = container[Url("META-INF/encryption.xml")!!]
                ?: return@withContext Try.failure(ReadError.Decoding("Missing encryption.xml"))
            val encryptionDocument =
                encryptionResource
                    .readDecodeOrElse(
                        decode = { it.decodeXml() },
                        recover = { return@withContext Try.failure(it) },
                    )
            Try.success(EpubEncryptionParser.parse(encryptionDocument))
        }

    private fun AssetRetriever.RetrieveUrlError.toOpenError(): ContentProtection.OpenError =
        when (this) {
            is AssetRetriever.RetrieveUrlError.FormatNotSupported ->
                ContentProtection.OpenError.AssetNotSupported(this)
            is AssetRetriever.RetrieveUrlError.Reading ->
                ContentProtection.OpenError.Reading(cause)
            is AssetRetriever.RetrieveUrlError.SchemeNotSupported ->
                ContentProtection.OpenError.AssetNotSupported(this)
        }

    companion object {
        fun isAltoralLicenseJson(
            json: String,
            acceptReadiumBasicProfile: Boolean = false,
        ): Boolean {
            return try {
                val root = JSONObject(json)
                val enc = root.getJSONObject("encryption")
                val profile = enc.optString("profile", "")
                if (profile.contains(ALTORAL_LICENSE_PROFILE_SUBSTRING, ignoreCase = true)) {
                    return true
                }
                if (acceptReadiumBasicProfile && profile == READIUM_LCP_BASIC_PROFILE_URI) {
                    return findPublicationLink(root) != null
                }
                false
            } catch (_: Exception) {
                false
            }
        }

        fun findPublicationLink(license: JSONObject): JSONObject? {
            val links = license.optJSONArray("links") ?: return null
            for (i in 0 until links.length()) {
                val o = links.optJSONObject(i) ?: continue
                if (o.optString("rel") == "publication") {
                    return o
                }
            }
            return null
        }
    }
}
