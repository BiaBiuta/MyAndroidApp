package com.example.myandroidapp.todo.ui.myreports

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.myandroidapp.MyApplication
import com.example.myandroidapp.auth.data.RoleRepository
import com.example.myandroidapp.todo.data.PostRepository
import com.example.myandroidapp.todo.data.local.ReportEntity
import com.example.myandroidapp.todo.data.local.ReportStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import java.text.DateFormat
import java.util.Date

/**
 * "Raportarile mele" (doar USER): raportarile trimise de utilizator, cu statusul
 * (in asteptare / rezolvat / respins) si mesajul adminului. Se actualizeaza live din Room.
 */
class MyReportsViewModel(
    postRepository: PostRepository,
    roleRepository: RoleRepository
) : ViewModel() {
    @OptIn(ExperimentalCoroutinesApi::class)
    val reports: Flow<List<ReportEntity>> = roleRepository.currentUser.flatMapLatest { user ->
        if (user == null) flowOf(emptyList()) else postRepository.reportsOf(user.email)
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app =
                    (this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as MyApplication)
                MyReportsViewModel(app.container.postRepository, app.container.roleRepository)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MyReportsScreen(onBack: () -> Unit) {
    val viewModel = viewModel<MyReportsViewModel>(factory = MyReportsViewModel.Factory)
    val reports by viewModel.reports.collectAsStateWithLifecycle(initialValue = emptyList())

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Raportările mele") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Înapoi")
                    }
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            if (reports.isEmpty()) {
                Text(
                    "Nu ai trimis nicio raportare.\nPoți raporta o postare cu butonul „Raportează” din listă.",
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(24.dp)
                )
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
                    items(reports, key = { it.postId }) { report -> ReportCard(report) }
                }
            }
        }
    }
}

@Composable
private fun ReportCard(report: ReportEntity) {
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = report.postSnippet.ifBlank { "(postare fără descriere)" },
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                StatusLabel(report.status)
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "Motiv: ${report.reason} · trimisă ${formatDate(report.createdAt)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (report.status == ReportStatus.PENDING) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Un administrator nu a răspuns încă.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Spacer(Modifier.height(8.dp))
                Text(
                    if (report.status == ReportStatus.RESOLVED) "Postarea a fost eliminată."
                    else "Postarea a fost verificată și rămâne publicată.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(8.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                        .padding(12.dp)
                ) {
                    Text(
                        "Răspunsul administratorului" +
                            if (report.resolvedAt > 0) " · ${formatDate(report.resolvedAt)}" else "",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(report.adminReply.ifBlank { "(fără mesaj)" }, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun StatusLabel(status: String) {
    val (text, color) = when (status) {
        ReportStatus.RESOLVED -> "Rezolvat" to Color(0xFF2E7D32)
        ReportStatus.REJECTED -> "Respins" to Color(0xFFC62828)
        else -> "În așteptare" to Color(0xFF6D6D6D)
    }
    Text(
        text = text,
        color = Color.White,
        style = MaterialTheme.typography.labelMedium,
        modifier = Modifier
            .background(color, RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp)
    )
}

private fun formatDate(millis: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(millis))
