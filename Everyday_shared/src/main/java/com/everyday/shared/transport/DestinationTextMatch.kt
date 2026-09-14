package com.everyday.shared.transport

import java.text.Normalizer
import java.util.Locale

/** Locale-tolerant text relevance shared by phone retrieval and glasses-side ranking. */
data class DestinationTextMatch(
    val score: Double,
    val tokenCoverage: Double
)

object DestinationTextMatcher {
    private val leadingArticles = setOf("a", "an", "the", "le", "la", "les", "un", "une")
    private val canonicalTokens = mapOf(
        "centre" to "center",
        "theatre" to "theater",
        "travelling" to "traveling",
        "programme" to "program"
    )

    fun match(query: String, displayName: String, secondaryText: String? = null): DestinationTextMatch {
        val normalizedQuery = withoutLeadingArticle(normalize(query))
        val normalizedNames = listOf(displayName, displayName.substringBefore(','))
            .map { withoutLeadingArticle(normalize(it)) }
            .filter(String::isNotBlank)
            .distinct()
        val searchable = normalize(listOfNotNull(displayName, secondaryText).joinToString(" "))
        val queryTokens = normalizedQuery.split(' ').filter(String::isNotBlank).toSet()
        val candidateTokens = searchable.split(' ').filter(String::isNotBlank).toSet()
        val matchingTokens = queryTokens.count { token ->
            candidateTokens.any { candidate ->
                candidate == token ||
                    (token.length >= 4 && candidate.length >= 4 &&
                        (candidate.contains(token) || token.contains(candidate)))
            }
        }
        val coverage = if (queryTokens.isEmpty()) 0.0 else matchingTokens.toDouble() / queryTokens.size
        val exact = if (normalizedQuery.isNotBlank() && normalizedNames.any { it == normalizedQuery }) 1.0 else 0.0
        val prefix = if (normalizedQuery.isNotBlank() && normalizedNames.any { it.startsWith(normalizedQuery) }) 1.0 else 0.0
        val contains = if (normalizedQuery.isNotBlank() &&
            (normalizedNames.any { it.contains(normalizedQuery) } || searchable.contains(normalizedQuery))) 1.0 else 0.0
        return DestinationTextMatch(
            score = (exact * 0.42 + prefix * 0.18 + contains * 0.16 + coverage * 0.24)
                .coerceIn(0.0, 1.0),
            tokenCoverage = coverage
        )
    }

    fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .replace(Regex("\\s+"), " ")
        .trim()
        .let { Normalizer.normalize(it, Normalizer.Form.NFD) }
        .replace(Regex("\\p{M}+"), "")
        .lowercase(Locale.ROOT)
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
        .split(' ')
        .filter(String::isNotBlank)
        .joinToString(" ") { canonicalTokens[it] ?: it }

    private fun withoutLeadingArticle(value: String): String {
        val words = value.split(' ').filter(String::isNotBlank)
        return if (words.size > 1 && words.first() in leadingArticles) words.drop(1).joinToString(" ") else value
    }
}
