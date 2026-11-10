package com.example.myandroidapp.todo.ui.posts
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
    actions: @Composable (Post) -> Unit = {}
){
    Log.d("PostList","recompose")
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(12.dp)
    ) {
        items(posts) { item ->
            PostComponent(item, onClick = { onPostClick(item.id) }, actions = { actions(item) })
        }
    }
}
@Composable
fun PostComponent(
    dep: Post, // Modelul Post similar celui din React
    onClick: () -> Unit,
    actions: @Composable () -> Unit = {},
//    onLike: () -> Unit,
//    onComment: () -> Unit,
//    onShare: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(8.dp),
        elevation = CardDefaults.cardElevation(4.dp)
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            // Header: User Info
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                // Profile Image
                val decodedImage: Bitmap? = dep.user_profile_photo?.let {
                    val base64String = it.substringAfter("base64,") // Elimină prefixul
                    val decodedBytes = Base64.decode(base64String, Base64.DEFAULT)
                    BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.size)
                }

                if (decodedImage != null) {
                    Image(
                        bitmap = decodedImage.asImageBitmap(),
                        contentDescription = "User Profile",
                        modifier = Modifier
                            .size(50.dp)
                            .clip(RoundedCornerShape(25.dp))
                            .background(MaterialTheme.colorScheme.secondary)
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text(text = "${dep.user_first_name} ${dep.user_last_name}", style = MaterialTheme.typography.titleMedium)
                    Text(text = dep.created_at, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Post Description
            Text(text = dep.description, style = MaterialTheme.typography.bodyLarge)

            Spacer(modifier = Modifier.height(8.dp))

            // Optional Post Image
            PostPhoto(photo = dep.photo)

            Spacer(modifier = Modifier.height(8.dp))

            // Action Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceAround
            ) {
                Button(onClick = {}) {
                    Text("Like")
                }
                Button(onClick = {}) {
                    Text("Comment")
                }
                Button(onClick = {}) {
                    Text("Share")
                }
            }
            // Actiunile permise rolului curent (sterge / raporteaza)
            actions()
        }
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
