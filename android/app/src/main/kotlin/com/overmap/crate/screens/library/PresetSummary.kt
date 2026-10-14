package com.overmap.crate.screens.library

import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.em
import com.overmap.crate.app.AppModel
import com.overmap.crate.core.Query
import com.overmap.crate.core.StyleLabel
import com.overmap.crate.ui.components.GenreSummary
import com.overmap.crate.ui.theme.CrateTheme

/**
 * The preset summary line (README "Presets summary line"): genre & style · vibes ("+" = all of) · ⌗ tags · BPM ·
 * energy · key · vocals. Parts that are "any" are dropped, except in the Save sheet ([includeAny]).
 */
class PresetSummary(q: Query, model: AppModel, includeAny: Boolean) {
    data class Part(val text: String, val symbol: String? = null, val isAny: Boolean = false)

    val parts: List<Part>

    init {
        val out = mutableListOf<Part>()
        val genre = genreText(q, model)
        if (genre != null) out += Part(genre) else if (includeAny) out += Part("Any genre", isAny = true)

        if (q.vibesInclude.isNotEmpty()) {
            out += Part(q.vibesInclude.joinToString(" + ") { id ->
                model.snapshot.vibes.vibes[id]?.name ?: id.replaceFirstChar { it.uppercase() }
            })
        } else if (includeAny) out += Part("any vibe", isAny = true)

        if (q.tagsInclude.isNotEmpty()) out += Part(q.tagsInclude.joinToString(" + "), symbol = "tag")
        else if (includeAny) out += Part("no tags", isAny = true)

        val b = q.bpm
        if (b != null) {
            val max = if (b.max >= 1000) "∞" else b.max.toInt().toString()
            out += Part("${b.min.toInt()}–$max" + if (b.halfDouble) " ×½" else "")
        } else if (includeAny) out += Part("any BPM", isAny = true)

        val e = q.energy
        if (e != null && !(e.min <= 1 && e.max >= 10)) {
            out += Part(if (e.min == e.max) "${e.min}" else "${e.min}–${e.max}", symbol = "bolt.fill")
        } else if (includeAny) out += Part("any energy", isAny = true)

        val k = q.key
        if (k != null) out += Part(k.camelot + if (k.compatible) " + compatible" else "")
        else if (includeAny) out += Part("any key", isAny = true)

        when (q.vocal) {
            Query.Vocal.vocal -> out += Part("vocal")
            Query.Vocal.instrumental -> out += Part("instrumental")
            Query.Vocal.any -> if (includeAny) out += Part("vocals any", isAny = true)
        }
        if (includeAny) out += Part("limit ${q.limit}", isAny = true)
        parts = out
    }

    val isEmpty: Boolean get() = parts.isEmpty()

    /** Plain words for TalkBack ("tags italian", "energy 6 to 10"). */
    val spoken: String
        get() = if (parts.isEmpty()) "Whole library" else parts.joinToString(", ") { p ->
            when (p.symbol) {
                "tag" -> "tags ${p.text}"
                "bolt.fill" -> "energy ${p.text.replace("–", " to ")}"
                else -> p.text.replace("–", " to ").replace("×½", "half or double")
            }
        }

    /** Styled text: "any" parts in tertiary, symbols inline. */
    fun annotated(secondary: Color, tertiary: Color): AnnotatedString = buildAnnotatedString {
        if (parts.isEmpty()) {
            withStyle(SpanStyle(color = secondary)) { append("Whole library") }
            return@buildAnnotatedString
        }
        parts.forEachIndexed { i, p ->
            if (i > 0) withStyle(SpanStyle(color = tertiary)) { append(" · ") }
            withStyle(SpanStyle(color = if (p.isAny) tertiary else secondary)) {
                if (p.symbol != null) {
                    appendInlineContent(p.symbol, if (p.symbol == "tag") "#" else "ϟ")
                    append(" ")
                }
                append(p.text)
            }
        }
    }

    companion object {
        private fun genreText(q: Query, model: AppModel): String? {
            val tax = model.snapshot.taxonomy
            val order = tax.families
            val entries = mutableListOf<Pair<String, String>>()
            for (f in q.familiesInclude) entries += f to LibraryText.familyName(f, tax)
            for ((f, styles) in q.familyStyles) {
                if (styles.isEmpty()) continue
                val name = LibraryText.familyName(f, tax)
                val all = (model.index.families.firstOrNull { it.family == f }?.styles?.map { it.first } ?: emptyList()).toMutableList()
                all += styles.filter { it !in all }.sorted()
                val summary = if (all.isEmpty()) GenreSummary.Styles(styles) else GenreSummary.make(styles.toSet(), all)
                val display = { s: String -> StyleLabel(s, tax.aliases).styleDisplay }
                val text = when (summary) {
                    GenreSummary.AllStyles -> name
                    is GenreSummary.Except -> "$name except ${summary.names.joinToString(", ") { display(it) }}"
                    is GenreSummary.Styles -> "$name: ${summary.names.joinToString(", ") { short(display(it), name) }}"
                    is GenreSummary.Count -> "$name: ${summary.ticked} styles"
                }
                entries += f to text
            }
            entries.sortWith(compareBy<Pair<String, String>> { order.indexOf(it.first).let { i -> if (i < 0) 99 else i } }.thenBy { it.first })
            val texts = entries.map { it.second }.toMutableList()
            if (q.stylesInclude.isNotEmpty()) texts += q.stylesInclude.joinToString(", ") { StyleLabel(it, tax.aliases).styleDisplay }
            if (q.genresInclude.isNotEmpty()) texts += q.genresInclude.joinToString(", ") { tax.aliases[it] ?: it }
            if (texts.isEmpty()) return null
            val rich = texts.any { it.contains(":") || it.contains(" except ") }
            return texts.joinToString(if (rich) "; " else ", ")
        }

        /** "Hard Techno" under Techno → "Hard"; "Techno" stays. */
        private fun short(style: String, family: String): String {
            val suffix = " $family"
            return if (style.length > suffix.length && style.lowercase().endsWith(suffix.lowercase())) style.dropLast(suffix.length) else style
        }
    }
}

/** The summary as one Text with inline tag / bolt glyphs. TalkBack reads [PresetSummary.spoken]. */
@Composable
fun PresetSummaryText(
    summary: PresetSummary,
    style: TextStyle,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
    speak: Boolean = true,
) {
    val c = CrateTheme.colors
    val inline = mapOf(
        "tag" to InlineTextContent(Placeholder(0.9.em, 0.9.em, PlaceholderVerticalAlign.TextCenter)) {
            Icon(Icons.Filled.Tag, null, tint = c.textSecondary)
        },
        "bolt.fill" to InlineTextContent(Placeholder(0.9.em, 0.9.em, PlaceholderVerticalAlign.TextCenter)) {
            Icon(Icons.Filled.Bolt, null, tint = c.textSecondary)
        },
    )
    Text(
        summary.annotated(c.textSecondary, c.textTertiary), style = style, inlineContent = inline,
        maxLines = maxLines, overflow = TextOverflow.Ellipsis,
        modifier = if (speak) modifier.clearAndSetSemantics { contentDescription = summary.spoken } else modifier.clearAndSetSemantics {},
    )
}
