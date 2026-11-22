package cc.hafa.inbox.core.contacts

import cc.hafa.inbox.core.compose.addressKey
import cc.hafa.inbox.core.gmail.GmailJson
import cc.hafa.inbox.core.gmail.HttpTransport
import cc.hafa.inbox.core.gmail.TokenSource
import cc.hafa.inbox.core.gmail.UrlConnectionTransport
import cc.hafa.inbox.core.gmail.googleRequest
import cc.hafa.inbox.core.gmail.queryString
import cc.hafa.inbox.core.mail.Person
import kotlinx.serialization.Serializable
import java.text.Normalizer

/** A Google contact reduced to what autocomplete needs. */
@Serializable
data class Contact(
    /** People API resource name, e.g. `people/c123` or `otherContacts/c456` */
    val resourceName: String,
    /** display name; may be empty */
    val name: String,
    /** email addresses, primary first */
    val emails: List<String>,
    /** whether it is an "other contact" (someone mailed) rather than a saved contact */
    val other: Boolean,
    /** photo URL without a size, or null when there is none or only Google's placeholder */
    val photoUrl: String? = null,
)

/** Which People API list a sync reads. */
enum class ContactSource(
    /** path under `https://people.googleapis.com/v1/` */
    val path: String,
    /** name of the field listing the fields to return */
    val fieldsParam: String,
    /** name of the response field holding the people */
    val listField: String,
) {
    /** the user's saved contacts */
    CONNECTIONS("people/me/connections", "personFields", "connections"),

    /** people the user has mailed but not saved */
    OTHER("otherContacts", "readMask", "otherContacts"),
}

@Serializable
private data class PeopleName(val displayName: String? = null, val metadata: PeopleFieldMetadata? = null)

@Serializable
private data class PeopleEmail(val value: String? = null, val metadata: PeopleFieldMetadata? = null)

@Serializable
private data class PeopleMetadata(val deleted: Boolean = false)

@Serializable
private data class PeopleFieldMetadata(val primary: Boolean = false)

@Serializable
private data class PeoplePhoto(
    val metadata: PeopleFieldMetadata? = null,
    val url: String? = null,
    val default: Boolean = false,
)

@Serializable
private data class PeoplePerson(
    val resourceName: String = "",
    val metadata: PeopleMetadata? = null,
    val names: List<PeopleName>? = null,
    val emailAddresses: List<PeopleEmail>? = null,
    val photos: List<PeoplePhoto>? = null,
)

@Serializable
private data class PeopleConnections(
    val connections: List<PeoplePerson>? = null,
    val otherContacts: List<PeoplePerson>? = null,
    val nextPageToken: String? = null,
    val nextSyncToken: String? = null,
)

/** One page of a contacts sync: contacts changed or added, resource names deleted, and paging. */
data class ContactsPage(
    /** contacts changed or added */
    val changed: List<Contact>,
    /** resource names deleted */
    val deleted: List<String>,
    /** token for the next page, if any */
    val nextPageToken: String?,
    /** token for the next sync, on the last page */
    val nextSyncToken: String?,
)

/** HTTP status the People API answers when a sync token has expired. */
const val SYNC_TOKEN_EXPIRED = 410

/** The People API calls for autocomplete: saved contacts and "other contacts", synced with sync tokens. */
class PeopleClient(
    private val tokens: TokenSource,
    private val transport: HttpTransport = UrlConnectionTransport,
) {
    /**
     * One page of [source]. Without [syncToken] it lists everything; with one, only what changed
     * since (deleted people marked). The last page carries the next sync token.
     */
    suspend fun page(source: ContactSource, syncToken: String?, pageToken: String?): ContactsPage {
        val params = mutableListOf(
            source.fieldsParam to FIELDS,
            "pageSize" to "1000",
            "requestSyncToken" to "true",
        )
        syncToken?.let { params += "syncToken" to it }
        pageToken?.let { params += "pageToken" to it }
        val text = googleRequest(tokens, transport, "GET", "$PEOPLE_BASE${source.path}?${queryString(params)}")
        val page = GmailJson.decodeFromString(PeopleConnections.serializer(), text)
        val people = (if (source == ContactSource.CONNECTIONS) page.connections else page.otherContacts).orEmpty()
        val (deleted, kept) = people.partition { it.metadata?.deleted == true }
        return ContactsPage(
            changed = kept.mapNotNull { person ->
                val emails = person.emailAddresses.orEmpty().sortedByDescending { it.metadata?.primary == true }
                    .mapNotNull { it.value?.trim()?.takeIf(String::isNotEmpty) }
                    .distinctBy(::addressKey)
                if (emails.isEmpty() || person.resourceName.isEmpty()) {
                    null
                } else {
                    Contact(
                        person.resourceName,
                        person.names.orEmpty().sortedByDescending { it.metadata?.primary == true }.firstOrNull()?.displayName?.trim().orEmpty(),
                        emails,
                        source == ContactSource.OTHER,
                        photoOf(person.photos),
                    )
                }
            },
            deleted = deleted.map { it.resourceName } +
                kept.filter { it.emailAddresses.isNullOrEmpty() }.map { it.resourceName },
            nextPageToken = page.nextPageToken,
            nextSyncToken = page.nextSyncToken,
        )
    }

    /** What every request asks for. */
    companion object {
        /** `personFields` / `readMask`; a sync token only covers the fields it was made with */
        const val FIELDS = "names,emailAddresses,photos,metadata"
        private const val PEOPLE_BASE = "https://people.googleapis.com/v1/"
    }
}

// The primary photo, else the first; none when it is Google's generated placeholder.
private fun photoOf(photos: List<PeoplePhoto>?): String? {
    val photo = photos.orEmpty().firstOrNull { it.metadata?.primary == true } ?: photos?.firstOrNull()
    return photo?.takeIf { !it.default }?.url?.takeIf(String::isNotBlank)
}

private val SIZE_PARAM_RE = Regex("""([?&])sz=[^&#]*""")

/** A contact photo URL asking for [px] device pixels: its `sz` query parameter set, replacing any there. */
fun sizedPhotoUrl(url: String, px: Int): String {
    val fragmentAt = url.indexOf('#')
    val base = if (fragmentAt < 0) url else url.substring(0, fragmentAt)
    val fragment = if (fragmentAt < 0) "" else url.substring(fragmentAt)
    val sized = if (SIZE_PARAM_RE.containsMatchIn(base)) {
        base.replace(SIZE_PARAM_RE) { "${it.groupValues[1]}sz=$px" }
    } else {
        "$base${if ('?' in base) "&" else "?"}sz=$px"
    }
    return sized + fragment
}

/** One address of one person, as autocomplete offers it. */
data class BookEntry(
    /** display name; may be empty */
    val name: String,
    /** address */
    val email: String,
    /** photo URL, or null */
    val photoUrl: String?,
    /** a saved contact, rather than someone the user has mailed */
    val saved: Boolean,
)

/**
 * The address book autocomplete searches, one entry per address: the first entry for an address
 * wins (callers list saved contacts first), and later ones only fill in its missing name or photo.
 */
fun mergeBook(entries: List<BookEntry>): List<BookEntry> {
    val byAddress = LinkedHashMap<String, BookEntry>()
    for (entry in entries) {
        val key = addressKey(entry.email)
        val known = byAddress[key]
        if (known == null) {
            byAddress[key] = entry
        } else if ((known.name.isEmpty() && entry.name.isNotEmpty()) || (known.photoUrl == null && entry.photoUrl != null)) {
            byAddress[key] = known.copy(name = known.name.ifEmpty { entry.name }, photoUrl = known.photoUrl ?: entry.photoUrl)
        }
    }
    return byAddress.values.toList()
}

/** The address book of [contacts]: saved contacts first, then other contacts, merged by [mergeBook]. */
fun contactBook(contacts: List<Contact>): List<BookEntry> = mergeBook(
    contacts.sortedBy { it.other }.flatMap { contact ->
        contact.emails.map { BookEntry(contact.name, it, contact.photoUrl, saved = !contact.other) }
    },
)

private val MARKS_RE = Regex("""\p{M}+""")
private val WORD_BREAK_RE = Regex("""[\s.,\-_@+'"()]+""")

/** Text as matching compares it: lower-cased, accents dropped (`José` → `jose`). */
fun foldText(text: String): String = Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD).replace(MARKS_RE, "")

/** The words of a name or address, folded, split at spaces and punctuation. */
fun contactWords(text: String): List<String> = foldText(text).split(WORD_BREAK_RE).filter(String::isNotEmpty)

/**
 * How well an entry matches what's typed; 0 for no match. Every typed word must start a word of
 * the name or address, in any order. Whole-address and name starts score highest; saved contacts
 * beat others, and named ones beat bare addresses.
 */
fun matchScore(entry: BookEntry, query: String): Int {
    val typed = foldText(query.trim())
    val typedWords = contactWords(typed)
    val firstWord = typedWords.firstOrNull() ?: return 0
    val nameWords = contactWords(entry.name)
    val allWords = nameWords + contactWords(entry.email)
    if (!typedWords.all { word -> allWords.any { it.startsWith(word) } }) return 0
    var score = 1
    if (foldText(entry.email).startsWith(typed)) score += 4
    if (foldText(entry.name).startsWith(typed)) score += 4
    if (nameWords.any { it.startsWith(firstWord) }) score += 2
    if (entry.saved) score += 3
    if (entry.name.isNotEmpty()) score += 1
    return score
}

/**
 * The best [limit] entries for what's typed, best first, leaving out the addresses in [exclude]
 * (already chosen). Ties go by name (or address, when unnamed), then address.
 */
fun searchContacts(book: List<BookEntry>, query: String, exclude: Collection<String> = emptyList(), limit: Int = 6): List<BookEntry> {
    val excluded = exclude.map(::addressKey).toSet()
    return book.asSequence()
        .filter { addressKey(it.email) !in excluded }
        .map { it to matchScore(it, query) }
        .filter { (_, score) -> score > 0 }
        .sortedWith(
            compareByDescending<Pair<BookEntry, Int>> { it.second }
                .thenBy { foldText(it.first.name.ifEmpty { it.first.email }) }
                .thenBy { addressKey(it.first.email) },
        )
        .take(limit)
        .map { it.first }
        .toList()
}

/** Contacts matching what's typed in a recipient field, best first ([searchContacts] over [contactBook]). */
fun suggestContacts(contacts: List<Contact>, query: String, exclude: Set<String> = emptySet(), limit: Int = 6): List<Person> =
    searchContacts(contactBook(contacts), query, exclude, limit).map { Person(it.name, it.email) }
