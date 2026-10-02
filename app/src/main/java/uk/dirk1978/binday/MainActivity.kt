package uk.dirk1978.binday

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import uk.dirk1978.binday.notify.Reminders
import uk.dirk1978.binday.theme.BinDayTheme
import uk.dirk1978.binday.ui.AppViewModel
import uk.dirk1978.binday.ui.HomeScreen
import uk.dirk1978.binday.ui.SetupScreen

class MainActivity : ComponentActivity() {
  private val viewModel: AppViewModel by viewModels()

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    setContent {
      BinDayTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { BinDayApp(viewModel) }
      }
    }
  }
}

@Composable
private fun BinDayApp(viewModel: AppViewModel) {
  val state by viewModel.state.collectAsStateWithLifecycle()
  val refreshing by viewModel.refreshing.collectAsStateWithLifecycle()
  val context = LocalContext.current
  var editing by remember { mutableStateOf(false) }
  var notificationsAllowed by remember { mutableStateOf(Reminders.canNotify(context)) }

  val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
    notificationsAllowed = granted
    if (granted) viewModel.resyncReminder()
  }
  fun askForNotifications() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
  }

  // Coming back to the app: re-check the permission, and refresh if the cache is old.
  LifecycleResumeEffect(Unit) {
    notificationsAllowed = Reminders.canNotify(context)
    viewModel.refresh(ifStale = true)
    onPauseOrDispose {}
  }

  if (!state.loaded) return
  val settings = state.settings

  if (settings == null || editing) {
    BackHandler(enabled = editing) { editing = false }
    SetupScreen(
      initial = settings,
      onSave = {
        viewModel.save(it)
        editing = false
        if (it.remind && !Reminders.canNotify(context)) askForNotifications()
      },
      onCancel = if (settings != null) ({ editing = false }) else null,
    )
  } else {
    HomeScreen(
      state = state,
      refreshing = refreshing,
      notificationsAllowed = notificationsAllowed,
      onRefresh = { viewModel.refresh() },
      onSettings = { editing = true },
      onAllowNotifications = ::askForNotifications,
    )
  }
}
