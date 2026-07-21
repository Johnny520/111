package com.apkanalyzer

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.apkanalyzer.ui.MainViewModel
import com.apkanalyzer.ui.Screen
import com.apkanalyzer.ui.screens.AboutScreen
import com.apkanalyzer.ui.screens.AiAssistantScreen
import com.apkanalyzer.ui.screens.AnalyzerScreen
import com.apkanalyzer.ui.screens.McpScreen
import com.apkanalyzer.ui.screens.PollinationsScreen
import com.apkanalyzer.ui.screens.SettingsScreen
import com.apkanalyzer.ui.theme.APKAnalyzerTheme

class MainActivity : ComponentActivity() {

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.isNotEmpty() && results.values.all { !it }) {
            Toast.makeText(
                this,
                "需要存储权限才能读取文件，请在系统设置中开启",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestPermissions()

        setContent {
            val viewModel: MainViewModel = viewModel()
            APKAnalyzerTheme(darkTheme = viewModel.isDarkTheme) {
                MainApp(viewModel)
            }
        }
    }

    private fun requestPermissions() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.READ_MEDIA_IMAGES)
        } else {
            permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                // MANAGE_EXTERNAL_STORAGE requires special intent on Android 11+
            }
        }

        val notGranted = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (notGranted.isNotEmpty()) {
            requestPermissionLauncher.launch(notGranted.toTypedArray())
        }
    }
}

@Composable
fun MainApp(viewModel: MainViewModel = viewModel()) {
    val navController = rememberNavController()
    val items = listOf(
        Screen.Analyzer,
        Screen.Pollinations,
        Screen.AiAssistant,
        Screen.Mcp,
        Screen.Settings,
        Screen.About
    )

    Scaffold(
        bottomBar = {
            NavigationBar {
                val navBackStackEntry by navController.currentBackStackEntryAsState()
                val currentDestination = navBackStackEntry?.destination
                items.forEach { screen ->
                    NavigationBarItem(
                        icon = {
                            when (screen) {
                                Screen.Analyzer -> Icon(Icons.Default.Analytics, contentDescription = null)
                                Screen.AiAssistant -> Icon(Icons.Default.SmartToy, contentDescription = null)
                                Screen.Mcp -> Icon(Icons.Default.NetworkCheck, contentDescription = null)
                                Screen.Settings -> Icon(Icons.Default.Settings, contentDescription = null)
                                Screen.About -> Icon(Icons.Default.Info, contentDescription = null)
                                else -> Icon(Icons.Default.Image, contentDescription = null)
                            }
                        },
                        label = { Text(stringResource(screen.resourceId)) },
                        selected = currentDestination?.hierarchy?.any { it.route == screen.route } == true,
                        onClick = {
                            navController.navigate(screen.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    )
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Analyzer.route,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(Screen.Analyzer.route) { AnalyzerScreen(viewModel) }
            composable(Screen.AiAssistant.route) { AiAssistantScreen(viewModel) }
            composable(Screen.Pollinations.route) { PollinationsScreen(viewModel) }
            composable(Screen.Mcp.route) { McpScreen(viewModel) }
            composable(Screen.Settings.route) { SettingsScreen(viewModel) }
            composable(Screen.About.route) { AboutScreen() }
        }
    }
}
