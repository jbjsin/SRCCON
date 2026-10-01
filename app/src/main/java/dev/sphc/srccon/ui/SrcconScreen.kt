package dev.sphc.srccon.ui

import android.os.SystemClock
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.sphc.srccon.BuildConfig
import dev.sphc.srccon.R
import dev.sphc.srccon.core.OperationPhase
import dev.sphc.srccon.core.PhysicalRelayState
import dev.sphc.srccon.usb.UsbDeviceDescriptor
import dev.sphc.srccon.usb.UsbDeviceFingerprint
import dev.sphc.srccon.usb.UsbSessionState
import dev.sphc.srccon.usb.previousDeviceHints
import kotlinx.coroutines.delay

private enum class MainPage { CONNECTION, CONTROL, SETTINGS }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SrcconScreen(
    state: SrcconUiState,
    viewModel: SrcconViewModel,
    onRequestNotificationPermission: () -> Unit,
) {
    var pageName by rememberSaveable { mutableStateOf(MainPage.CONNECTION.name) }
    val page = MainPage.entries.firstOrNull { it.name == pageName } ?: MainPage.CONNECTION
    var menuExpanded by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.message) {
        state.message?.let { message ->
            snackbarHostState.showSnackbar(message)
            viewModel.clearMessage()
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResourceCompat(R.string.app_name),
                        fontWeight = FontWeight.Bold,
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                ),
                actions = {
                    val themeName = themeName(state.settings.theme)
                    TextButton(
                        onClick = viewModel::cycleTheme,
                        modifier = Modifier
                            .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                            .semantics {
                                contentDescription = themeName
                            },
                    ) {
                        Text(
                            text = themeGlyph(state.settings.theme),
                            style = MaterialTheme.typography.titleLarge,
                        )
                    }
                    Box {
                        val settingsDescription = stringResourceCompat(R.string.page_settings)
                        TextButton(
                            onClick = { menuExpanded = true },
                            modifier = Modifier
                                .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                                .semantics { contentDescription = settingsDescription },
                        ) {
                            Text(
                                text = "☰",
                                style = MaterialTheme.typography.titleLarge,
                            )
                        }
                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResourceCompat(R.string.page_settings)) },
                                onClick = {
                                    pageName = MainPage.SETTINGS.name
                                    menuExpanded = false
                                },
                            )
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
        ) {
            MainSwitcher(
                page = page,
                onPageSelected = { pageName = it.name },
            )
            Spacer(Modifier.height(8.dp))
            StatusPill(state)
            Spacer(Modifier.height(10.dp))

            Box(modifier = Modifier.weight(1f)) {
                when (page) {
                    MainPage.CONNECTION -> ConnectionPage(
                        state = state,
                        onScan = viewModel::scan,
                        onSelectDevice = viewModel::selectDevice,
                        onConnect = viewModel::connect,
                        onDisconnect = viewModel::disconnect,
                        onDemo = viewModel::startDemo,
                        onRequestNotificationPermission = onRequestNotificationPermission,
                        onValidateProfile = viewModel::validateProfile,
                    )

                    MainPage.CONTROL -> ControlPage(
                        state = state,
                        onStop = viewModel::stop,
                        onManualOn = viewModel::startManual,
                        onManualOff = viewModel::manualOff,
                        onSingleStart = viewModel::startSingle,
                        onSafetyOff = viewModel::safetyOff,
                    )

                    MainPage.SETTINGS -> SettingsPage(
                        state = state,
                        onSetTheme = viewModel::setTheme,
                        onSaveMaximum = viewModel::saveMaximumExposure,
                    )
                }
            }
        }
    }
}

@Composable
private fun MainSwitcher(
    page: MainPage,
    onPageSelected: (MainPage) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.extraLarge,
    ) {
        Row(
            modifier = Modifier.padding(6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Segment(
                label = stringResourceCompat(R.string.page_connection),
                selected = page == MainPage.CONNECTION,
                onClick = { onPageSelected(MainPage.CONNECTION) },
            )
            Segment(
                label = stringResourceCompat(R.string.page_control),
                selected = page == MainPage.CONTROL,
                onClick = { onPageSelected(MainPage.CONTROL) },
            )
        }
    }
}

@Composable
private fun RowScope.Segment(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = Modifier
            .weight(1f)
            .heightIn(min = 48.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
        contentColor = if (selected) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        shape = MaterialTheme.shapes.large,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = label,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            )
        }
    }
}

@Composable
private fun StatusPill(state: SrcconUiState) {
    val phase = state.operation.phase
    val label = when (phase) {
        OperationPhase.PREFLIGHT -> stringResourceCompat(R.string.status_preparing)
        OperationPhase.MANUAL_ON -> stringResourceCompat(R.string.status_manual)
        OperationPhase.SINGLE_EXPOSURE -> stringResourceCompat(R.string.status_exposure)
        OperationPhase.STOPPING -> stringResourceCompat(R.string.status_stopping)
        OperationPhase.FAULT -> stringResourceCompat(R.string.status_fault)
        OperationPhase.IDLE -> when {
            state.demoReady -> stringResourceCompat(R.string.status_ready_demo)
            state.usbSession is UsbSessionState.Ready -> stringResourceCompat(R.string.status_ready_usb)
            else -> stringResourceCompat(R.string.status_disconnected)
        }
    }
    val isFault = phase == OperationPhase.FAULT
    val isActive = phase != OperationPhase.IDLE && !isFault
    val background = when {
        isFault -> MaterialTheme.colorScheme.errorContainer
        isActive -> MaterialTheme.colorScheme.primaryContainer
        state.readyTarget != null -> MaterialTheme.colorScheme.secondaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val foreground = when {
        isFault -> MaterialTheme.colorScheme.onErrorContainer
        isActive -> MaterialTheme.colorScheme.onPrimaryContainer
        state.readyTarget != null -> MaterialTheme.colorScheme.onSecondaryContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = background,
        contentColor = foreground,
        shape = MaterialTheme.shapes.extraLarge,
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun ConnectionPage(
    state: SrcconUiState,
    onScan: () -> Unit,
    onSelectDevice: (UsbDeviceDescriptor) -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onDemo: () -> Unit,
    onRequestNotificationPermission: () -> Unit,
    onValidateProfile: () -> Unit,
) {
    val previousHints = remember(state.settings.lastDeviceFingerprint, state.devices) {
        previousDeviceHints(
            UsbDeviceFingerprint.decode(state.settings.lastDeviceFingerprint),
            state.devices,
        )
    }
    val session = state.usbSession
    val showDisconnect = state.demoReady ||
        session is UsbSessionState.Ready ||
        session is UsbSessionState.PermissionRequested ||
        session is UsbSessionState.Fault
    val canConnect = session is UsbSessionState.Selected && !state.connecting && state.canChangeConnection

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SectionCard(title = stringResourceCompat(R.string.usb_devices)) {
            Text(
                text = stringResourceCompat(R.string.scan_is_passive),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(
                onClick = onScan,
                enabled = !state.scanning && state.canChangeConnection,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
            ) {
                Text(stringResourceCompat(R.string.action_scan))
            }

            if (state.devices.isEmpty()) {
                Text(
                    text = stringResourceCompat(R.string.no_usb_devices),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                state.devices.forEachIndexed { index, device ->
                    DeviceChoice(
                        device = device,
                        selected = state.selectedKey == device.key,
                        previouslyUsed = previousHints.containsKey(device.key),
                        enabled = state.canChangeConnection,
                        onClick = { onSelectDevice(device) },
                    )
                    if (index != state.devices.lastIndex) HorizontalDivider()
                }
            }

            if (state.selectedKey == null && !state.demoReady) {
                Text(
                    text = stringResourceCompat(R.string.select_device_first),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (showDisconnect) {
                    Button(
                        onClick = onDisconnect,
                        enabled = state.canChangeConnection,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp),
                    ) {
                        Text(
                            text = stringResourceCompat(R.string.action_disconnect),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                } else {
                    Button(
                        onClick = onConnect,
                        enabled = canConnect,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp),
                    ) {
                        Text(stringResourceCompat(R.string.action_connect))
                    }
                }
                OutlinedButton(
                    onClick = onDemo,
                    enabled = state.canChangeConnection &&
                        state.operation.phase == OperationPhase.IDLE &&
                        !state.settings.mayBeEnergized &&
                        !state.demoReady,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp),
                ) {
                    Text(
                        text = stringResourceCompat(R.string.action_demo),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        if (!state.notificationPermissionGranted) {
            SectionCard(title = stringResourceCompat(R.string.action_request_notifications)) {
                Text(
                    text = stringResourceCompat(R.string.notification_permission_note),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Button(
                    onClick = onRequestNotificationPermission,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp),
                ) {
                    Text(stringResourceCompat(R.string.action_request_notifications))
                }
            }
        }

        SectionCard(title = stringResourceCompat(R.string.protocol)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResourceCompat(R.string.profile_vctec),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = stringResourceCompat(R.string.profile_read_only),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                OutlinedButton(
                    onClick = onValidateProfile,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text(stringResourceCompat(R.string.action_validate))
                }
            }
            Text(
                text = stringResourceCompat(R.string.serial_summary),
                style = MaterialTheme.typography.bodyMedium,
            )
            CommandRow(stringResourceCompat(R.string.command_on), "A0 01 01 A2")
            CommandRow(stringResourceCompat(R.string.command_off), "A0 01 00 A1")
            Text(
                text = stringResourceCompat(R.string.profile_valid),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SectionCard(title = stringResourceCompat(R.string.ownership_note_title)) {
            Text(
                text = stringResourceCompat(R.string.ownership_note),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun DeviceChoice(
    device: UsbDeviceDescriptor,
    selected: Boolean,
    previouslyUsed: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val unavailable = stringResourceCompat(R.string.not_available)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        RadioButton(
            selected = selected,
            onClick = null,
            enabled = enabled,
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(top = 3.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = device.productName ?: device.key.deviceName,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                if (previouslyUsed) {
                    CompactBadge(stringResourceCompat(R.string.device_previously_used))
                }
            }
            DeviceMetadata(
                stringResourceCompat(
                    R.string.device_vid_pid,
                    device.key.vendorId,
                    device.key.productId,
                ),
            )
            DeviceMetadata(
                stringResourceCompat(
                    R.string.device_driver,
                    device.driverFamily?.displayName ?: unavailable,
                ),
            )
            DeviceMetadata(stringResourceCompat(R.string.device_port_count, device.portCount))
            DeviceMetadata(
                stringResourceCompat(
                    R.string.device_manufacturer,
                    device.manufacturerName ?: unavailable,
                ),
            )
            DeviceMetadata(
                stringResourceCompat(
                    R.string.device_serial,
                    device.serialNumber ?: unavailable,
                ),
            )
            DeviceMetadata(stringResourceCompat(R.string.device_session_id, device.key.deviceName))
            Text(
                text = stringResourceCompat(
                    if (device.permissionGranted) {
                        R.string.device_permission_granted
                    } else {
                        R.string.device_permission_required
                    },
                ),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun CompactBadge(label: String) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shape = MaterialTheme.shapes.extraLarge,
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
        )
    }
}

@Composable
private fun DeviceMetadata(value: String) {
    Text(
        text = value,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun CommandRow(label: String, command: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(text = label, fontWeight = FontWeight.Bold)
            Text(text = command, fontFamily = FontFamily.Monospace)
        }
    }
}

@Composable
private fun ControlPage(
    state: SrcconUiState,
    onStop: () -> Unit,
    onManualOn: () -> Unit,
    onManualOff: () -> Unit,
    onSingleStart: (Int) -> Unit,
    onSafetyOff: () -> Unit,
) {
    val phase = state.operation.phase
    val active = phase == OperationPhase.PREFLIGHT ||
        phase == OperationPhase.MANUAL_ON ||
        phase == OperationPhase.SINGLE_EXPOSURE ||
        phase == OperationPhase.STOPPING
    val stopEnabled = phase == OperationPhase.PREFLIGHT ||
        phase == OperationPhase.MANUAL_ON ||
        phase == OperationPhase.SINGLE_EXPOSURE
    val idle = phase == OperationPhase.IDLE
    val ready = state.readyTarget != null
    var exposureText by rememberSaveable { mutableStateOf(state.settings.singleExposureSeconds.toString()) }
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }

    LaunchedEffect(state.settings.singleExposureSeconds) {
        exposureText = state.settings.singleExposureSeconds.toString()
    }
    LaunchedEffect(state.operation.deadlineElapsedMillis, phase) {
        while (phase == OperationPhase.SINGLE_EXPOSURE && state.operation.deadlineElapsedMillis != null) {
            now = SystemClock.elapsedRealtime()
            delay(200)
        }
        now = SystemClock.elapsedRealtime()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (active) {
            Button(
                onClick = onStop,
                enabled = stopEnabled,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
            ) {
                Text(
                    text = stringResourceCompat(R.string.action_stop),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Black,
                )
            }
        }

        if (phase == OperationPhase.FAULT) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                ),
                shape = MaterialTheme.shapes.large,
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = stringResourceCompat(R.string.relay_unknown),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = stringResourceCompat(R.string.fault_recovery_note),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    state.operation.message?.let {
                        Text(text = it, style = MaterialTheme.typography.bodySmall)
                    }
                    Button(
                        onClick = onSafetyOff,
                        enabled = ready,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError,
                        ),
                    ) {
                        Text(stringResourceCompat(R.string.action_safety_off))
                    }
                }
            }
        }

        SectionCard(title = stringResourceCompat(R.string.relay_status)) {
            Text(
                text = relayStateLabel(state.operation.physicalRelayState),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = if (state.operation.physicalRelayState == PhysicalRelayState.UNKNOWN) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
            Text(
                text = operationLabel(state),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val deadline = state.operation.deadlineElapsedMillis
            if (deadline != null && phase == OperationPhase.SINGLE_EXPOSURE) {
                val remainingSeconds = ((deadline - now).coerceAtLeast(0L)) / 1_000f
                Text(
                    text = "${stringResourceCompat(R.string.remaining)}: " +
                        stringResourceCompat(R.string.seconds_value, remainingSeconds),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
            }
        }

        if (!ready) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.large,
            ) {
                Text(
                    text = stringResourceCompat(R.string.connect_to_control),
                    modifier = Modifier.padding(14.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        SectionCard(title = stringResourceCompat(R.string.manual_control)) {
            Text(
                text = stringResourceCompat(R.string.manual_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = onManualOn,
                    enabled = ready && idle,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp),
                ) {
                    Text(stringResourceCompat(R.string.action_on))
                }
                OutlinedButton(
                    onClick = onManualOff,
                    enabled = phase == OperationPhase.MANUAL_ON,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp),
                ) {
                    Text(stringResourceCompat(R.string.action_off))
                }
            }
        }

        SectionCard(title = stringResourceCompat(R.string.single_exposure)) {
            OutlinedTextField(
                value = exposureText,
                onValueChange = { value ->
                    if (value.isEmpty() || value.all(Char::isDigit)) exposureText = value
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResourceCompat(R.string.exposure_seconds)) },
                supportingText = {
                    Text("1–${state.settings.maximumExposureSeconds}")
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            Button(
                onClick = { onSingleStart(exposureText.toIntOrNull() ?: 0) },
                enabled = ready && idle,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
            ) {
                Text(stringResourceCompat(R.string.action_start))
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun SettingsPage(
    state: SrcconUiState,
    onSetTheme: (AppThemeMode) -> Unit,
    onSaveMaximum: (Int) -> Unit,
) {
    var maximumText by rememberSaveable {
        mutableStateOf(state.settings.maximumExposureSeconds.toString())
    }
    LaunchedEffect(state.settings.maximumExposureSeconds) {
        maximumText = state.settings.maximumExposureSeconds.toString()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SectionCard(title = stringResourceCompat(R.string.interface_section)) {
            Text(
                text = stringResourceCompat(R.string.theme_label),
                style = MaterialTheme.typography.labelLarge,
            )
            AppThemeMode.entries.forEach { mode ->
                val selected = state.settings.theme == mode
                if (selected) {
                    Button(
                        onClick = { onSetTheme(mode) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp),
                    ) {
                        Text("${themeGlyph(mode)}  ${themeName(mode)}")
                    }
                } else {
                    OutlinedButton(
                        onClick = { onSetTheme(mode) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp),
                    ) {
                        Text("${themeGlyph(mode)}  ${themeName(mode)}")
                    }
                }
            }
        }

        SectionCard(title = stringResourceCompat(R.string.safety_section)) {
            OutlinedTextField(
                value = maximumText,
                onValueChange = { value ->
                    if (value.isEmpty() || value.all(Char::isDigit)) maximumText = value
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResourceCompat(R.string.maximum_exposure)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            Button(
                onClick = { onSaveMaximum(maximumText.toIntOrNull() ?: 0) },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
            ) {
                Text(stringResourceCompat(R.string.action_save))
            }
            Text(
                text = stringResourceCompat(R.string.safety_summary),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SectionCard(title = stringResourceCompat(R.string.about_section)) {
            Text(
                text = stringResourceCompat(R.string.version_label, BuildConfig.VERSION_NAME),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = stringResourceCompat(R.string.publisher_label),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = stringResourceCompat(R.string.validation_status),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
            )
            HorizontalDivider()
            Text(
                text = stringResourceCompat(R.string.scope_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            content()
        }
    }
}

@Composable
private fun relayStateLabel(state: PhysicalRelayState): String = when (state) {
    PhysicalRelayState.UNKNOWN -> stringResourceCompat(R.string.relay_unknown)
    PhysicalRelayState.ON_COMMAND_SENT -> stringResourceCompat(R.string.relay_on_sent)
    PhysicalRelayState.OFF_COMMAND_SENT -> stringResourceCompat(R.string.relay_off_sent)
}

@Composable
private fun operationLabel(state: SrcconUiState): String = when (state.operation.phase) {
    OperationPhase.IDLE -> when {
        state.demoReady -> stringResourceCompat(R.string.status_ready_demo)
        state.usbSession is UsbSessionState.Ready -> stringResourceCompat(R.string.status_ready_usb)
        else -> stringResourceCompat(R.string.status_disconnected)
    }
    OperationPhase.PREFLIGHT -> stringResourceCompat(R.string.status_preparing)
    OperationPhase.MANUAL_ON -> stringResourceCompat(R.string.status_manual)
    OperationPhase.SINGLE_EXPOSURE -> stringResourceCompat(R.string.status_exposure)
    OperationPhase.STOPPING -> stringResourceCompat(R.string.status_stopping)
    OperationPhase.FAULT -> stringResourceCompat(R.string.status_fault)
}

@Composable
private fun themeName(mode: AppThemeMode): String = when (mode) {
    AppThemeMode.LIGHT -> stringResourceCompat(R.string.theme_light)
    AppThemeMode.DARK -> stringResourceCompat(R.string.theme_dark)
    AppThemeMode.NIGHT -> stringResourceCompat(R.string.theme_night)
}

private fun themeGlyph(mode: AppThemeMode): String = when (mode) {
    AppThemeMode.LIGHT -> "☀"
    AppThemeMode.DARK -> "◐"
    AppThemeMode.NIGHT -> "●"
}

@Composable
private fun stringResourceCompat(id: Int, vararg formatArgs: Any): String =
    androidx.compose.ui.res.stringResource(id, *formatArgs)
