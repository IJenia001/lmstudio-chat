package com.eal.lmstudiochat

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.eal.lmstudiochat.ui.ChatScreen
import com.eal.lmstudiochat.ui.ChatViewModel
import com.eal.lmstudiochat.ui.SettingsScreen
import com.eal.lmstudiochat.ui.SettingsViewModel
import com.eal.lmstudiochat.ui.theme.LmTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LmTheme {
                AppNav()
            }
        }
    }
}

@Composable
private fun AppNav() {
    val nav = rememberNavController()
    val app = androidx.compose.ui.platform.LocalContext.current.applicationContext as LmApplication
    NavHost(navController = nav, startDestination = "chat") {
        composable("chat") {
            val model: ChatViewModel = viewModel(factory = ChatViewModel.factory(app))
            ChatScreen(
                viewModel = model,
                onOpenSettings = { nav.navigate("settings") },
            )
        }
        composable("settings") {
            val model: SettingsViewModel = viewModel(factory = SettingsViewModel.factory(app))
            SettingsScreen(
                viewModel = model,
                onBack = { draft ->
                    model.save(draft)
                    nav.popBackStack()
                },
            )
        }
    }
}
