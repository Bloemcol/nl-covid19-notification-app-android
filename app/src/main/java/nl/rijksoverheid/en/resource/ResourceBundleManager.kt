/*
 * Copyright (c) 2020 De Staat der Nederlanden, Ministerie van Volksgezondheid, Welzijn en Sport.
 *  Licensed under the EUROPEAN UNION PUBLIC LICENCE v. 1.2
 *
 *  SPDX-License-Identifier: EUPL-1.2
 */
package nl.rijksoverheid.en.resource

import android.content.Context
import nl.rijksoverheid.en.R
import nl.rijksoverheid.en.api.CacheStrategy
import nl.rijksoverheid.en.api.CdnService
import nl.rijksoverheid.en.api.model.ResourceBundle
import nl.rijksoverheid.en.util.formatDaysSince
import nl.rijksoverheid.en.util.formatExposureDate
import nl.rijksoverheid.en.util.formatExposureDateShort
import timber.log.Timber
import java.time.Clock
import java.time.LocalDate

private const val DEFAULT_LANGUAGE = "en"

class ResourceBundleManager(
    private val context: Context,
    private val cdnService: CdnService,
    private val clock: Clock = Clock.systemDefaultZone()
) {
    private var resourceBundle: ResourceBundle? = null

    private suspend fun loadResourceBundle(): ResourceBundle {
        val bundle = if (resourceBundle == null) {
            getResourceBundleFromCacheOrNetwork() ?: loadDefaultResourceBundle()
        } else {
            resourceBundle
        }
        this.resourceBundle = bundle
        return bundle!!
    }

    private fun loadDefaultResourceBundle(): ResourceBundle {
        return context.resources.openRawResource(R.raw.default_guidance).use {
            ResourceBundle.load(it) ?: error("Error loading default guidance")
        }
    }

    private suspend fun getResourceBundleFromCacheOrNetwork(): ResourceBundle? {
        return try {
            cdnService.getManifest(CacheStrategy.CACHE_FIRST).resourceBundleId?.let {
                cdnService.getResourceBundle(it, CacheStrategy.CACHE_FIRST)
            }
        } catch (ex: Exception) {
            Timber.w(ex, "Error fetching resource bundle")
            null
        }
    }

    suspend fun getExposureNotificationGuidance(exposureDate: LocalDate): List<ResourceBundle.Guidance.Element> {
        val bundle = loadResourceBundle()
        val language = context.getString(R.string.app_language)
        val localeMap = bundle.resources[language]
        val fallback = bundle.resources[DEFAULT_LANGUAGE]
            ?: error("No resources for default language $DEFAULT_LANGUAGE")

        return bundle.guidance.layout.mapNotNull {
            when (it) {
                is ResourceBundle.Guidance.Element.Paragraph -> {
                    val title = localeMap?.get(it.title) ?: fallback[it.title] ?: it.title
                    val body = localeMap?.get(it.body) ?: fallback[it.body] ?: it.body
                    ResourceBundle.Guidance.Element.Paragraph(
                        title.replacePlaceHolders(exposureDate, bundle.guidance.quarantineDays),
                        body.replacePlaceHolders(exposureDate, bundle.guidance.quarantineDays)
                    )
                }
                is ResourceBundle.Guidance.Element.Unknown -> null
            }
        }
    }

    private fun String.replacePlaceHolders(exposureDate: LocalDate, quarantineDays: Int): String {
        val exposureDateFormatted = exposureDate.formatExposureDate(context)
        val stayHomeUntilDate = exposureDate.plusDays(quarantineDays.toLong())
            .formatExposureDateShort(context)
        val daysSinceExposure = exposureDate.formatDaysSince(context, clock)
        return this.replace("\\\n", "\n")
            .replace("{ExposureDate}", exposureDateFormatted)
            .replace("{ExposureDaysAgo}", daysSinceExposure)
            .replace("{StayHomeUntilDate}", stayHomeUntilDate)
    }
}