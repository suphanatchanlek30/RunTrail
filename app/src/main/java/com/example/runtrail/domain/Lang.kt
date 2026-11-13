package com.example.runtrail.domain

/**
 * UI language for text built outside Android resources (domain and network code).
 * Defaults to Thai so JVM tests need no setup; `App` binds it to the selected locale.
 */
object Lang {

    /** Returns true when the UI is in English. */
    @Volatile
    var english: () -> Boolean = { false }

    /** Picks the Thai or English text for the current language. */
    fun t(th: String, en: String): String = if (english()) en else th
}
