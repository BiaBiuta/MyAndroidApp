package com.example.myandroidapp.todo.ui.posts
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
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
    emptyText: String = "Nicio postare de afișat."
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
            PostComponent(item, onClick = { onPostClick(item.id) }, showSyncStatus = isOwn(item), actions = { actions(item) })
        }
    }
}

/**
 * Cardul unei postari: autor si data, titlu, rating, data vizitei, insignele de stare
 * (sincronizare, ciorna, ascunsa de moderator), textul si poza.
 */
@Composable
fun PostComponent(
    dep: Post,
    onClick: () -> Unit,
    showSyncStatus: Boolean = false,
    actions: @Composable () -> Unit = {}
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(2.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            // Autorul si data publicarii
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "${dep.user_first_name} ${dep.user_last_name}".trim().ifBlank { "Călător" },
                        style = MaterialTheme.typography.labelLarge
                    )
                    if (dep.created_at.isNotBlank()) {
                        Text(
                            text = dep.created_at.take(16).replace('T', ' '),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            if (dep.title.isNotBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(text = dep.title, style = MaterialTheme.typography.titleLarge)
            }
            if (dep.rating > 0 || dep.visit_date > 0) {
                Spacer(modifier = Modifier.height(4.dp))
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
                Text(text = dep.description, style = MaterialTheme.typography.bodyLarge)
            }
            if (dep.photo.isNotBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                PostPhoto(photo = dep.photo)
            }
            // Actiunile permise rolului curent (editeaza / sterge / raporteaza)
            actions()
        }
    }
}

/** Stelele unui rating (doar afisare); TalkBack citeste "4 din 5 stele". */
@Composable
fun RatingStars(rating: Int) {
    Row(modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = "$rating din 5 stele" }) {
        for (star in 1..5) {
            Icon(
                imageVector = if (star <= rating) Icons.Filled.Star else Icons.Outlined.StarBorder,
                contentDescription = null,
                tint = if (star <= rating) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(18.dp)
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
        if (post.status == PostStatus.DRAFT) add(Triple(Icons.Default.EditNote, "Ciornă", BadgeTone.NEUTRAL))
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
fun PostPhoto(photo: String?, modifier: Modifier = Modifier) {
    if (photo.isNullOrBlank()) return
    val imageModifier = modifier
        .fillMaxWidth()
        .height(200.dp)
        .clip(RoundedCornerShape(8.dp))
    val base64Data = photo.substringAfter("base64,", "")
    if (base64Data.isNotEmpty()) {
        val bitmap = try {
            val decodedBytes = Base64.decode(base64Data, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.size)
        } catch (e: IllegalArgumentException) {
            null
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
        Image(
            painter = rememberImagePainter(data = photo),
            contentDescription = "Poza postării",
            modifier = imageModifier,
            contentScale = ContentScale.Crop
        )
    }
}

@Composable
fun PostDetail(post: Post, onPostClick: (id: String) -> Unit) {
    //TODO maps-ul
    Log.d("PostDetail","recompose id = ${post.id}")
    Row{
        ClickableText(text= AnnotatedString(post.id)) { }
    }
}
