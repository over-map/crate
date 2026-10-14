package com.overmap.crate.core

import kotlinx.serialization.Serializable
import java.text.Normalizer

/**
 * A key on the Camelot wheel: number 1–12 around the circle of fifths, letter A (minor) or B (major).
 * Create with `Camelot("8A")`, `Camelot(number = 8, isMajor = false)` or `Camelot(tonic = "A", scale = "minor")`;
 * each returns `null` for invalid input.
 */
class Camelot private constructor(
    /** Wheel position, 1–12. */
    val number: Int,
    /** `true` for the B ring (major), `false` for A (minor). */
    val isMajor: Boolean,
) : Comparable<Camelot> {
    /** The code, e.g. `8A`. */
    val code: String get() = "$number${if (isMajor) "B" else "A"}"

    /** Display name, e.g. `A minor`. Flats, except `F♯ minor` (11A) and `F♯ major` (2B). */
    val name: String
        get() {
            val minor = listOf("A♭", "E♭", "B♭", "F", "C", "G", "D", "A", "E", "B", "F♯", "D♭")
            val major = listOf("B", "F♯", "D♭", "A♭", "E♭", "B♭", "F", "C", "G", "D", "A", "E")
            return "${(if (isMajor) major else minor)[number - 1]} ${if (isMajor) "major" else "minor"}"
        }

    /** The key itself, its two neighbours on the same ring (12 wraps to 1) and the relative key on the other ring. */
    val compatible: List<Camelot>
        get() = listOf(
            this,
            Camelot((number + 10) % 12 + 1, isMajor),
            Camelot(number % 12 + 1, isMajor),
            Camelot(number, !isMajor),
        )

    /** True when `other` is in [compatible]. */
    fun isCompatible(with: Camelot): Boolean = with in compatible

    override fun compareTo(other: Camelot): Int =
        compareValuesBy(this, other, { it.number }, { if (it.isMajor) 1 else 0 })

    override fun equals(other: Any?): Boolean = other is Camelot && other.number == number && other.isMajor == isMajor
    override fun hashCode(): Int = number * 2 + if (isMajor) 1 else 0
    override fun toString(): String = code

    companion object {
        /** Creates a key from its parts; `null` unless `number` is 1–12. */
        operator fun invoke(number: Int, isMajor: Boolean): Camelot? =
            if (number in 1..12) Camelot(number, isMajor) else null

        /** Parses a code such as `8A` or `12B`. Surrounding spaces and lowercase letters are accepted. */
        operator fun invoke(code: String): Camelot? {
            val text = code.trim().uppercase()
            val letter = text.lastOrNull() ?: return null
            if (letter != 'A' && letter != 'B') return null
            val digits = text.dropLast(1)
            if (digits.length !in 1..2 || !digits.all { it in '0'..'9' } || digits.first() == '0') return null
            return invoke(digits.toInt(), letter == 'B')
        }

        /**
         * Converts a musical key such as `("A", "minor")` or `("Bb", "major")`. Accepts `#`/`♯` and `b`/`♭`,
         * and scales `minor`/`min`/`m` or `major`/`maj`.
         */
        operator fun invoke(tonic: String, scale: String): Camelot? {
            val pitch = pitchClass(tonic) ?: return null
            val s = scale.trim().lowercase()
            val major = when {
                s.startsWith("maj") -> true
                s.startsWith("min") || s == "m" -> false
                else -> return null
            }
            // C major is 8B; each step round the circle of fifths adds 1. A minor key shares its relative major's number.
            val majorRoot = if (major) pitch else (pitch + 3) % 12
            val fifths = (majorRoot * 7) % 12
            return invoke((8 - 1 + fifths) % 12 + 1, major)
        }

        /** True for a valid Camelot code. */
        fun isValid(code: String): Boolean = invoke(code) != null

        /** All 24 keys, 1A…12A then 1B…12B. */
        val all: List<Camelot> = listOf(false, true).flatMap { major -> (1..12).map { Camelot(it, major) } }

        /** Pitch class (C = 0) of a tonic spelled with sharps or flats. */
        internal fun pitchClass(tonic: String): Int? {
            val t = tonic.trim()
            val letter = t.firstOrNull()?.uppercaseChar() ?: return null
            var pc = mapOf('C' to 0, 'D' to 2, 'E' to 4, 'F' to 5, 'G' to 7, 'A' to 9, 'B' to 11)[letter] ?: return null
            for (accidental in t.drop(1)) {
                pc += when (accidental) {
                    '#', '♯' -> 1
                    'b', '♭' -> -1
                    else -> return null
                }
            }
            return (pc % 12 + 12) % 12
        }
    }
}

/** The mood vocabulary and its icon names. Moods are stored as plain strings; this type only adds presentation. */
@Suppress("EnumEntryName")
enum class Mood(
    /** SF Symbol name for the mood chip (map to a Material icon on Android). */
    val symbol: String,
) {
    dark("moon.fill"), energetic("bolt.fill"), melancholic("cloud.rain.fill"), dreamy("sparkles"),
    uplifting("sunrise.fill"), relaxing("leaf.fill"), aggressive("flame.fill"), party("party.popper.fill"),
    vocal("mic.fill"), instrumental("pianokeys"), hypnotic("circle.dotted.circle"), happy("face.smiling");

    val rawValue: String get() = name

    companion object {
        /** Symbol used for moods without a dedicated one. */
        const val fallbackSymbol = "tag"

        /** The mood with this name, or `null`. */
        fun of(name: String): Mood? = entries.firstOrNull { it.name == name }

        /** The symbol for any mood name, falling back to [fallbackSymbol]. */
        fun symbol(name: String): String = of(name)?.symbol ?: fallbackSymbol

        /** Moods shown as chips on Build ("vocal" and "instrumental" are covered by the Vocals control). */
        fun isChip(name: String): Boolean = name != "vocal" && name != "instrumental"
    }
}

/** A canonical Discogs label `Genre---Style`, split into its parts, with display names from the taxonomy aliases. */
class StyleLabel(
    /** The full canonical label, e.g. `Electronic---Techno`. Queries and storage always use this. */
    val canonical: String,
    aliases: Map<String, String> = emptyMap(),
) {
    /** Canonical genre, e.g. `Electronic`. */
    val genre: String
    /** Canonical style part, e.g. `Techno` (the whole label when there is no separator). */
    val style: String
    /** User-facing genre name (alias applied), e.g. `Funk & Soul`. */
    val genreDisplay: String
    /** User-facing style name (alias applied). */
    val styleDisplay: String

    init {
        val at = canonical.indexOf(separator)
        genre = if (at >= 0) canonical.substring(0, at) else canonical
        style = if (at >= 0) canonical.substring(at + separator.length) else canonical
        genreDisplay = aliases[genre] ?: genre
        styleDisplay = aliases[canonical] ?: aliases[style] ?: style
    }

    /** "Style · Genre", as shown on chips. */
    val display: String get() = "$styleDisplay · $genreDisplay"

    override fun equals(other: Any?): Boolean = other is StyleLabel && other.canonical == canonical &&
        other.genreDisplay == genreDisplay && other.styleDisplay == styleDisplay
    override fun hashCode(): Int = canonical.hashCode() * 31 + styleDisplay.hashCode()
    override fun toString(): String = canonical

    companion object {
        /** Separator between genre and style in canonical labels. */
        const val separator = "---"

        /** The parent genre of a canonical style label (`Electronic---Techno` → `Electronic`). */
        fun genre(of: String): String = of.indexOf(separator).let { if (it >= 0) of.substring(0, it) else of }

        /** Joins a genre and style into a canonical label. */
        fun canonical(genre: String, style: String): String = genre + separator + style
    }
}

/** Which of a genre hue's three shades a style uses. Stable across launches and devices. */
@Suppress("EnumEntryName")
enum class StyleShade(val rawValue: Int) {
    light(0), base(1), deep(2);

    companion object {
        /** `[light, base, deep][fnv1a32(utf8(canonical)) % 3]`. */
        fun of(canonicalStyle: String): StyleShade = entries[(fnv1a32(canonicalStyle) % 3u).toInt()]

        /** 32-bit FNV-1a over the UTF-8 bytes of `text` (offset basis 2166136261, prime 16777619). */
        fun fnv1a32(text: String): UInt {
            var hash = 2_166_136_261u
            for (byte in text.encodeToByteArray()) {
                hash = hash xor byte.toUByte().toUInt()
                hash *= 16_777_619u
            }
            return hash
        }
    }
}

/**
 * Normalises community tags (Last.fm, Discogs, Beatport) so that "Hip-Hop", "hiphop" and "rap" compare equal.
 * The steps and synonym table are shared with Python through `spec/tag_synonyms.json`; the module bundles a copy.
 */
data class TagNormaliser(
    /** Exact-match synonyms applied after the text steps (`italy` → `italian`). */
    val synonyms: Map<String, String>,
) {
    /** Reads the `synonyms` object of a `tag_synonyms.json` file. */
    constructor(json: ByteArray) : this(
        CrateJSON.json.decodeFromString(SynonymFile.serializer(), json.decodeToString()).synonyms
    )

    @Serializable
    private class SynonymFile(val synonyms: Map<String, String>)

    /**
     * NFKD, drop combining marks, lower-case, remove `'` and `’`, `&` → ` and `, each of `- _ / . ,` → space,
     * collapse whitespace, trim, then map synonyms. `"  Hip-Hop "` → `"hip hop"`.
     */
    fun normalise(tag: String): String {
        val decomposed = Normalizer.normalize(tag, Normalizer.Form.NFKD)
        val stripped = StringBuilder()
        var i = 0
        while (i < decomposed.length) {
            val cp = decomposed.codePointAt(i)
            if (Character.getType(cp) != Character.NON_SPACING_MARK.toInt()) stripped.appendCodePoint(cp)
            i += Character.charCount(cp)
        }
        val text = stripped.toString().lowercase()
            .replace("'", "").replace("’", "").replace("&", " and ")
        val out = StringBuilder()
        var pendingSpace = false
        for (ch in text) {
            if (ch in "-_/.," || ch.isWhitespace()) {
                pendingSpace = true
            } else {
                if (pendingSpace && out.isNotEmpty()) out.append(' ')
                pendingSpace = false
                out.append(ch)
            }
        }
        val result = out.toString()
        return synonyms[result] ?: result
    }

    /** Normalises every tag and drops the ones that become empty. */
    fun normaliseAll(tags: List<String>): Set<String> = tags.map(::normalise).filter { it.isNotEmpty() }.toSet()

    /** A track's normalised tags, or `null` when it has never been tagged (no `tags` key). */
    fun trackTags(track: Track): Set<String>? = track.tags?.let(::normaliseAll)

    companion object {
        /** The copy bundled with the module. Falls back to no synonyms if the resource is missing (a test guards this). */
        val bundled: TagNormaliser by lazy {
            runCatching {
                TagNormaliser::class.java.getResourceAsStream("tag_synonyms.json")!!.use { TagNormaliser(it.readBytes()) }
            }.getOrElse { TagNormaliser(emptyMap()) }
        }
    }
}

/** Shorthand for the bundled [TagNormaliser]. */
object Tags {
    /** Normalises `tag` with the bundled synonym table. */
    fun normalise(tag: String): String = TagNormaliser.bundled.normalise(tag)
}
