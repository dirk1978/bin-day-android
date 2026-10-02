package uk.dirk1978.binday.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.time.LocalDateTime
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import uk.dirk1978.binday.data.BinRepository
import uk.dirk1978.binday.data.Cache
import uk.dirk1978.binday.data.Settings
import uk.dirk1978.binday.notify.Reminders

/** Everything the home screen shows. [loaded] is false until DataStore has been read once. */
data class HomeState(
  val loaded: Boolean = false,
  val settings: Settings? = null,
  val cache: Cache = Cache(),
  val now: LocalDateTime = LocalDateTime.now(),
)

class AppViewModel(app: Application) : AndroidViewModel(app) {
  private val repository = BinRepository(app)

  /** Ticks every 30s so "today", "tomorrow" and the 9am switch update while the app is open. */
  private val clock = flow {
    while (true) {
      emit(LocalDateTime.now())
      delay(30_000)
    }
  }

  val state: StateFlow<HomeState> =
    combine(repository.store.settings, repository.store.cache, clock) { settings, cache, now ->
      HomeState(loaded = true, settings = settings, cache = cache, now = now)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeState())

  private val _refreshing = MutableStateFlow(false)
  val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

  fun refresh(ifStale: Boolean = false) {
    if (_refreshing.value) return
    viewModelScope.launch {
      _refreshing.value = true
      try {
        repository.refresh(ifStale)
      } finally {
        _refreshing.value = false
      }
    }
  }

  fun save(settings: Settings) {
    viewModelScope.launch {
      repository.store.saveSettings(settings)
      _refreshing.value = true
      try {
        repository.refresh()
      } finally {
        _refreshing.value = false
      }
    }
  }

  /** After the notification permission is granted, show tonight's reminder if it's due. */
  fun resyncReminder() {
    viewModelScope.launch { Reminders.sync(getApplication()) }
  }
}
