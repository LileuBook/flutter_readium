package dk.nota.flutter_readium.altoral

import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.protection.ContentProtection
import org.readium.r2.shared.publication.services.ContentProtectionService
import org.readium.r2.shared.util.Error

/**
 * Publication service marker for Altoral-unlocked books (no LCP license object).
 */
internal class AltoralContentProtectionService(
    @Suppress("unused") private val licenseJson: String,
    private val unlocked: Boolean,
    override val error: Error?,
) : ContentProtectionService {
    override val isRestricted: Boolean get() = !unlocked

    override val credentials: String? get() = null

    override val rights: ContentProtectionService.UserRights
        get() =
            if (unlocked) {
                ContentProtectionService.UserRights.Unrestricted
            } else {
                ContentProtectionService.UserRights.AllRestricted
            }

    override val scheme: ContentProtection.Scheme?
        get() = ContentProtection.Scheme(ALTORAL_SCHEME_URI)

    override val name: String?
        get() = "Altoral"
}
