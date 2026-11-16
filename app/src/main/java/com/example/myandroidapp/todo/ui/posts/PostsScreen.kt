package com.example.myapplication.todo.ui.items

import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Publish
import androidx.compose.material.icons.filled.Search
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.CloudOff
import android.app.Application
import android.util.Log
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.*
import androidx.compose.material.AlertDialog
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.Modifier
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Person
import com.example.myandroidapp.auth.data.local.UserEntity
import com.example.myandroidapp.auth.data.local.AuthProvider
import com.example.myandroidapp.auth.data.local.isAdmin
import com.example.myandroidapp.auth.data.local.isGuest
import com.example.myandroidapp.auth.data.local.isRegularUser
import com.example.myandroidapp.todo.data.Post
import androidx.compose.ui.platform.LocalContext
import androidx.biometric.BiometricManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.myandroidapp.R
import com.example.myandroidapp.todo.data.Location
import com.example.myandroidapp.todo.ui.posts.PostList
import com.example.myandroidapp.todo.ui.posts.PostsViewModel
import com.example.myandroidapp.ui.theme.Purple80
import com.example.myapplication.network.MyNetworkStatusViewModel
import com.ilazar.myanimationsapp.MyFloatingActionButton
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PostsScreen(
    onItemClick: (id: String?) -> Unit,
    onAddItem: () -> Unit,
    onLogout: () -> Unit,
    onOpenPrivate: () -> Unit = {},
    // doar USER: postarile proprii
    onOpenMyPosts: () -> Unit = {},
    // doar ADMIN: moderare + gestionare utilizatori
    onOpenAdmin: () -> Unit = {},
    // doar USER: raportarile trimise, cu statusul si raspunsul adminului
    onOpenMyReports: () -> Unit = {},
    // doar autorul: editarea postarii proprii
    onEditPost: (id: String) -> Unit = {},
    // comutatorul de amprenta: vizibil doar pentru sesiunile salvate (login cu e-mail si parola)
    showBiometricToggle: Boolean = false,
    // sesiune creata fara server (login verificat in baza locala): trebuie reconectare pentru sincronizare
    offlineSession: Boolean = false,
    biometricEnabled: Boolean = false,
    onBiometricToggle: (enabled: Boolean) -> Unit = {}
) {
    Log.d("PostsScreen", "recompose")

    val itemsViewModel = viewModel<PostsViewModel>(factory = PostsViewModel.Factory)
    val myNetworkStatusViewModel = viewModel<MyNetworkStatusViewModel>(
        factory = MyNetworkStatusViewModel.Factory(LocalContext.current.applicationContext as Application)
    )

    var isEditing by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()
    val itemsUiState by itemsViewModel.uiState.collectAsStateWithLifecycle(initialValue = listOf())
    val currentUser by itemsViewModel.currentUser.collectAsStateWithLifecycle()
    val reportedByMe by itemsViewModel.reportedByMe.collectAsStateWithLifecycle()
    val unreadNotices by itemsViewModel.unreadNotices.collectAsStateWithLifecycle()
    var postToReport by remember { mutableStateOf<Post?>(null) }
    var postToDelete by remember { mutableStateOf<Post?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }

    // Confirmari / erori ale actiunilor pe rol (stergere, raportare)
    LaunchedEffect(itemsViewModel.message) {
        itemsViewModel.message?.let {
            snackbarHostState.showSnackbar(it)
            itemsViewModel.consumeMessage()
        }
    }

    LaunchedEffect(isEditing) {
        if (isEditing) {
            // scurta animatie a butonului, apoi formularul
            delay(400L)
            isEditing = false
            onAddItem()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(id = R.string.posts)) },
                actions = {
                    // starea retelei: iconita + text pentru TalkBack (detaliile sunt in banner)
                    Icon(
                        imageVector = if (myNetworkStatusViewModel.uiState) Icons.Default.Cloud else Icons.Default.CloudOff,
                        contentDescription = if (myNetworkStatusViewModel.uiState) "Online" else "Offline",
                        tint = if (myNetworkStatusViewModel.uiState)
                            androidx.compose.material3.MaterialTheme.colorScheme.primary
                        else androidx.compose.material3.MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 8.dp)
                    )
                    // Activare / dezactivare amprenta oricand, nu doar in dialogul de dupa primul login
                    if (showBiometricToggle) {
                        val context = LocalContext.current
                        IconToggleButton(
                            checked = biometricEnabled,
                            onCheckedChange = { enable ->
                                val available = BiometricManager.from(context)
                                    .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) ==
                                        BiometricManager.BIOMETRIC_SUCCESS
                                coroutineScope.launch {
                                    if (enable && !available) {
                                        snackbarHostState.showSnackbar(
                                            "Nu există nicio amprentă înregistrată pe telefon. Adaug-o din Setări."
                                        )
                                    } else {
                                        onBiometricToggle(enable)
                                        snackbarHostState.showSnackbar(
                                            if (enable) "Autentificarea cu amprenta a fost activată."
                                            else "Autentificarea cu amprenta a fost dezactivată."
                                        )
                                    }
                                }
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Default.Fingerprint,
                                contentDescription = if (biometricEnabled)
                                    "Amprentă activată. Atinge pentru a dezactiva"
                                else
                                    "Amprentă dezactivată. Atinge pentru a activa",
                                tint = if (biometricEnabled) androidx.compose.material3.MaterialTheme.colorScheme.primary
                                else androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    // Sectiunea privata (doar USER; cere din nou amprenta sau parola)
                    if (currentUser?.isRegularUser == true) {
                        IconButton(onClick = onOpenPrivate) {
                            Icon(Icons.Default.Lock, contentDescription = "Postări private")
                        }
                    }
                    IconButton(onClick = onLogout) {
                        Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = "Deconectare")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            // Doar USER publica: adminul modereaza, vizitatorul anonim doar citeste
            if (currentUser?.isRegularUser == true) {
                MyFloatingActionButton(
                    isEditing = isEditing,
                    onClick = {
                        coroutineScope.launch {
                            isEditing = true
                        }
                    }
                )
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .padding(paddingValues)
                .fillMaxSize()
        ) {
            // Banda cu rolul curent + functionalitatea unica a rolului
            RoleBar(
                user = currentUser,
                onOpenMyPosts = onOpenMyPosts,
                onOpenMyReports = onOpenMyReports,
                onOpenAdmin = onOpenAdmin
            )
            // Starea retelei: offline (datele salvate), postari in coada, sesiune fara server
            val pendingCount by itemsViewModel.pendingCount.collectAsStateWithLifecycle()
            ConnectivityBanner(
                isOnline = myNetworkStatusViewModel.uiState,
                pendingCount = pendingCount,
                offlineSession = offlineSession,
                onReconnect = onLogout
            )
            // Cand revine reteaua, lista se reincarca singura de pe server
            var wasOffline by remember { mutableStateOf(false) }
            LaunchedEffect(myNetworkStatusViewModel.uiState) {
                if (!myNetworkStatusViewModel.uiState) wasOffline = true
                else if (wasOffline) {
                    wasOffline = false
                    itemsViewModel.loadItems()
                }
            }
            if (itemsViewModel.isRefreshing) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            // Serverul pornit e o versiune veche: titlul, rating-ul, ciornele si locul s-ar pierde
            if (itemsViewModel.serverOutdated) {
                ServerOutdatedBanner()
            }
            // Cautare dupa destinatie + filtre (Toate / Ale mele / Ciornele mele / rating minim)
            val filters by itemsViewModel.filters.collectAsStateWithLifecycle()
            val myDraftCount by itemsViewModel.myDraftCount.collectAsStateWithLifecycle()
            SearchAndFilters(
                filters = filters,
                showMineFilter = currentUser?.isRegularUser == true,
                onQueryChange = itemsViewModel::onQueryChange,
                onMineOnlyChange = itemsViewModel::onMineOnlyChange,
                onMinRatingChange = itemsViewModel::onMinRatingChange,
                onSortByRatingChange = itemsViewModel::onSortByRatingChange,
                draftCount = myDraftCount,
                onDraftsOnlyChange = itemsViewModel::onDraftsOnlyChange
            )
            val noFilters = filters == PostsViewModel.Filters()
            PostList(
                posts = itemsUiState,
                isOwn = { it.user_id.isNotEmpty() && it.user_id == currentUser?.ownerKey },
                emptyText = when {
                    filters.draftsOnly -> "Nu ai ciorne. Când salvezi o postare ca ciornă, o găsești aici."
                    noFilters -> "Încă nu există postări. Fii primul care adaugă una!"
                    else -> "Nicio postare nu se potrivește căutării."
                },
                onPostClick = { postId ->
                    val post = itemsUiState.find { it.id == postId }
                    // "Sincronizare esuata – atinge pentru a corecta": deschide editarea
                    if (post != null && post.syncStatus == com.example.myandroidapp.todo.data.SyncStatus.FAILED &&
                        itemsViewModel.canEdit(post, currentUser)
                    ) {
                        onEditPost(post.id)
                        return@PostList
                    }
                    // detaliile postarii: poza mare, impresiile complete, harta locului
                    if (post != null) onItemClick(post.id)
                },
                modifier = Modifier.fillMaxSize(),
                actions = { post ->
                    PostRoleActions(
                        canDelete = itemsViewModel.canDelete(post, currentUser),
                        canReport = itemsViewModel.canReport(post, currentUser),
                        alreadyReported = post.id in reportedByMe,
                        onDelete = { postToDelete = post },
                        onReport = { postToReport = post },
                        canEdit = itemsViewModel.canEdit(post, currentUser),
                        onEdit = { onEditPost(post.id) },
                        canPublish = post.status == com.example.myandroidapp.todo.data.PostStatus.DRAFT &&
                                itemsViewModel.canEdit(post, currentUser),
                        onPublish = { itemsViewModel.publishDraft(post) }
                    )
                }
            )
        }
    }

    postToDelete?.let { post ->
        AlertDialog(
            onDismissRequest = { postToDelete = null },
            title = { Text("Ștergeți postarea?") },
            text = {
                Text(
                    if (currentUser?.isAdmin == true && post.user_id != currentUser?.ownerKey)
                        "Postarea va fi eliminată pentru toți utilizatorii (moderare)."
                    else "Postarea va fi ștearsă definitiv."
                )
            },
            confirmButton = {
                Button(onClick = {
                    itemsViewModel.deletePost(post)
                    postToDelete = null
                }) { Text("Șterge") }
            },
            dismissButton = {
                TextButton(onClick = { postToDelete = null }) { Text("Anulează") }
            }
        )
    }

    // Postari ale utilizatorului sterse de un administrator -> mesajul adminului
    if (unreadNotices.isNotEmpty()) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = {},
            icon = { Icon(Icons.Default.Flag, contentDescription = null) },
            title = {
                Text(
                    if (unreadNotices.size == 1) "Un administrator ți-a șters o postare"
                    else "Un administrator ți-a șters ${unreadNotices.size} postări"
                )
            },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    unreadNotices.forEach { notice ->
                        Column {
                            Text(
                                "„${notice.postSnippet.ifBlank { "(postare fără text)" }}”",
                                fontWeight = FontWeight.SemiBold
                            )
                            if (notice.reasons.isNotBlank()) {
                                Text("Motivul raportării: ${notice.reasons}")
                            }
                            Text("Mesajul administratorului: ${notice.message}")
                            Text(
                                java.text.DateFormat.getDateTimeInstance(
                                    java.text.DateFormat.SHORT, java.text.DateFormat.SHORT
                                ).format(java.util.Date(notice.createdAt)),
                                style = androidx.compose.material3.MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = { itemsViewModel.acknowledgeNotices() }) { Text("Am înțeles") }
            }
        )
    }

    postToReport?.let { post ->
        ReportPostDialog(
            onDismiss = { postToReport = null },
            onConfirm = { reason ->
                itemsViewModel.reportPost(post, reason)
                postToReport = null
            }
        )
    }

    EditMessage(shown = isEditing)
}

@Composable
private fun EditMessage(shown: Boolean) {
    AnimatedVisibility(
        visible = shown,
        enter = slideInVertically(
            initialOffsetY = { fullHeight -> -fullHeight },
            animationSpec = tween(durationMillis = 150, easing = LinearOutSlowInEasing)
        ),
        exit = slideOutVertically(
            targetOffsetY = { fullHeight -> -fullHeight },
            animationSpec = tween(durationMillis = 250, easing = FastOutLinearInEasing)
        )
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colors.secondary,
            elevation = 4.dp
        ) {
            Text("Postare nouă…", modifier = Modifier.padding(16.dp))
        }
    }
}


@Preview
@Composable
fun PreviewPostsScreen() {
    PostsScreen(onItemClick = {}, onAddItem = {}, onLogout = {})
}


/** Bara de cautare si filtrele de deasupra feed-ului. */
@Composable
private fun SearchAndFilters(
    filters: PostsViewModel.Filters,
    showMineFilter: Boolean,
    onQueryChange: (String) -> Unit,
    onMineOnlyChange: (Boolean) -> Unit,
    onMinRatingChange: (Int) -> Unit,
    onSortByRatingChange: (Boolean) -> Unit,
    draftCount: Int = 0,
    onDraftsOnlyChange: (Boolean) -> Unit = {}
) {
    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
        androidx.compose.material3.OutlinedTextField(
            value = filters.query,
            onValueChange = onQueryChange,
            placeholder = { androidx.compose.material3.Text("Caută o destinație…") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = {
                if (filters.query.isNotEmpty()) {
                    IconButton(onClick = { onQueryChange("") }) {
                        Icon(Icons.Default.Clear, contentDescription = "Șterge căutarea")
                    }
                }
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            androidx.compose.material3.FilterChip(
                selected = !filters.mineOnly && !filters.draftsOnly,
                onClick = { onMineOnlyChange(false); onDraftsOnlyChange(false) },
                label = { androidx.compose.material3.Text("Toate") }
            )
            if (showMineFilter) {
                androidx.compose.material3.FilterChip(
                    selected = filters.mineOnly && !filters.draftsOnly,
                    onClick = { onMineOnlyChange(true); onDraftsOnlyChange(false) },
                    label = { androidx.compose.material3.Text("Ale mele") }
                )
                // ciornele proprii, cu numarul lor, ca sa stii ce mai ai de publicat
                androidx.compose.material3.FilterChip(
                    selected = filters.draftsOnly,
                    onClick = { onDraftsOnlyChange(!filters.draftsOnly) },
                    leadingIcon = { Icon(Icons.Default.EditNote, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    label = { androidx.compose.material3.Text("Ciornele mele ($draftCount)") }
                )
            }
            androidx.compose.material3.FilterChip(
                selected = filters.sortByRating,
                onClick = { onSortByRatingChange(!filters.sortByRating) },
                label = {
                    androidx.compose.material3.Text(if (filters.sortByRating) "Sortat: rating ↓" else "Sortat: cele mai noi")
                }
            )
            (0..5).forEach { stars ->
                androidx.compose.material3.FilterChip(
                    selected = filters.minRating == stars,
                    onClick = { onMinRatingChange(stars) },
                    label = { androidx.compose.material3.Text(if (stars == 0) "Orice rating" else "$stars★+") },
                    modifier = Modifier.semantics {
                        contentDescription = if (stars == 0) "Orice rating" else "Cel puțin $stars stele"
                    }
                )
            }
        }
    }
}

/** Avertisment: aplicatia vorbeste cu un server vechi (care nu stie de titlu, rating, ciorne, loc). */
@Composable
private fun ServerOutdatedBanner() {
    androidx.compose.material3.Surface(
        color = androidx.compose.material3.MaterialTheme.colorScheme.errorContainer,
        contentColor = androidx.compose.material3.MaterialTheme.colorScheme.onErrorContainer,
        modifier = Modifier
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Polite }
    ) {
        Text(
            "Serverul pornit este o versiune veche: titlul, rating-ul, ciornele și locul nu se salvează pe server. " +
                    "Oprește-l și pornește-l din folderul server/ al proiectului (npm start).",
            style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
        )
    }
}

/**
 * Bannerul de conectivitate de deasupra listei:
 *  - offline: se vad postarile salvate pe telefon (cache), iar cele noi asteapta in coada;
 *  - online, cu postari in coada: se trimit acum;
 *  - sesiune creata fara server (login offline): trebuie reconectare ca sa se sincronizeze.
 * Starea e spusa in text, nu doar prin culoare / iconita.
 */
@Composable
private fun ConnectivityBanner(
    isOnline: Boolean,
    pendingCount: Int,
    offlineSession: Boolean,
    onReconnect: () -> Unit
) {
    val pending = when (pendingCount) {
        0 -> ""
        1 -> " O postare așteaptă să fie trimisă."
        else -> " $pendingCount postări așteaptă să fie trimise."
    }
    val (icon, text) = when {
        !isOnline -> Icons.Default.CloudOff to
                "Ești offline. Vezi postările salvate pe telefon; cele noi se trimit când revine conexiunea.$pending"
        offlineSession -> Icons.Default.CloudOff to
                "Ești conectat fără server (login offline). Conectează-te din nou ca să sincronizezi.$pending"
        pendingCount > 0 -> Icons.Default.CloudUpload to "Se trimit postările salvate offline…$pending"
        else -> return
    }
    androidx.compose.material3.Surface(
        color = androidx.compose.material3.MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = androidx.compose.material3.MaterialTheme.colorScheme.onTertiaryContainer,
        modifier = Modifier
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Polite }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.width(12.dp))
            androidx.compose.material3.Text(
                text = text,
                style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f)
            )
            if (isOnline && offlineSession) {
                TextButton(onClick = onReconnect) { Text("Reconectare") }
            }
        }
    }
}

/**
 * Banda de sub bara de sus: rolul curent si butoanele catre functionalitatile unice ale rolului.
 *  - ADMIN: Administrare
 *  - USER: Postarile mele, Raportarile mele
 */
@Composable
fun RoleBar(
    user: UserEntity?,
    onOpenMyPosts: () -> Unit,
    onOpenAdmin: () -> Unit,
    onOpenMyReports: () -> Unit = {}
) {
    // Culori din tema (Material 3): contrast corect atat in tema luminoasa, cat si in cea intunecata
    androidx.compose.material3.Surface(
        color = if (user?.isAdmin == true) androidx.compose.material3.MaterialTheme.colorScheme.primaryContainer
        else androidx.compose.material3.MaterialTheme.colorScheme.secondaryContainer,
        contentColor = if (user?.isAdmin == true) androidx.compose.material3.MaterialTheme.colorScheme.onPrimaryContainer
        else androidx.compose.material3.MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = when {
                        user?.isAdmin == true -> Icons.Default.AdminPanelSettings
                        user?.isGuest == true -> Icons.Default.Visibility
                        else -> Icons.Default.Person
                    },
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = when {
                        user == null -> "Se încarcă contul…"
                        user.isAdmin -> "Administrator · ${user.email}"
                        user.isGuest -> "Vizitator anonim · poți doar citi postările"
                        else -> "Utilizator · ${user.email} · ${AuthProvider.label(user.provider)}"
                    },
                    style = MaterialTheme.typography.body2,
                    modifier = Modifier.weight(1f)
                )
                if (user != null && user.isAdmin) {
                    TextButton(onClick = onOpenAdmin) { Text("Administrare") }
                }
            }
            if (user != null && user.isRegularUser) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onOpenMyPosts) { Text("Postările mele") }
                    TextButton(onClick = onOpenMyReports) { Text("Raportările mele") }
                }
            }
        }
    }
}

/**
 * Butoanele de sub o postare, in functie de rol:
 *  - ADMIN: Sterge (orice postare)
 *  - USER: Editeaza + Sterge (doar postarile proprii) si Raporteaza (postarile altora)
 */
@Composable
fun PostRoleActions(
    canDelete: Boolean,
    canReport: Boolean,
    alreadyReported: Boolean,
    onDelete: () -> Unit,
    onReport: () -> Unit,
    canEdit: Boolean = false,
    onEdit: () -> Unit = {},
    // ciorna proprie: "Publică" direct din lista, fara editare
    canPublish: Boolean = false,
    onPublish: () -> Unit = {}
) {
    if (!canDelete && !canReport && !canEdit && !canPublish) return
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End
    ) {
        if (canReport) {
            TextButton(onClick = onReport, enabled = !alreadyReported) {
                Icon(Icons.Default.Flag, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text(if (alreadyReported) "Raportat" else "Raportează")
            }
        }
        if (canPublish) {
            FilledTonalButton(onClick = onPublish) {
                Icon(Icons.Default.Publish, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Publică")
            }
            Spacer(modifier = Modifier.width(4.dp))
        }
        if (canEdit) {
            TextButton(onClick = onEdit) {
                Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Editează")
            }
        }
        if (canDelete) {
            TextButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Șterge")
            }
        }
    }
}

private val REPORT_REASONS = listOf(
    "Conținut nepotrivit",
    "Spam sau reclamă",
    "Informații false",
    "Încalcă drepturile cuiva"
)

@Composable
fun ReportPostDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var selected by remember { mutableStateOf(REPORT_REASONS.first()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Raportează postarea") },
        text = {
            Column {
                Text("De ce raportați această postare? Un administrator o va verifica, iar răspunsul lui apare la „Raportările mele”.")
                Spacer(modifier = Modifier.height(8.dp))
                REPORT_REASONS.forEach { reason ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(selected = selected == reason, onClick = { selected = reason })
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = selected == reason, onClick = { selected = reason })
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(reason)
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(selected) }) { Text("Trimite") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Anulează") }
        }
    )
}
