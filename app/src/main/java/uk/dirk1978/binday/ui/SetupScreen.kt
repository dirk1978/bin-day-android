package uk.dirk1978.binday.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import java.time.LocalTime
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import uk.dirk1978.binday.core.Address
import uk.dirk1978.binday.core.Collections
import uk.dirk1978.binday.core.Council
import uk.dirk1978.binday.core.GardenChoice
import uk.dirk1978.binday.core.GardenStreet
import uk.dirk1978.binday.data.CouncilApi
import uk.dirk1978.binday.data.CouncilException
import uk.dirk1978.binday.data.Settings

/** A garden waste option shown in setup. */
private data class GardenOption(val label: String, val choice: GardenChoice)

/**
 * Postcode -> address -> garden waste -> reminder. The garden waste choices
 * come from the council's street table for the chosen address; only if the
 * street isn't listed do we ask which bin the brown one goes out with.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupScreen(initial: Settings?, onSave: (Settings) -> Unit, onCancel: (() -> Unit)?) {
  val scope = rememberCoroutineScope()
  var postcode by remember { mutableStateOf(initial?.postcode.orEmpty()) }
  var addresses by remember { mutableStateOf<List<Address>>(emptyList()) }
  var gardenRows by remember { mutableStateOf<List<GardenStreet>?>(null) }
  var selected by remember { mutableStateOf<Address?>(null) }
  var hasGarden by remember { mutableStateOf(initial != null && initial.garden != GardenChoice.None) }
  var garden by remember { mutableStateOf(initial?.garden ?: GardenChoice.None) }
  var remind by remember { mutableStateOf(initial?.remind ?: true) }
  var reminderTime by remember { mutableStateOf(initial?.reminderTime ?: Settings.DEFAULT_REMINDER_TIME) }
  var busy by remember { mutableStateOf(false) }
  var error by remember { mutableStateOf<String?>(null) }
  var showTimePicker by remember { mutableStateOf(false) }
  val keyboard = LocalSoftwareKeyboardController.current

  fun find() {
    keyboard?.hide()
    error = null
    busy = true
    scope.launch {
      try {
        val streets = async { runCatching { CouncilApi.fetchGardenStreets() }.getOrDefault(emptyList()) }
        val found = CouncilApi.findAddresses(postcode)
        gardenRows = streets.await()
        postcode = Council.normalisePostcode(postcode)
        addresses = found
        selected = found.firstOrNull { it.uprn == initial?.uprn } ?: found.first()
      } catch (e: CouncilException) {
        addresses = emptyList()
        selected = null
        error = e.message
      } finally {
        busy = false
      }
    }
  }

  val gardenOptions: List<GardenOption> = remember(selected, gardenRows) {
    val rounds = Collections.gardenRoundsFor(gardenRows.orEmpty(), postcode, selected?.address.orEmpty())
    if (rounds.isNotEmpty()) rounds.map { GardenOption(it.describe(), GardenChoice.Round(it)) }
    else listOf("Green", "Grey").map { GardenOption("With my $it bin", GardenChoice.PairWith(it)) }
  }
  val gardenFromCouncil = gardenOptions.firstOrNull()?.choice is GardenChoice.Round
  // Keep a saved choice if it's still offered, otherwise take the first.
  val chosenGarden = if (!hasGarden) GardenChoice.None else gardenOptions.firstOrNull { it.choice == garden }?.choice
    ?: gardenOptions.first().choice

  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text(if (initial == null) "Set up Bin Day" else "Settings") },
        navigationIcon = { if (onCancel != null) TextButton(onClick = onCancel) { Text("Cancel") } },
      )
    },
  ) { innerPadding ->
    Column(
      Modifier
        .fillMaxSize()
        .padding(innerPadding)
        .consumeWindowInsets(innerPadding)
        .imePadding()
        .verticalScroll(rememberScrollState())
        .padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      Text(
        "See which bins go out next, for any address in Bromsgrove District.",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )

      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
          value = postcode,
          onValueChange = { postcode = it.uppercase() },
          label = { Text("Postcode") },
          placeholder = { Text("e.g. B61 8DA") },
          singleLine = true,
          keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Search),
          keyboardActions = KeyboardActions(onSearch = { if (!busy) find() }),
          modifier = Modifier.weight(1f),
        )
        Button(onClick = ::find, enabled = !busy && postcode.isNotBlank()) { Text(if (busy) "Finding…" else "Find") }
      }
      error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

      if (addresses.isNotEmpty()) {
        AddressPicker(addresses, selected) { selected = it }

        Column {
          Text("Garden waste (brown bin)", fontWeight = FontWeight.SemiBold)
          CheckRow("I pay for garden waste", hasGarden) { hasGarden = it }
          if (hasGarden) {
            Text(
              when {
                !gardenFromCouncil && gardenRows.isNullOrEmpty() ->
                  "Couldn't load the council's garden waste list. Which bin does your brown bin go out with?"
                !gardenFromCouncil -> "Your street isn't in the council's garden waste list. Which bin does your brown bin go out with?"
                gardenOptions.size > 1 -> "The council lists more than one garden waste round for this postcode. Choose yours:"
                else -> "From the council's garden waste list, your brown bin is collected on:"
              },
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            gardenOptions.forEach { option ->
              RadioRow(option.label, option.choice == chosenGarden) { garden = option.choice }
            }
          }
        }

        Column {
          Text("Reminder", fontWeight = FontWeight.SemiBold)
          Row(
            Modifier.fillMaxWidth().toggleable(remind, role = Role.Switch) { remind = it },
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Text("Notify me the evening before", Modifier.weight(1f))
            Switch(checked = remind, onCheckedChange = null)
          }
          if (remind) {
            OutlinedButton(onClick = { showTimePicker = true }) { Text("From $reminderTime") }
            Text(
              "Stays in your notifications until 9am on collection day.",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }

        Button(
          onClick = {
            val address = selected ?: return@Button
            onSave(Settings(postcode, address.uprn, address.address, chosenGarden, remind, reminderTime))
          },
          enabled = selected != null,
          modifier = Modifier.fillMaxWidth(),
        ) { Text("Save") }
      }
    }
  }

  if (showTimePicker) {
    val pickerState = rememberTimePickerState(reminderTime.hour, reminderTime.minute, is24Hour = true)
    AlertDialog(
      onDismissRequest = { showTimePicker = false },
      title = { Text("Remind me from") },
      text = { TimePicker(pickerState) },
      confirmButton = {
        TextButton(onClick = {
          reminderTime = LocalTime.of(pickerState.hour, pickerState.minute)
          showTimePicker = false
        }) { Text("OK") }
      },
      dismissButton = { TextButton(onClick = { showTimePicker = false }) { Text("Cancel") } },
    )
  }
}

@Composable
private fun AddressPicker(addresses: List<Address>, selected: Address?, onSelect: (Address) -> Unit) {
  var open by remember { mutableStateOf(false) }
  Column {
    Text("Address", fontWeight = FontWeight.SemiBold)
    Box {
      OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
        Text(selected?.let { titleCase(it.address) } ?: "Choose your address", Modifier.weight(1f))
        Text("▾")
      }
      DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        addresses.forEach { address ->
          DropdownMenuItem(
            text = { Text(titleCase(address.address)) },
            onClick = {
              onSelect(address)
              open = false
            },
          )
        }
      }
    }
  }
}

@Composable
private fun CheckRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
  Row(
    Modifier.fillMaxWidth().toggleable(checked, role = Role.Checkbox, onValueChange = onChange),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Checkbox(checked = checked, onCheckedChange = null)
    Text(label, Modifier.padding(start = 12.dp))
  }
}

@Composable
private fun RadioRow(label: String, selected: Boolean, onSelect: () -> Unit) {
  Row(
    Modifier.fillMaxWidth().selectable(selected, role = Role.RadioButton, onClick = onSelect).padding(vertical = 8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    RadioButton(selected = selected, onClick = null)
    Text(label, Modifier.padding(start = 12.dp))
  }
}
