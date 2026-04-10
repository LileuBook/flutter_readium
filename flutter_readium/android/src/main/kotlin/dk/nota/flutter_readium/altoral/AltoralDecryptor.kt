@file:OptIn(org.readium.r2.shared.InternalReadiumApi::class)

package dk.nota.flutter_readium.altoral

import org.readium.lcp.sdk.DRMContext
import org.readium.lcp.sdk.Lcp
import org.readium.r2.shared.InternalReadiumApi
import org.readium.r2.shared.extensions.coerceFirstNonNegative
import org.readium.r2.shared.extensions.inflate
import org.readium.r2.shared.extensions.requireLengthFitInt
import org.readium.r2.shared.publication.encryption.Encryption
import org.readium.r2.shared.publication.protection.ContentProtection
import org.readium.r2.shared.util.DebugError
import org.readium.r2.shared.util.ThrowableError
import org.readium.r2.shared.util.Try
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.data.ReadError
import org.readium.r2.shared.util.flatMap
import org.readium.r2.shared.util.getEquivalent
import org.readium.r2.shared.util.getOrElse
import org.readium.r2.shared.util.resource.FailureResource
import org.readium.r2.shared.util.resource.Resource
import org.readium.r2.shared.util.resource.TransformingResource
import org.readium.r2.shared.util.resource.flatMap

private val LCP_RESOURCE_SCHEME = ContentProtection.Scheme.Lcp.uri

/**
 * Decrypts EPUB (or RPF) resources using the same AES-CBC layout as LCP basic profile,
 * driven by [org.readium.lcp.sdk.Lcp] and a pre-built [DRMContext] (no LCP license signatures).
 */
internal class AltoralStreamDecryptor(
    private val unlock: AltoralUnlock?,
    private val encryptionData: Map<Url, Encryption>,
) {
    fun transform(url: Url, resource: Resource): Resource =
        resource.flatMap {
            val encryption = encryptionData.getEquivalent(url)
            if (encryption == null || encryption.scheme != LCP_RESOURCE_SCHEME) {
                return@flatMap resource
            }
            when {
                unlock == null ->
                    FailureResource(
                        ReadError.Decoding(
                            DebugError(
                                "Cannot decipher content because the publication is locked.",
                            ),
                        ),
                    )
                encryption.isDeflated || !encryption.isCbcEncrypted ->
                    FullAltoralResource(resource, encryption, unlock)
                else ->
                    CbcAltoralResource(resource, unlock, encryption.originalLength)
            }
        }
}

internal class AltoralUnlock(
    private val lcp: Lcp,
    private val context: DRMContext,
) {
    fun decrypt(data: ByteArray): Try<ByteArray, ReadError> =
        try {
            Try.success(lcp.decrypt(context, data))
        } catch (e: Exception) {
            Try.failure(
                ReadError.Decoding(
                    DebugError("Altoral decrypt failed", ThrowableError(e)),
                ),
            )
        }

    suspend fun decryptFully(
        data: Try<ByteArray, ReadError>,
        isDeflated: Boolean,
    ): Try<ByteArray, ReadError> =
        data.flatMap { encryptedData ->
            if (encryptedData.isEmpty()) {
                return Try.success(encryptedData)
            }
            var bytes =
                decrypt(encryptedData).getOrElse {
                    return Try.failure(
                        ReadError.Decoding(DebugError("Failed to decrypt the resource", it)),
                    )
                }
            if (bytes.isEmpty()) {
                return Try.failure(
                    ReadError.Decoding(DebugError("Altoral decrypt returned empty data")),
                )
            }
            val padding = bytes.last().toInt() and 0xFF
            if (padding !in bytes.indices) {
                return Try.failure(
                    ReadError.Decoding(
                        DebugError(
                            "The padding length of the encrypted resource is incorrect: $padding / ${bytes.size}",
                        ),
                    ),
                )
            }
            bytes = bytes.copyOfRange(0, bytes.size - padding)
            if (isDeflated) {
                bytes =
                    bytes.inflate(nowrap = true).getOrElse {
                        return Try.failure(
                            ReadError.Decoding(
                                DebugError("Cannot inflate the decrypted resource", ThrowableError(it)),
                            ),
                        )
                    }
            }
            Try.success(bytes)
        }
}

private class FullAltoralResource(
    resource: Resource,
    private val encryption: Encryption,
    private val unlock: AltoralUnlock,
) : TransformingResource(resource) {
    override suspend fun transform(data: Try<ByteArray, ReadError>): Try<ByteArray, ReadError> =
        unlock.decryptFully(data, encryption.isDeflated)

    override suspend fun length(): Try<Long, ReadError> =
        encryption.originalLength?.let { Try.success(it) }
            ?: super.length()
}

@Suppress("NAME_SHADOWING")
internal class CbcAltoralResource(
    resource: Resource,
    private val unlock: AltoralUnlock,
    private val originalLength: Long? = null,
) : Resource by resource {
    private val resource = CachingRangeTailResource(resource, 4 * AES_BLOCK_SIZE)
    private var builtinPaddingLength: Int? = null

    override suspend fun length(): Try<Long, ReadError> {
        originalLength?.let { return Try.success(it) }
        val encryptedLength = resource.length().getOrElse { return Try.failure(it) }
        if (encryptedLength == 0L) {
            return Try.success(0)
        }
        if (encryptedLength < 2L * AES_BLOCK_SIZE) {
            return Try.failure(
                ReadError.Decoding(DebugError("Invalid CBC-encrypted stream.")),
            )
        }
        val paddingLength =
            builtinPaddingLength
                ?: readPaddingLength(encryptedLength)
                    .onSuccess { builtinPaddingLength = it }
                    .getOrElse { return Try.failure(it) }
                ?: 0
        val len = encryptedLength - AES_BLOCK_SIZE.toLong() - paddingLength
        return Try.success(len)
    }

    private suspend fun readPaddingLength(encryptedSize: Long): Try<Int?, ReadError> {
        val readOffset = encryptedSize - 2L * AES_BLOCK_SIZE
        val bytes = resource.read(readOffset until encryptedSize + 1).getOrElse { return Try.failure(it) }
        if (bytes.size != 2 * AES_BLOCK_SIZE) {
            return Try.success(null)
        }
        val decryptedBytes =
            unlock.decrypt(bytes).getOrElse {
                return Try.failure(
                    ReadError.Decoding(DebugError("Can't decrypt trailing size of CBC-encrypted stream")),
                )
            }
        check(decryptedBytes.size == AES_BLOCK_SIZE)
        return Try.success(decryptedBytes.last().toInt())
    }

    override suspend fun read(range: LongRange?): Try<ByteArray, ReadError> {
        if (range == null) {
            return unlock.decryptFully(resource.read(), isDeflated = false)
        }
        val range = range.coerceFirstNonNegative().requireLengthFitInt()
        if (range.isEmpty()) {
            return Try.success(ByteArray(0))
        }
        val startPadding = range.first - range.first.floorMultipleOf(AES_BLOCK_SIZE.toLong())
        val endPadding = (range.last + 1).ceilMultipleOf(AES_BLOCK_SIZE.toLong()) - (range.last + 1)
        val encryptedStart = range.first - startPadding
        val encryptedEndExclusive = range.last + 1 + endPadding + 2 * AES_BLOCK_SIZE
        val encryptedRangeSize = (encryptedEndExclusive - encryptedStart).toInt()
        val encryptedData = resource.read(encryptedStart until encryptedEndExclusive).getOrElse { return Try.failure(it) }
        if (encryptedData.size % AES_BLOCK_SIZE != 0) {
            return Try.failure(
                ReadError.Decoding(DebugError("Encrypted data size is not a multiple of AES block size.")),
            )
        }
        if (encryptedData.size < 2 * AES_BLOCK_SIZE) {
            return Try.success(ByteArray(0))
        }
        val dataIncludesBuiltinPadding = encryptedData.size < encryptedRangeSize
        check(encryptedData.size <= encryptedRangeSize)
        val missingEndSize = encryptedRangeSize - encryptedData.size
        val bytes =
            unlock.decrypt(encryptedData).getOrElse {
                return Try.failure(
                    ReadError.Decoding(
                        DebugError(
                            "Can't decrypt the content for resource with key: ${resource.sourceUrl}",
                            it,
                        ),
                    ),
                )
            }
        check(bytes.size == encryptedData.size - AES_BLOCK_SIZE)
        val builtinPaddingLength =
            if (dataIncludesBuiltinPadding) {
                bytes.last().toInt().also { builtinPaddingLength = it }
            } else {
                0
            }
        val correctedEndPadding = (endPadding.toInt() + AES_BLOCK_SIZE - missingEndSize).coerceAtLeast(builtinPaddingLength)
        val dataSlice = startPadding.toInt() until bytes.size - correctedEndPadding
        return Try.success(bytes.sliceArray(dataSlice))
    }

    private companion object {
        private const val AES_BLOCK_SIZE = 16
    }
}

private class CachingRangeTailResource(
    private val resource: Resource,
    private val cacheLength: Int,
) : Resource by resource {
    private class Cache(
        var startIndex: Int?,
        val data: ByteArray,
    )

    private val cache = Cache(null, ByteArray(cacheLength))

    override suspend fun read(range: LongRange?): Try<ByteArray, ReadError> {
        if (range == null) {
            return resource.read(null)
        }
        val range = range.coerceFirstNonNegative().requireLengthFitInt()
        if (range.isEmpty()) {
            return Try.success(ByteArray(0))
        }
        val cacheStartIndex =
            cache.startIndex?.takeIf { cacheStart ->
                val cacheEndExclusive = cacheStart + cacheLength
                val rangeBeginsInCache = range.first in cacheStart until cacheEndExclusive
                val rangeGoesBeyondCache = cacheEndExclusive <= range.last + 1
                rangeBeginsInCache && rangeGoesBeyondCache
            }
        val result =
            if (cacheStartIndex == null) {
                resource.read(range)
            } else {
                readWithCache(cacheStartIndex, cache.data, range)
            }
        if (result is Try.Success && result.value.size >= cacheLength) {
            val offsetInResult = result.value.size - cacheLength
            cache.startIndex = (range.last + 1 - cacheLength).toInt()
            result.value.copyInto(cache.data, 0, offsetInResult)
        }
        return result
    }

    private suspend fun readWithCache(
        cacheStartIndex: Int,
        cachedData: ByteArray,
        range: LongRange,
    ): Try<ByteArray, ReadError> {
        require(range.first >= cacheStartIndex)
        require(range.last + 1 >= cacheStartIndex + cachedData.size)
        val offsetInCache = (range.first - cacheStartIndex).toInt()
        val fromCacheLength = cachedData.size - offsetInCache
        val newData = resource.read(range.first + fromCacheLength..range.last).getOrElse { return Try.failure(it) }
        val out = ByteArray(fromCacheLength + newData.size)
        cachedData.copyInto(out, 0, offsetInCache)
        newData.copyInto(out, fromCacheLength)
        return Try.success(out)
    }
}

private val Encryption.isDeflated: Boolean
    get() = compression?.lowercase(java.util.Locale.ROOT) == "deflate"

private val Encryption.isCbcEncrypted: Boolean
    get() = algorithm == "http://www.w3.org/2001/04/xmlenc#aes256-cbc"

private fun Long.ceilMultipleOf(divisor: Long) =
    divisor * (this / divisor + if (this % divisor == 0L) 0 else 1)

private fun Long.floorMultipleOf(divisor: Long) = divisor * (this / divisor)
