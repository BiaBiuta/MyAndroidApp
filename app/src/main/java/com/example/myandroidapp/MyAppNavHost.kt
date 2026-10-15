package com.example.myandroidapp

import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.myandroidapp.auth.LoginScreen
import com.example.myandroidapp.core.data.remote.Api
import com.example.myandroidapp.todo.ui.post.PostAddScreen
import com.example.myapplication.todo.ui.items.PostsScreen



val postsRoute = "posts"
val authRoute = "auth"

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
                })
        }
        composable(route = "$postsRoute-new")
        {
            PostAddScreen(
                onClose = { onClose() }
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

    // Cand sesiunea e activa (login cu parola, amprenta acceptata, sau sesiune salvata
    // fara amprenta) setam token-ul si intram in ecranul principal.
    LaunchedEffect(sessionState) {
        val state = sessionState
        if (state is SessionState.Active) {
            Log.d("MyAppNavHost", "session active, navigate to items")
            Api.tokenInterceptor.token = state.token
            myAppViewModel.setToken(state.token)
            if (navController.currentDestination?.route == authRoute) {
                navController.navigate(postsRoute) {
                    popUpTo(0)
                }
            }
        }
    }
}
