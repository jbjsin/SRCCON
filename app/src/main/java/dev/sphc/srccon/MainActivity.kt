package dev.sphc.srccon

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.sphc.srccon.ui.SrcconScreen
import dev.sphc.srccon.ui.SrcconTheme
import dev.sphc.srccon.ui.SrcconViewModel

class MainActivity : ComponentActivity() {
    private lateinit var srcconViewModel: SrcconViewModel

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        if (::srcconViewModel.isInitialized) {
            srcconViewModel.refreshNotificationPermission()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        srcconViewModel = ViewModelProvider(this)[SrcconViewModel::class.java]

        setContent {
            val state = srcconViewModel.uiState.collectAsStateWithLifecycle().value
            SrcconTheme(state.settings.theme) {
                SrcconScreen(
                    state = state,
                    viewModel = srcconViewModel,
                    onRequestNotificationPermission = ::requestNotificationPermission,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (::srcconViewModel.isInitialized) {
            srcconViewModel.refreshNotificationPermission()
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            srcconViewModel.refreshNotificationPermission()
        }
    }
}
