package com.example.myandroidapp

import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.myandroidapp.auth.LoginScreen
import com.example.myandroidapp.core.data.UserPreferences
import com.example.myandroidapp.core.data.remote.Api
import com.example.myandroidapp.core.ui.UserPreferencesViewModel
import com.example.myandroidapp.todo.ui.post.PostAddScreen
import com.example.myapplication.todo.ui.items.PostsScreen


val postsRoute="posts"
val authRoute="auth"
@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun MyAppNavHost() {
    val navController= rememberNavController()
    val onClose={
        Log.d("MyAppNavHost","navigation back to list")
        navController.popBackStack()
    }
    val userPreferencesViewModel =
        viewModel<UserPreferencesViewModel>(factory = UserPreferencesViewModel.Factory)
    val userPreferencesUiState by userPreferencesViewModel.uiState.collectAsStateWithLifecycle(
        initialValue = UserPreferences()
    )

    //myAppViewModel va gestiona logica principala a aplicatiei
    //-logoutul si setarea tokenlui
    val myAppViewModel = viewModel<MyAppViewModel>(factory = MyAppViewModel.Factory)
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
                    myAppViewModel.logout()
                    Api.tokenInterceptor.token = null
                    navController.navigate(authRoute) {
                        popUpTo(0)
                    }
                })
        }
//        composable(
//            route = "$postsRoute/{id}",
//            arguments = listOf(navArgument("id") { type = NavType.StringType })
//        )
//        {
//            PostAddScreen (
//
//            )
//        }
        composable(route = "$postsRoute-new")
        {
            PostAddScreen(
                onClose = { onClose() }
            )
        }
        composable(route = authRoute)
        {
            LoginScreen(
                onClose = {
                    Log.d("MyAppNavHost", "navigate to list")
                    navController.navigate(postsRoute)
                }
            )
        }
    }
    LaunchedEffect(userPreferencesUiState.token) {
        if (userPreferencesUiState.token.isNotEmpty()) {
            Log.d("MyAppNavHost", "Lauched effect navigate to items")
            Api.tokenInterceptor.token = userPreferencesUiState.token
            myAppViewModel.setToken(userPreferencesUiState.token)
            navController.navigate(postsRoute) {
                popUpTo(0)
            }
        }
    }
}