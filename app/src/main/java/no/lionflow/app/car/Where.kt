package no.lionflow.app.car

import android.content.Context
import android.location.Geocoder
import android.telephony.TelephonyManager
import java.util.Locale

/** Which country the car is in: from the mobile network the phone is on, else from the car's position. */
object Where {
    private var cached: Pair<Long, String?>? = null

    fun country(ctx: Context): String? {
        cached?.let { (t, c) -> if (System.currentTimeMillis() - t < 10 * 60_000L) return c }
        var cc = try {
            (ctx.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager)?.networkCountryIso?.uppercase(Locale.ROOT)?.takeIf { it.length == 2 }
        } catch (_: Exception) { null }
        if (cc == null) {
            val st = CarHub.status.value
            val la = st?.lat; val lo = st?.lon
            if (la != null && lo != null) cc = try {
                @Suppress("DEPRECATION")
                Geocoder(ctx, Locale.ROOT).getFromLocation(la, lo, 1)?.firstOrNull()?.countryCode?.uppercase(Locale.ROOT)
            } catch (_: Exception) { null }
        }
        cached = System.currentTimeMillis() to cc
        return cc
    }

    fun inNorway(ctx: Context) = country(ctx) == "NO"

    /** The spoken language for where the car is (English where we don't know it). */
    fun speechLanguage(ctx: Context): String = when (country(ctx)) {
        "NO", "SJ" -> "nb-NO"; "SE" -> "sv-SE"; "DK" -> "da-DK"; "FI" -> "fi-FI"; "IS" -> "is-IS"
        "DE" -> "de-DE"; "AT" -> "de-AT"; "CH" -> "de-CH"; "LI" -> "de-DE"; "LU" -> "fr-LU"
        "FR", "MC" -> "fr-FR"; "BE" -> "fr-BE"; "NL" -> "nl-NL"; "ES" -> "es-ES"; "PT" -> "pt-PT"; "IT", "SM" -> "it-IT"
        "PL" -> "pl-PL"; "CZ" -> "cs-CZ"; "SK" -> "sk-SK"; "HU" -> "hu-HU"; "SI" -> "sl-SI"; "HR" -> "hr-HR"; "RO" -> "ro-RO"
        "GR" -> "el-GR"; "TR" -> "tr-TR"; "EE" -> "et-EE"; "LV" -> "lv-LV"; "LT" -> "lt-LT"
        "GB" -> "en-GB"; "IE" -> "en-IE"; "AU" -> "en-AU"; "CA" -> "en-CA"; "US" -> "en-US"
        else -> "en-GB"
    }
}
