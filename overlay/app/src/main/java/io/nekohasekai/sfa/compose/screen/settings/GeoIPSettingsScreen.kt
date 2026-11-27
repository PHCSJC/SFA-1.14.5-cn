package io.nekohasekai.sfa.compose.screen.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import io.nekohasekai.sfa.compose.topbar.LocalScaffoldPadding
import io.nekohasekai.sfa.compose.topbar.OverrideTopBar
import io.nekohasekai.sfa.database.Settings
import io.nekohasekai.sfa.geoip.GeoIPRuleSet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Settings page for the bundled China-IP rule-set.
 *
 * Shows the current data size and timestamp, allows a manual refresh, and
 * toggles scheduled updates. A failed refresh is reported inline and leaves
 * the previous data in place.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GeoIPSettingsScreen(navController: NavController) {
    OverrideTopBar {
        TopAppBar(
            title = { Text("China IP rule-set") },
            navigationIcon = {
                IconButton(onClick = { navController.popBackStack() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                }
            },
        )
    }

    val scope = rememberCoroutineScope()
    val scaffoldPadding = LocalScaffoldPadding.current

    var busy by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf("") }
    var lastError by remember { mutableStateOf(Settings.geoipLastError) }
    var sizeBytes by remember { mutableStateOf(GeoIPRuleSet.size()) }
    var lastUpdate by remember { mutableStateOf(Settings.geoipLastUpdate) }
    var lastVersion by remember { mutableStateOf(Settings.geoipLastVersion) }
    var autoUpdate by remember { mutableStateOf(Settings.geoipAutoUpdateEnabled) }
    var intervalHours by remember { mutableStateOf(Settings.geoipUpdateIntervalHours) }

    fun refreshUi() {
        sizeBytes = GeoIPRuleSet.size()
        lastUpdate = Settings.geoipLastUpdate
        lastVersion = Settings.geoipLastVersion
        lastError = Settings.geoipLastError
    }

    val timeFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .verticalScroll(rememberScrollState())
            .padding(
                top = scaffoldPadding.calculateTopPadding() + 8.dp,
                bottom = scaffoldPadding.calculateBottomPadding() + 8.dp,
            ),
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
            ),
        ) {
            Column {
                ListItem(
                    headlineContent = { Text("Current data") },
                    supportingContent = {
                        Column {
                            Text(
                                if (sizeBytes > 0) {
                                    "%.1f KB".format(sizeBytes / 1024.0)
                                } else {
                                    "not installed"
                                },
                            )
                            if (lastVersion.isNotEmpty()) {
                                Text("version $lastVersion")
                            }
                            if (lastUpdate > 0) {
                                Text("updated ${timeFormat.format(Date(lastUpdate))}")
                            }
                        }
                    },
                    leadingContent = {
                        Icon(
                            Icons.Outlined.Public,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    },
                    modifier = Modifier.clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)),
                )
            }
        }

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
            ),
        ) {
            Column {
                ListItem(
                    headlineContent = { Text("Automatic updates") },
                    supportingContent = {
                        Text(
                            if (autoUpdate) {
                                "Checks every ${intervalHours}h while the service starts"
                            } else {
                                "Disabled; data stays at the bundled version"
                            },
                        )
                    },
                    trailingContent = {
                        Switch(
                            checked = autoUpdate,
                            onCheckedChange = { enabled ->
                                autoUpdate = enabled
                                Settings.geoipAutoUpdateEnabled = enabled
                            },
                        )
                    },
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    listOf(24, 72, 168).forEach { hours ->
                        OutlinedButton(
                            onClick = {
                                intervalHours = hours
                                Settings.geoipUpdateIntervalHours = hours
                            },
                            enabled = autoUpdate && !busy,
                        ) {
                            Text(
                                "${hours}h",
                                fontWeight = if (intervalHours == hours) FontWeight.Bold else FontWeight.Normal,
                            )
                        }
                    }
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = {
                    scope.launch {
                        busy = true
                        statusText = "downloading…"
                        val result = GeoIPRuleSet.update()
                        refreshUi()
                        busy = false
                        statusText = result.fold(
                            onSuccess = { "updated (${it / 1024} KB)" },
                            onFailure = { "failed: ${it.message}" },
                        )
                    }
                },
                enabled = !busy,
            ) {
                Text(if (busy) "Working…" else "Update now")
            }

            OutlinedButton(
                onClick = {
                    scope.launch {
                        busy = true
                        statusText = "restoring bundled data…"
                        withContext(Dispatchers.IO) {
                            runCatching {
                                GeoIPRuleSet.file.delete()
                                GeoIPRuleSet.ensureInstalled()
                            }
                        }
                        refreshUi()
                        busy = false
                        statusText = "bundled data restored"
                    }
                },
                enabled = !busy,
            ) {
                Text("Reset to bundled")
            }
        }

        if (statusText.isNotEmpty()) {
            Text(
                statusText,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = if (lastError.isEmpty()) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.error
                },
            )
        }

        if (lastError.isNotEmpty()) {
            Text(
                "last error: $lastError",
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        Text(
            "The rule-set is bundled in the app, so a fresh install works without " +
                "network access. A failed update keeps the previous data.",
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
