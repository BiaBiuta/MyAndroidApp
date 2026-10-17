package com.example.myandroidapp

import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.compose.ui.platform.LocalContext
import com.example.myandroidapp.auth.data.local.isAdmin
import com.example.myandroidapp.todo.ui.admin.AdminScreen
import com.example.myandroidapp.todo.ui.myposts.MyPostsScreen
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.myandroidapp.auth.LoginScreen
import com.example.myandroidapp.core.data.remote.Api
import com.example.myandroidapp.todo.ui.post.PostAddScreen
import com.example.myandroidapp.todo.ui. privateposts.PrivatePostsScreen
import com.example.myapplication.todo.ui.items.PostsScreen
// ATENTIE: SessionState e cel din MyAppViewModel.kt (acelasi pachet), NU importati
// com.google.android.gms.cast.SessionState daca Android Studio il propune.


val postsRoute = "posts"
val authRoute = "auth"
val privateRoute = "private"
val myPostsRoute = "my-posts"
val adminRoute = "admin"

@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun MyAppNavHost() {
    val navController = rememberNavController()
    val onClose = {
        Log.d("MyAppNavHost", "navigation back to list")
        navController.popBackStack()
    }

    // myAppViewModel gestioneaza sesiunea: blocare/deblocare cu amprenta, logout, token
    val myAppViewModel = viewModel<MyAppViewModel>(factory = MyAppViewModel.Factory)
    val sessionState by myAppViewModel.sessionState.collectAsStateWithLifecycle()
    // Contul logat (din baza de date locala) -> rolul decide ce ecrane sunt disponibile
    val currentUser by myAppViewModel.currentUser.collectAsStateWithLifecycle()

    // Urmarim cand aplicatia trece in fundal / revine (ex. butonul Home)
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> myAppViewModel.onAppBackground()
                Lifecycle.Event.ON_START -> myAppViewModel.onAppForeground()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    NavHost(
        navController = navController,
        startDestination = authRoute
    ) {
        composable(postsRoute) {
            PostsScreen(
                onItemClick = { itemId ->
                    Log.d("MyAppNavHost", "navigate to item $itemId")
                    navController.navigate("$postsRoute/$itemId")
                },
                onAddItem = {
                    Log.d("MyAppNavHost", "navigate to new item")
                    navController.navigate("$postsRoute-new")
                },
                onLogout = {
                    Log.d("MyAppNavHost", "logout")
                    // sterge complet sesiunea + token-ul, apoi duce utilizatorul la login
                    myAppViewModel.logout {
                        navController.navigate(authRoute) {
                            popUpTo(0)
                        }
                    }
                },
                onOpenPrivate = {
                    Log.d("MyAppNavHost", "navigate to private section")
                    navController.navigate(privateRoute)
                },
                onOpenMyPosts = { navController.navigate(myPostsRoute) },
                onOpenAdmin = { navController.navigate(adminRoute) })
        }
        // Doar rolul USER
        composable(route = myPostsRoute) {
            MyPostsScreen(onBack = { navController.popBackStack() })
        }
        // Doar rolul ADMIN (actiunile sunt verificate si in RoleRepository)
        composable(route = adminRoute) {
            if (currentUser?.isAdmin == true) {
                AdminScreen(onBack = { navController.popBackStack() })
            } else {
                LaunchedEffect(Unit) { navController.popBackStack() }
            }
        }
        composable(route = "$postsRoute-new")
        {
            PostAddScreen(
                onClose = { onClose() }
            )
        }
        // Adaugare din sectiunea privata: comutatorul "Postare privată" e pornit implicit
        composable(route = "$postsRoute-new-private")
        {
            PostAddScreen(
                onClose = { onClose() },
                isPrivateInitially = true
            )
        }
        composable(route = privateRoute)
        {
            PrivatePostsScreen(
                onBack = { navController.popBackStack() },
                onAddPrivatePost = { navController.navigate("$postsRoute-new-private") }
            )
        }
        composable(route = authRoute)
        {
            LoginScreen(
                sessionLocked = sessionState is SessionState.Locked,
                onSessionUnlocked = { myAppViewModel.unlock() },
                onEnterWithoutSession = {
                    Log.d("MyAppNavHost", "enter without saved session")
                    navController.navigate(postsRoute) {
                        popUpTo(authRoute) { inclusive = true }
                    }
                }
            )
        }
    }

    LaunchedEffect(sessionState) {
        when (val state = sessionState) {
            // Sesiune activa (login cu parola, amprenta acceptata, sau sesiune salvata
            // fara amprenta) -> setam token-ul si intram in ecranul principal
            is SessionState.Active -> {
                Log.d("MyAppNavHost", "session active, navigate to items")
                Api.tokenInterceptor.token = state.token
                myAppViewModel.setToken(state.token)
                if (navController.currentDestination?.route == authRoute) {
                    navController.navigate(postsRoute) {
                        popUpTo(0)
                    }
                }
            }
            // Sesiunea s-a blocat (revenire din fundal) -> inapoi la login,
            // unde se ridica automat sheet-ul de amprenta
            is SessionState.Locked -> {
                if (navController.currentDestination?.route != authRoute) {
                    navController.navigate(authRoute) {
                        popUpTo(0)
                    }
                }
            }
            else -> {}
        }
    }

    // Cont blocat de un admin -> mesaj + inapoi la login
    val notice by myAppViewModel.notice.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(notice) {
        notice?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            myAppViewModel.consumeNotice()
            if (navController.currentDestination?.route != authRoute) {
                navController.navigate(authRoute) { popUpTo(0) }
            }
        }
    }
}
