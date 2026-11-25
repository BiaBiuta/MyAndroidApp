package com.example.myandroidapp.todo.ui.posts
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Favorite
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import com.example.myandroidapp.core.logging.SafeLog
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Place
import androidx.compose.ui.graphics.Color
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import com.example.myandroidapp.todo.data.PostStatus
import com.example.myandroidapp.todo.data.SyncStatus
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import coil.compose.rememberImagePainter
import com.example.myandroidapp.todo.data.Post
typealias OnPostFn=(id:String)->Unit
typealias OnLikeFn=(id:String)->Unit
@Composable
fun PostList(
    posts: List<Post>,
    onPostClick: (String) -> Unit,
    modifier: Modifier,
    // Butoanele afisate sub fiecare postare (depind de rol: sterge / raporteaza)
    actions: @Composable (Post) -> Unit = {},
    // insignele de sincronizare apar doar pe postarile proprii (doar autorul le are in coada)
    isOwn: (Post) -> Boolean = { false },
    emptyText: String = "Nicio postare de afișat.",
    // aprecierea din lista (null = utilizatorul curent nu poate aprecia: admin / vizitator)
    onToggleLike: ((Post) -> Unit)? = null,
    // numele autorului -> profilul lui
    onAuthorClick: ((Post) -> Unit)? = null,
    // postarile salvate si comutatorul (null = nu se pot salva)
    savedIds: Set<String> = emptySet(),
    onToggleSave: ((Post) -> Unit)? = null
) {
    if (posts.isEmpty()) {
        Box(modifier = modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Text(emptyText, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
        return
    }
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp)
    ) {
        items(posts, key = { it.id }) { item ->
            PostComponent(
                item,
                onClick = { onPostClick(item.id) },
                showSyncStatus = isOwn(item),
                onToggleLike = onToggleLike?.let { toggle -> { toggle(item) } },
                onAuthorClick = onAuthorClick?.let { open -> { open(item) } },
                saved = item.id in savedIds,
                onToggleSave = onToggleSave?.let { toggle -> { toggle(item) } },
                actions = { actions(item) }
            )
        }
    }
}

/** Numele autorului afisat pe card ("Călător" daca lipseste). */
fun authorName(post: Post): String =
    "${post.user_first_name} ${post.user_last_name}".trim().ifBlank { "Călător" }

/** "2026-11-16T18:03:00.000Z" -> data si ora locala, scurt (ex. "16 nov. 2026, 20:03"). */
fun formatPostDate(iso: String): String {
    if (iso.isBlank()) return ""
    return try {
        val parser = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }
        val date = parser.parse(iso.take(19)) ?: return iso.take(16).replace('T', ' ')
        java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(date)
    } catch (e: Exception) {
        iso.take(16).replace('T', ' ')
    }
}

/**
 * Cardul unei postari: poza sus, apoi titlul, locul, rating-ul si data vizitei, insignele de stare
 * (sincronizare, ciorna, ascunsa de moderator), inceputul impresiilor si autorul.
 * Atingerea cardului deschide detaliile postarii.
 */
@Composable
fun PostComponent(
    dep: Post,
    onClick: () -> Unit,
    showSyncStatus: Boolean = false,
    onToggleLike: (() -> Unit)? = null,
    onAuthorClick: (() -> Unit)? = null,
    saved: Boolean = false,
    onToggleSave: (() -> Unit)? = null,
    actions: @Composable () -> Unit = {}
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        if (dep.displayPhoto.isNotBlank()) {
            Box {
                PostPhoto(photo = dep.displayPhoto, height = 190.dp, rounded = false)
                // mai multe poze: "+N" pe coperta
                val more = dep.allPhotos.size - 1
                if (more > 0) {
                    androidx.compose.material3.Surface(
                        color = Color.Black.copy(alpha = 0.6f),
                        contentColor = Color.White,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp)
                    ) {
                        Text("+$more poze", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
                    }
                }
            }
        }
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
            if (dep.title.isNotBlank()) {
                Text(
                    text = dep.title,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 2,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
            }
            if (dep.place.isNotBlank()) {
                Spacer(modifier = Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Place,
                        contentDescription = "Locul",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = dep.place,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                }
            }
            if (dep.rating > 0 || dep.visit_date > 0) {
                Spacer(modifier = Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (dep.rating > 0) RatingStars(dep.rating)
                    if (dep.rating > 0 && dep.visit_date > 0) Spacer(modifier = Modifier.width(12.dp))
                    if (dep.visit_date > 0) {
                        Text(
                            text = "Vizitat: ${com.example.myandroidapp.todo.ui.post.formatVisitDate(dep.visit_date)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            PostBadges(dep, showSyncStatus)

            if (dep.description.isNotBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = dep.description,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 3,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                // numele autorului duce la profilul lui
                Text(
                    text = authorName(dep),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (onAuthorClick != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = if (onAuthorClick != null) Modifier.clickable(onClick = onAuthorClick) else Modifier
                )
                val date = formatPostDate(dep.created_at)
                if (date.isNotBlank()) {
                    Text(" · $date", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            // aprecieri si comentarii (doar postarile publicate au)
            if (dep.status == PostStatus.ACTIVE && !dep.isPrivate) {
                SocialRow(post = dep, onToggleLike = onToggleLike, saved = saved, onToggleSave = onToggleSave)
            }
            // Actiunile permise rolului curent (publica / editeaza / sterge / raporteaza)
            actions()
        }
    }
}

/** Stelele unui rating (doar afisare); TalkBack citeste "4 din 5 stele". */
@Composable
fun RatingStars(rating: Int, size: androidx.compose.ui.unit.Dp = 18.dp) {
    Row(modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = "$rating din 5 stele" }) {
        for (star in 1..5) {
            Icon(
                imageVector = if (star <= rating) Icons.Filled.Star else Icons.Outlined.StarBorder,
                contentDescription = null,
                tint = if (star <= rating) Color(0xFFF5A623) else MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(size)
            )
        }
    }
}

/**
 * Insignele de stare. Fiecare are text (nu doar culoare / iconita):
 * "Sincronizată", "În așteptare", "Sincronizare eșuată – atinge pentru a corecta", "Ciornă",
 * "Ascunsă de moderator".
 */
@Composable
fun PostBadges(post: Post, showSyncStatus: Boolean) {
    val badges = buildList {
        if (showSyncStatus) {
            when (post.syncStatus) {
                SyncStatus.PENDING -> add(Triple(Icons.Default.CloudUpload, "În așteptare", BadgeTone.PENDING))
                SyncStatus.FAILED -> add(Triple(Icons.Default.ErrorOutline, "Sincronizare eșuată – atinge pentru a corecta", BadgeTone.ERROR))
                else -> add(Triple(Icons.Default.CloudDone, "Sincronizată", BadgeTone.OK))
            }
        }
        // ciorna se vede bine (doar autorul o vede): trebuie publicata ca s-o vada ceilalti
        if (post.status == PostStatus.DRAFT) add(Triple(Icons.Default.EditNote, "Ciornă · doar tu o vezi", BadgeTone.PENDING))
        if (post.hidden) add(Triple(Icons.Default.VisibilityOff, "Ascunsă de moderator", BadgeTone.ERROR))
    }
    if (badges.isEmpty()) return
    Spacer(modifier = Modifier.height(8.dp))
    FlowRowCompat(badges.map { (icon, text, tone) -> { StatusBadge(icon, text, tone) } })
}

enum class BadgeTone { OK, PENDING, ERROR, NEUTRAL }

@Composable
fun StatusBadge(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, tone: BadgeTone) {
    val (container, content) = when (tone) {
        BadgeTone.OK -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
        BadgeTone.PENDING -> MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
        BadgeTone.ERROR -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
        BadgeTone.NEUTRAL -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(color = container, contentColor = content, shape = RoundedCornerShape(50)) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(14.dp))
            Spacer(modifier = Modifier.width(4.dp))
            Text(text, style = MaterialTheme.typography.labelMedium)
        }
    }
}

/** Insignele pe un rand, care trec pe randul urmator daca nu incap. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlowRowCompat(items: List<@Composable () -> Unit>) {
    androidx.compose.foundation.layout.FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        items.forEach { it() }
    }
}

/**
 * Poza unei postari: Base64 ("data:image/...;base64,...") sau URL / cale de fisier.
 * Folosita in lista principala si in panoul de administrare (postarile raportate).
 */
@Composable
fun PostPhoto(
    photo: String?,
    modifier: Modifier = Modifier,
    height: androidx.compose.ui.unit.Dp = 200.dp,
    rounded: Boolean = true
) {
    if (photo.isNullOrBlank()) return
    // decodarea Base64 o singura data per poza (nu la fiecare recompunere / derulare)
    val imageModifier = modifier
        .fillMaxWidth()
        .height(height)
        .let { if (rounded) it.clip(RoundedCornerShape(8.dp)) else it }
    val base64Data = photo.substringAfter("base64,", "")
    if (base64Data.isNotEmpty()) {
        val bitmap = androidx.compose.runtime.remember(base64Data) {
            try {
                val decodedBytes = Base64.decode(base64Data, Base64.DEFAULT)
                BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.size)
            } catch (e: IllegalArgumentException) {
                null
            }
        }
        bitmap?.let {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = "Poza postării",
                modifier = imageModifier,
                contentScale = ContentScale.Crop
            )
        }
    } else {
        // fisier local sau poza de pe server (cu token si cache, vezi PhotoLoader)
        val context = androidx.compose.ui.platform.LocalContext.current
        coil.compose.AsyncImage(
            model = com.example.myandroidapp.core.ui.PhotoLoader.model(photo),
            imageLoader = com.example.myandroidapp.core.ui.PhotoLoader.get(context),
            contentDescription = "Poza postării",
            modifier = imageModifier.background(MaterialTheme.colorScheme.surfaceVariant),
            contentScale = ContentScale.Crop
        )
    }
}

@Composable
fun PostDetail(post: Post, onPostClick: (id: String) -> Unit) {
    //TODO maps-ul
    SafeLog.d("PostDetail","recompose id = ${post.id}")
    Row{
        ClickableText(text= AnnotatedString(post.id)) { }
    }
}

/** Aprecieri si comentarii: inima se poate apasa doar daca utilizatorul are voie sa aprecieze. */
@Composable
fun SocialRow(post: Post, onToggleLike: (() -> Unit)?, saved: Boolean = false, onToggleSave: (() -> Unit)? = null) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
        val liked = post.liked_by_me
        val heart = if (liked) Icons.Default.Favorite else Icons.Default.FavoriteBorder
        val tint = if (liked) Color(0xFFE53935) else MaterialTheme.colorScheme.onSurfaceVariant
        if (onToggleLike != null) {
            androidx.compose.material3.IconButton(onClick = onToggleLike, modifier = Modifier.size(36.dp)) {
                Icon(heart, contentDescription = if (liked) "Retrage aprecierea" else "Apreciază", tint = tint)
            }
        } else {
            Icon(heart, contentDescription = null, tint = tint, modifier = Modifier.padding(8.dp).size(20.dp))
        }
        Text(
            "${post.like_count}",
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.semantics { contentDescription = "${post.like_count} aprecieri" }
        )
        Spacer(modifier = Modifier.width(16.dp))
        Icon(
            Icons.Default.ChatBubbleOutline,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            "${post.comment_count}",
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.semantics { contentDescription = "${post.comment_count} comentarii" }
        )
        if (onToggleSave != null) {
            Spacer(modifier = Modifier.weight(1f))
            androidx.compose.material3.IconButton(onClick = onToggleSave, modifier = Modifier.size(36.dp)) {
                Icon(
                    if (saved) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                    contentDescription = if (saved) "Scoate din salvate" else "Salvează ca idee de călătorie",
                    tint = if (saved) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
