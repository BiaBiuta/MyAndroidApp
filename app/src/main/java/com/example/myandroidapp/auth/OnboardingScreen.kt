package com.example.myandroidapp.auth

import android.content.Context
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** Daca prezentarea de la prima pornire a fost deja vazuta pe acest telefon. */
object OnboardingPrefs {
    private const val PREFS = "onboarding"
    private const val KEY_DONE = "done_v1"

    fun isDone(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_DONE, false)

    fun markDone(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_DONE, true).apply()
    }
}

private data class OnboardingPage(val icon: ImageVector, val title: String, val text: String)

private val pages = listOf(
    OnboardingPage(
        Icons.Default.PhotoCamera,
        "Jurnalul tău de călătorie",
        "Fotografiază locul, scrie ce ai simțit, dă-i stele și fixează-l pe hartă. Grupează locurile în călătorii și vezi-ți traseul."
    ),
    OnboardingPage(
        Icons.Default.CloudUpload,
        "Merge și fără semnal",
        "Pe munte sau în roaming, postezi la fel. Totul se salvează pe telefon și se trimite singur când revine internetul: În așteptare → Sincronizat."
    ),
    OnboardingPage(
        Icons.Default.Fingerprint,
        "Amintirile tale, în siguranță",
        "Intră cu e-mail, Google, Apple sau ca vizitator. Deblochezi cu amprenta, iar seiful privat e criptat și nu permite capturi de ecran."
    )
)

/**
 * Prezentarea de la prima pornire: 3 ecrane, glisare sau "Mai departe", "Sari peste" oricand.
 * Apare o singura data pe telefon (OnboardingPrefs), apoi aplicatia merge direct la login.
 */
@Composable
fun OnboardingScreen(onFinish: () -> Unit) {
    val pagerState = rememberPagerState(pageCount = { pages.size })
    val scope = rememberCoroutineScope()
    val last = pagerState.currentPage == pages.lastIndex

    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "WanderLog",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f)
                )
                if (!last) TextButton(onClick = onFinish) { Text("Sari peste") }
            }

            HorizontalPager(state = pagerState, modifier = Modifier.weight(1f).fillMaxWidth()) { index ->
                val page = pages[index]
                Column(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(140.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            page.icon,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(64.dp)
                        )
                    }
                    Spacer(Modifier.height(32.dp))
                    Text(
                        page.title,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.semantics { heading() }
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        page.text,
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // indicatorul de pagina (punctele), anuntat si de TalkBack
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp)
                    .semantics { contentDescription = "Pagina ${pagerState.currentPage + 1} din ${pages.size}" },
                horizontalArrangement = Arrangement.Center
            ) {
                pages.indices.forEach { i ->
                    val selected = i == pagerState.currentPage
                    val width by animateDpAsState(if (selected) 24.dp else 8.dp, label = "dot")
                    val color by animateColorAsState(
                        if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                        label = "dotColor"
                    )
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 4.dp)
                            .height(8.dp)
                            .width(width)
                            .clip(CircleShape)
                            .background(color)
                    )
                }
            }

            Button(
                onClick = {
                    if (last) onFinish()
                    else scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                },
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)
            ) {
                Text(if (last) "Începe" else "Mai departe")
            }
        }
    }
}
