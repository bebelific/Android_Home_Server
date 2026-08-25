package com.printserver.core.adblock

import com.printserver.core.common.PreferencesManager
import java.text.SimpleDateFormat
import java.util.Locale

object ParentalControl {

    val adultList = setOf(
        "pornhub.com", "xvideos.com", "xnxx.com", "xhamster.com", "redtube.com",
        "youporn.com", "tube8.com", "onlyfans.com", "brazzers.com", "chaturbate.com",
        "stripchat.com", "bongacams.com", "livejasmin.com", "adultfriendfinder.com",
        "ashley.com", "ashleymadison.com", "dating.com", "tinder.com", "bumble.com",
        "okcupid.com", "match.com", "pof.com", "grindr.com", "adultwork.com",
        "4chan.org", "8kun.top", "theporndude.com", "porn.com", "fapello.com",
        "nhentai.net", "hentaihaven.xxx", "rule34video.com", "eporner.com",
        "spankbang.com", "pornone.com", "txxx.com", "porntrex.com", "beeg.com",
    )

    val socialList = setOf(
        "tiktok.com", "vm.tiktok.com", "snapchat.com", "instagram.com",
        "facebook.com", "messenger.com", "twitter.com", "x.com", "reddit.com",
        "discord.com", "discordapp.net", "twitch.tv", "9gag.com", "ifunny.co",
        "pinterest.com", "threads.net", "telegram.org", "web.telegram.org",
    )

    data class Decision(val blocked: Boolean, val reason: String)

    fun devices(prefs: PreferencesManager): Set<String> =
        prefs.pcDevices.value.split(',', ';')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()

    fun inScheduleWindow(prefs: PreferencesManager, nowMillis: Long = System.currentTimeMillis()): Boolean {
        if (!prefs.pcScheduleEnabled.value) return false
        val fmt = SimpleDateFormat("HH:mm", Locale.US)
        val now = fmt.format(nowMillis)
        val start = prefs.pcScheduleStart.value
        val end = prefs.pcScheduleEnd.value
        return if (start <= end) now in start..end else now >= start || now <= end
    }

    fun pausedUntil(prefs: PreferencesManager): Long = prefs.pcPauseUntil.value

    fun evaluate(prefs: PreferencesManager, clientIp: String, domain: String, nowMillis: Long = System.currentTimeMillis()): Decision {
        if (!prefs.pcEnabled.value) return Decision(false, "")
        if (clientIp !in devices(prefs)) return Decision(false, "")
        val now = nowMillis
        if (pausedUntil(prefs) > now) return Decision(true, "paused")
        if (inScheduleWindow(prefs, now)) return Decision(true, "bedtime")
        if (prefs.pcBlockAdult.value && matches(domain, adultList)) return Decision(true, "adult")
        if (prefs.pcBlockSocial.value && matches(domain, socialList)) return Decision(true, "social")
        return Decision(false, "")
    }

    private fun matches(domain: String, list: Set<String>): Boolean {
        val d = domain.lowercase().trimEnd('.')
        return list.any { d == it || d.endsWith(".$it") }
    }

    fun timeNow(): String = SimpleDateFormat("HH:mm", Locale.US).format(System.currentTimeMillis())
}
