package uk.dirk1978.binday.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import uk.dirk1978.binday.core.Collection
import uk.dirk1978.binday.core.Collections

private val LONG_DATE = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.UK)
private val SHORT_DATE = DateTimeFormatter.ofPattern("EEE d MMM", Locale.UK)
private val UPDATED = DateTimeFormatter.ofPattern("d MMM, HH:mm", Locale.UK)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
  state: HomeState,
  refreshing: Boolean,
  notificationsAllowed: Boolean,
  onRefresh: () -> Unit,
  onSettings: () -> Unit,
  onAllowNotifications: () -> Unit,
) {
  val settings = state.settings ?: return
  val cache = state.cache
  val schedule = cache.schedule?.takeIf { cache.uprn == settings.uprn }
  val today = state.now.toLocalDate()
  val current = schedule?.let { Collections.state(it, state.now, settings.garden) }
  val upcoming = schedule?.let { Collections.upcoming(it, today, settings.garden) }.orEmpty()
  // The hero shows the next collection, so the list starts after it.
  val rest = upcoming.dropWhile { current?.next == null || it.date <= current.next.date }
  val round = schedule?.let { Collections.resolveGardenRound(it, settings.garden) }

  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text("Bin Day") },
        actions = { TextButton(onClick = onSettings) { Text("Settings") } },
      )
    },
  ) { innerPadding ->
    Column(
      Modifier
        .fillMaxSize()
        .padding(innerPadding)
        .consumeWindowInsets(innerPadding)
        .verticalScroll(rememberScrollState())
        .padding(horizontal = 16.dp, vertical = 8.dp),
      verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      if (refreshing) LinearProgressIndicator(Modifier.fillMaxWidth())

      if (settings.remind && !notificationsAllowed) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
          Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Notifications are off", fontWeight = FontWeight.SemiBold)
            Text("Allow notifications so Bin Day can remind you the evening before.")
            OutlinedButton(onClick = onAllowNotifications) { Text("Allow notifications") }
          }
        }
      }

      if (cache.error != null && cache.uprn == settings.uprn) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
          Text(
            if (schedule != null) "Couldn't update from the council just now, so these are the last dates fetched. ${cache.error}"
            else cache.error,
            Modifier.padding(16.dp),
          )
        }
      }

      Hero(current?.collected, current?.next, today, loading = schedule == null && cache.error == null)

      if (rest.isNotEmpty()) {
        Text("COMING UP", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Column {
          rest.take(6).forEachIndexed { i, collection ->
            if (i > 0) HorizontalDivider()
            UpcomingRow(collection)
          }
        }
      }

      Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Muted("${titleCase(settings.address)}, ${settings.postcode}")
        if (round != null) Muted("Brown bin: ${round.describe()}")
        Muted(
          if (settings.remind) "Reminder: from ${settings.reminderTime} the evening before"
          else "Reminders are off",
        )
        cache.fetchedAt?.takeIf { schedule != null }?.let {
          Muted("Updated ${it.atZone(ZoneId.systemDefault()).format(UPDATED)}")
        }
      }
      OutlinedButton(onClick = onRefresh, enabled = !refreshing) { Text(if (refreshing) "Refreshing…" else "Refresh") }
    }
  }
}

@Composable
private fun Hero(collected: Collection?, next: Collection?, today: LocalDate, loading: Boolean) {
  val (eyebrow, title, subtitle) = when {
    collected != null -> Triple(
      "DONE FOR TODAY",
      "The van's been",
      next?.let { "Next: ${Collections.describe(it.bins)}, ${it.date.format(LONG_DATE)}" }
        ?: "Today's ${Collections.describe(collected.bins)} collected",
    )
    next == null -> Triple("", if (loading) "Fetching dates…" else "No dates available", "")
    else -> when (ChronoUnit.DAYS.between(today, next.date)) {
      0L -> Triple("TODAY", capitalise(Collections.describe(next.bins)), next.date.format(LONG_DATE))
      1L -> Triple("TONIGHT", "Put out the ${Collections.describe(next.bins)}", "For tomorrow, ${next.date.format(LONG_DATE)}")
      else -> Triple("NEXT COLLECTION", capitalise(Collections.describe(next.bins)), next.date.format(LONG_DATE))
    }
  }
  Card(Modifier.fillMaxWidth()) {
    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
      if (collected != null) VanImage(88.dp) else BinImage(next?.bins.orEmpty(), 88.dp)
      Spacer(Modifier.width(16.dp))
      Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (eyebrow.isNotEmpty()) {
          Text(eyebrow, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        }
        Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        if (subtitle.isNotEmpty()) Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
      }
    }
  }
}

@Composable
private fun UpcomingRow(collection: Collection) {
  Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
    BinImage(collection.bins, 32.dp)
    Spacer(Modifier.width(12.dp))
    Text(collection.date.format(SHORT_DATE), Modifier.width(110.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text(collection.bins.joinToString(" + "))
  }
}

@Composable
fun BinImage(bins: List<String>, size: Dp) {
  val px = with(LocalDensity.current) { size.roundToPx() }
  val bitmap = remember(bins, px) { BinArt.bin(px, bins).asImageBitmap() }
  Image(
    bitmap,
    contentDescription = null,
    modifier = Modifier.size(size).semantics { contentDescription = Collections.describe(bins) },
  )
}

@Composable
private fun VanImage(size: Dp) {
  val px = with(LocalDensity.current) { size.roundToPx() }
  val bitmap = remember(px) { BinArt.van(px).asImageBitmap() }
  Image(bitmap, contentDescription = "Bin lorry: the van's been", modifier = Modifier.size(size))
}

@Composable
private fun Muted(text: String) =
  Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

private fun capitalise(text: String) = text.replaceFirstChar { it.titlecase(Locale.UK) }

/** "4 WILLOW CLOSE" -> "4 Willow Close". */
fun titleCase(text: String) =
  text.lowercase(Locale.UK).split(" ").joinToString(" ") { it.replaceFirstChar { c -> c.titlecase(Locale.UK) } }
