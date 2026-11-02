package com.inakikani.picsync.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ToggleButton
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.inakikani.picsync.data.DatePreset
import com.inakikani.picsync.data.MediaFilters
import com.inakikani.picsync.data.MediaTypeFilter
import com.inakikani.picsync.data.formatMb
import com.inakikani.picsync.data.parseMinSizeMb
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** Bottom sheet with the media type, date and minimum size filters. Changes apply immediately. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun FiltersSheet(filters: MediaFilters, onChange: (MediaFilters) -> Unit, onDismiss: () -> Unit) {
    var sizeText by rememberSaveable { mutableStateOf(filters.minSizeMb?.let(::formatMb).orEmpty()) }
    var pickDates by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Filters", style = MaterialTheme.typography.headlineSmallEmphasized)

            Section("Type") {
                // Single choice: a connected button group (Material 3 Expressive).
                Row(horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
                    val types = MediaTypeFilter.entries
                    types.forEachIndexed { i, t ->
                        ToggleButton(
                            checked = filters.type == t,
                            onCheckedChange = { onChange(filters.copy(type = t)) },
                            modifier = Modifier.weight(1f).semantics { role = Role.RadioButton },
                            shapes = when (i) {
                                0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                                types.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                                else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                            },
                        ) { Text(t.label, maxLines = 1) }
                    }
                }
            }

            Section("Date taken") {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    DatePreset.entries.forEach { p ->
                        FilterChip(
                            selected = filters.date == p,
                            onClick = {
                                if (p == DatePreset.CUSTOM) pickDates = true else onChange(filters.copy(date = p))
                            },
                            label = {
                                Text(
                                    if (p == DatePreset.CUSTOM && filters.date == DatePreset.CUSTOM) {
                                        filters.customRangeLabel()
                                    } else {
                                        p.label
                                    },
                                )
                            },
                        )
                    }
                }
            }

            Section("Minimum size") {
                OutlinedTextField(
                    value = sizeText,
                    onValueChange = {
                        sizeText = it
                        onChange(filters.copy(minSizeMb = parseMinSizeMb(it)))
                    },
                    placeholder = { Text("e.g. 10") },
                    suffix = { Text("MB") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(
                    onClick = {
                        sizeText = ""
                        onChange(MediaFilters())
                    },
                    enabled = filters.isActive,
                    shapes = ButtonDefaults.shapes(),
                ) { Text("Reset") }
                Button(onClick = onDismiss, shapes = ButtonDefaults.shapes()) { Text("Done") }
            }
        }
    }

    if (pickDates) {
        DateRangeDialog(
            filters = filters,
            onPicked = { from, to ->
                pickDates = false
                onChange(filters.copy(date = DatePreset.CUSTOM, customFrom = from, customTo = to))
            },
            onDismiss = { pickDates = false },
        )
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        content()
    }
}

/** Android's date range calendar. A single tapped day means just that day. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateRangeDialog(
    filters: MediaFilters,
    onPicked: (LocalDate, LocalDate) -> Unit,
    onDismiss: () -> Unit,
) {
    val state = rememberDateRangePickerState(
        initialSelectedStartDateMillis = filters.customFrom?.toPickerMillis(),
        initialSelectedEndDateMillis = filters.customTo?.toPickerMillis(),
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                enabled = state.selectedStartDateMillis != null,
                onClick = {
                    val start = state.selectedStartDateMillis ?: return@TextButton
                    val from = start.pickerMillisToDate()
                    val to = state.selectedEndDateMillis?.pickerMillisToDate() ?: from
                    onPicked(from, to)
                },
            ) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    ) {
        DateRangePicker(state = state, modifier = Modifier.weight(1f))
    }
}

// The Material date pickers represent a calendar day as UTC midnight millis.
private fun LocalDate.toPickerMillis(): Long = atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
private fun Long.pickerMillisToDate(): LocalDate = Instant.ofEpochMilli(this).atZone(ZoneOffset.UTC).toLocalDate()
