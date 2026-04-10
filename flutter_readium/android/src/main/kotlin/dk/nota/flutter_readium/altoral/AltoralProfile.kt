package dk.nota.flutter_readium.altoral

/**
 * Altoral DRM profile marker inside the license JSON (`encryption.profile`).
 * Native code matches case-insensitively so servers may use e.g. `https://lileu.app/drm/altoral`.
 */
internal const val ALTORAL_LICENSE_PROFILE_SUBSTRING = "altoral"

/** Standard LCP basic profile URI; Altoral stack may keep this in JSON while using Altoral crypto. */
internal const val READIUM_LCP_BASIC_PROFILE_URI = "http://readium.org/lcp/basic-profile"

/** Declared protection scheme for [org.readium.r2.shared.publication.services.ContentProtectionService]. */
internal const val ALTORAL_SCHEME_URI = "https://lileu.app/drm/altoral"
