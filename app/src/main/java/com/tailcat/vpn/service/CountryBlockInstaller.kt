package com.tailcat.vpn.service

import com.tailcat.vpn.core.geo.CountryRanges
import com.tailcat.vpn.core.geo.CountryRangesException

/**
 * Installs the user's blocked countries into the engine before a session
 * exists (see [TailcatVpnService]).
 *
 * The one rule: never fall back to "connect without the filter". Data that
 * cannot be loaded, a list the engine rejects, and a DNS resolver inside a
 * blocked country all abandon the start. They are [PermanentStartFailure]s
 * because retrying cannot fix any of them: the user changes a setting, or the
 * profile's DNS server, instead.
 */
internal object CountryBlockInstaller {

    /**
     * @param engine the engine's setter, `(prefixes, resolverIp)`. It is always
     *   called when the data loads, with an empty list when nothing is selected,
     *   so a filter from an earlier start can never linger. It is never called
     *   when the data does not load.
     */
    fun install(
        countries: Set<String>,
        resolverIp: String,
        ranges: CountryRanges,
        engine: (prefixes: String, resolverIp: String) -> Unit
    ) {
        val prefixes = try {
            ranges.prefixesFor(countries)
        } catch (e: CountryRangesException) {
            throw PermanentStartFailure(
                "Blocked-country data could not be loaded (${e.message}), so the tunnel did not " +
                    "start. Clear the country list in Settings or reinstall the app."
            )
        }
        try {
            engine(prefixes, resolverIp)
        } catch (e: IllegalStateException) {
            throw PermanentStartFailure(e.message ?: "The blocked-country list was rejected")
        }
    }
}
