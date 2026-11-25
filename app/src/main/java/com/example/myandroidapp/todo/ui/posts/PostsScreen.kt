package com.example.myapplication.todo.ui.items

import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Map
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
import com.example.myandroidapp.core.logging.SafeLog
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
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Luggage
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Sensors
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
    // doar USER: trackerele IoT asociate
    onOpenDevices: () -> Unit = {},
    onOpenTrips: () -> Unit = {},
    // harta cu toate postarile si profilul (statistici, parola, deconectare)
    onOpenMap: () -> Unit = {},
    onOpenProfile: () -> Unit = {},
    // profilul public al unui calator (atingerea numelui autorului)
    onOpenUser: (userId: String) -> Unit = {},
    // doar autorul: editarea postarii proprii
    onEditPost: (id: String) -> Unit = {},
    // comutatorul de amprenta: vizibil doar pentru sesiunile salvate (login cu e-mail si parola)
    showBiometricToggle: Boolean = false,
    // sesiune creata fara server (login verificat in baza locala): trebuie reconectare pentru sincronizare
    offlineSession: Boolean = false,
    biometricEnabled: Boolean = false,
    onBiometricToggle: (enabled: Boolean) -> Unit = {}
) {
    SafeLog.d("PostsScreen", "recompose")

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

    // Notificarile (postari noi ale altora, sincronizare reusita): pe Android 13+ trebuie permisiune.
    // O cerem o singura data, cand utilizatorul ajunge in lista de postari.
    val notificationPermission = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { }
    val context = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(Unit) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
            androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }

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
                    IconButton(onClick = onOpenMap) {
                        Icon(Icons.Default.Map, contentDescription = "Harta postărilor")
                    }
                    // profilul: statistici, schimbarea parolei si deconectarea
                    IconButton(onClick = onOpenProfile) {
                        Icon(Icons.Default.AccountCircle, contentDescription = "Profil și deconectare")
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
                onOpenDevices = onOpenDevices,
                onOpenTrips = onOpenTrips,
                onOpenMyReports = onOpenMyReports,
                onOpenAdmin = onOpenAdmin
            )
            // Starea retelei: offline (datele salvate), postari in coada, sesiune fara server
            val pendingCount by itemsViewModel.pendingCount.collectAsStateWithLifecycle()
            ConnectivityBanner(
                isOnline = myNetworkStatusViewModel.uiState,
                pendingCount = pendingCount,
                offlineSession = offlineSession,
                onReconnect = onLogout,
                syncing = itemsViewModel.syncing,
                onSyncNow = itemsViewModel::syncNow
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
            // indicatorul de actualizare e cel al PullToRefreshBox (si la actualizarea automata)
            // Serverul pornit e o versiune veche: titlul, rating-ul, ciornele si locul s-ar pierde
            if (itemsViewModel.serverOutdated) {
                ServerOutdatedBanner()
            }
            // Cautare dupa destinatie + filtre (Toate / Ale mele / Ciornele mele / rating minim)
            val filters by itemsViewModel.filters.collectAsStateWithLifecycle()
            val myDraftCount by itemsViewModel.myDraftCount.collectAsStateWithLifecycle()
            val savedIds by itemsViewModel.saved.collectAsStateWithLifecycle()
            SearchAndFilters(
                filters = filters,
                showMineFilter = currentUser?.isRegularUser == true,
                onQueryChange = itemsViewModel::onQueryChange,
                onMineOnlyChange = itemsViewModel::onMineOnlyChange,
                onMinRatingChange = itemsViewModel::onMinRatingChange,
                onSortByRatingChange = itemsViewModel::onSortByRatingChange,
                draftCount = myDraftCount,
                onDraftsOnlyChange = itemsViewModel::onDraftsOnlyChange,
                onFollowingOnlyChange = itemsViewModel::onFollowingOnlyChange,
                onSavedOnlyChange = itemsViewModel::onSavedOnlyChange
            )
            val noFilters = filters == PostsViewModel.Filters()
            // trage lista in jos ca sa o actualizezi de pe server (offline: raman postarile salvate)
            androidx.compose.material3.pulltorefresh.PullToRefreshBox(
                isRefreshing = itemsViewModel.isRefreshing,
                onRefresh = itemsViewModel::loadItems,
                modifier = Modifier.fillMaxSize()
            ) {
                PostList(
                    posts = itemsUiState,
                    isOwn = { it.user_id.isNotEmpty() && it.user_id == currentUser?.ownerKey },
                    // doar conturile USER apreciaza (nu adminul, nu vizitatorul anonim)
                    onToggleLike = if (currentUser?.isRegularUser == true) itemsViewModel::toggleLike else null,
                    onAuthorClick = { post -> onOpenUser(post.user_id) },
                    savedIds = savedIds,
                    onToggleSave = if (currentUser?.isRegularUser == true) itemsViewModel::toggleSaved else null,
                    emptyText = when {
                        filters.draftsOnly -> "Nu ai ciorne. Când salvezi o postare ca ciornă, o găsești aici."
                        filters.followingOnly -> "Nu urmărești încă pe nimeni (sau n-au postat). Atinge numele unui autor ca să-i vezi profilul."
                        filters.savedOnly -> "Nicio postare salvată. Atinge semnul de carte de pe o postare ca s-o păstrezi ca idee de călătorie."
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
                            onPublish = { itemsViewModel.publishDraft(post) },
                            canModerate = itemsViewModel.canModerate(post, currentUser),
                            hidden = post.hidden,
                            onToggleHidden = { itemsViewModel.toggleHidden(post) }
                        )
                    }
                )
            }
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
    onDraftsOnlyChange: (Boolean) -> Unit = {},
    onFollowingOnlyChange: (Boolean) -> Unit = {},
    onSavedOnlyChange: (Boolean) -> Unit = {}
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
                // calatorii urmariti / ideile salvate (merg si offline)
                androidx.compose.material3.FilterChip(
                    selected = filters.followingOnly,
                    onClick = { onFollowingOnlyChange(!filters.followingOnly) },
                    leadingIcon = { Icon(Icons.Default.People, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    label = { androidx.compose.material3.Text("Urmăriți") }
                )
                androidx.compose.material3.FilterChip(
                    selected = filters.savedOnly,
                    onClick = { onSavedOnlyChange(!filters.savedOnly) },
                    leadingIcon = { Icon(Icons.Default.Bookmark, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    label = { androidx.compose.material3.Text("Salvate") }
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
            "Serverul pornit este o versiune veche: pozele, aprecierile, comentariile sau ciornele nu funcționează. " +
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
    onReconnect: () -> Unit,
    syncing: Boolean = false,
    onSyncNow: () -> Unit = {}
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
            } else if (isOnline && pendingCount > 0) {
                TextButton(onClick = onSyncNow, enabled = !syncing) { Text(if (syncing) "Se trimite…" else "Trimite acum") }
            }
        }
    }
}

/**
 * Scurtaturile de sub bara de sus, in functie de rol:
 *  - USER: chip-uri cu iconita (Călătorii, Postările mele, Raportările mele, Trackere), derulabile;
 *  - ADMIN: o eticheta discreta + "Administrare";
 *  - Vizitator: o linie scurta ca poate doar citi.
 * E-mailul contului nu mai e afisat aici (e in ecranul Profil).
 */
@Composable
fun RoleBar(
    user: UserEntity?,
    onOpenMyPosts: () -> Unit,
    onOpenAdmin: () -> Unit,
    onOpenMyReports: () -> Unit = {},
    onOpenDevices: () -> Unit = {},
    onOpenTrips: () -> Unit = {}
) {
    val colors = androidx.compose.material3.MaterialTheme.colorScheme
    when {
        user == null -> Unit
        user.isAdmin -> androidx.compose.material3.Surface(
            color = colors.primaryContainer,
            contentColor = colors.onPrimaryContainer,
            shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
        ) {
            Row(modifier = Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.AdminPanelSettings, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                androidx.compose.material3.Text(
                    "Mod administrator",
                    style = androidx.compose.material3.MaterialTheme.typography.labelLarge,
                    modifier = Modifier.weight(1f)
                )
                androidx.compose.material3.TextButton(onClick = onOpenAdmin) { androidx.compose.material3.Text("Administrare") }
            }
        }
        user.isGuest -> Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Visibility, contentDescription = null, modifier = Modifier.size(16.dp), tint = colors.onSurfaceVariant)
            Spacer(Modifier.width(6.dp))
            androidx.compose.material3.Text(
                "Vizitator · poți doar citi postările",
                style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
                color = colors.onSurfaceVariant
            )
        }
        user.isRegularUser -> androidx.compose.foundation.lazy.LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
        ) {
            item { ShortcutChip("Călătorii", Icons.Default.Luggage, onOpenTrips) }
            item { ShortcutChip("Postările mele", Icons.Default.PhotoLibrary, onOpenMyPosts) }
            item { ShortcutChip("Raportări", Icons.Default.Flag, onOpenMyReports) }
            item { ShortcutChip("Trackere", Icons.Default.Sensors, onOpenDevices) }
        }
    }
}

@Composable
private fun ShortcutChip(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    androidx.compose.material3.AssistChip(
        onClick = onClick,
        label = { androidx.compose.material3.Text(label) },
        leadingIcon = { Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp)) },
        shape = androidx.compose.foundation.shape.RoundedCornerShape(50)
    )
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
    onPublish: () -> Unit = {},
    // ADMIN: ascunde / reafiseaza postarea altcuiva (varianta blanda a stergerii)
    canModerate: Boolean = false,
    hidden: Boolean = false,
    onToggleHidden: () -> Unit = {}
) {
    if (!canDelete && !canReport && !canEdit && !canPublish && !canModerate) return
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
        if (canModerate) {
            TextButton(onClick = onToggleHidden) {
                Icon(
                    if (hidden) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(if (hidden) "Reafișează" else "Ascunde")
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
