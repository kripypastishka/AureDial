package dev.auredial.domain

import java.text.Normalizer
import java.util.Locale

private val keypadLetters = listOf("abcабвг", "defдежз", "ghiийкл", "jklмноп", "mnoрсту", "pqrsфхцч", "tuvшщъы", "wxyzьэюя")

/** Matches normalized numbers or the beginning of a contact name token. */
fun t9Matches(query: String, name: String?, number: String): Boolean {
    if (query.isEmpty() || query.any { it !in '0'..'9' }) return false
    if (number.filter { it in '0'..'9' }.contains(query)) return true
    if (name.isNullOrBlank() || query.any { it < '2' }) return false

    val normalized = Normalizer.normalize(name.lowercase(Locale.ROOT).replace('й', 'и'), Normalizer.Form.NFD)
    // Keep the Cyrillic Й key aligned with И, and allow diacritics such as ё and é.
    val words = normalized.filterNot { Character.getType(it) == Character.NON_SPACING_MARK.toInt() }
        .split(' ', '-', '\'', '’')
    return words.any { word ->
        word.length >= query.length && query.indices.all { i ->
            word[i] in keypadLetters[query[i] - '2']
        }
    }
}
