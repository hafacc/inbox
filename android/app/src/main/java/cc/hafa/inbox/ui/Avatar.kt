package cc.hafa.inbox.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cc.hafa.inbox.core.compose.addressKey
import cc.hafa.inbox.core.contacts.sizedPhotoUrl
import cc.hafa.inbox.core.mail.Person
import cc.hafa.inbox.core.mail.initials
import cc.hafa.inbox.core.theme.avatarIndex
import cc.hafa.inbox.data.PhotoCache
import cc.hafa.inbox.ui.theme.paper
import coil3.compose.AsyncImage

/** Contact photos to draw in avatars, by address; a failed one falls back to initials. */
class ContactPhotos(
    private val byAddress: Map<String, String> = emptyMap(),
    private val failed: Set<String> = emptySet(),
    private val onFailed: (String) -> Unit = {},
) {
    /** The photo URL (without a size) for an address, or null to draw initials. */
    fun urlFor(email: String): String? = byAddress[addressKey(email)]?.takeIf { it !in failed }

    /** Note that a photo failed to load. */
    fun markFailed(url: String) = onFailed(url)
}

/** The contact photos avatars draw. */
val LocalContactPhotos = staticCompositionLocalOf { ContactPhotos() }

/** A person's contact photo, else their initials on the theme's avatar colors picked from their address, as on web. */
@Composable
fun Avatar(person: Person, modifier: Modifier = Modifier, size: Dp = 40.dp, single: Boolean = false) {
    val letters = initials(person, single)
    val photos = LocalContactPhotos.current
    val url = photos.urlFor(person.email)
    val colors = paper.avatars[avatarIndex(person.email, paper.avatars.size)]
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(size)
            .background(colors.first, CircleShape),
    ) {
        Text(
            letters,
            color = colors.second,
            fontWeight = FontWeight.SemiBold,
            fontSize = (size.value * 0.35f).sp,
            maxLines = 1,
        )
        if (url != null) {
            val px = with(LocalDensity.current) { size.roundToPx() }
            SideEffect { PhotoCache.noteSize(px) }
            AsyncImage(
                model = sizedPhotoUrl(url, px),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                onError = { photos.markFailed(url) },
                modifier = Modifier.size(size).clip(CircleShape),
            )
        }
    }
}
