package com.v2ray.ang.ui.firewall

import android.app.Activity
import android.net.VpnService
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.v2ray.ang.R
import com.v2ray.ang.dto.AppInfo
import com.v2ray.ang.service.FirewallPrefs
import com.v2ray.ang.service.FirewallService
import com.v2ray.ang.ui.base.BaseComponentActivity
import com.v2ray.ang.ui.compose.AppDivider
import com.v2ray.ang.ui.compose.AppListItem
import com.v2ray.ang.ui.compose.AppTopBar
import com.v2ray.ang.ui.compose.ItemDivider
import com.v2ray.ang.ui.compose.NavigationBarsBottomPadding
import com.v2ray.ang.ui.compose.verticalScrollbar
import com.v2ray.ang.util.AppManagerUtil
import java.text.Collator

/**
 * Layar firewall: centang = internet aplikasi itu DIBLOKIR.
 * Bisa dipakai mandiri, atau bersamaan dengan v2ray.
 */
class FirewallActivity : BaseComponentActivity() {

    private var enabled by mutableStateOf(false)

    private val vpnPermission =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                FirewallService.start(this)
                enabled = true
            } else {
                enabled = false
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enabled = FirewallService.isEnabled(this)
    }

    override fun onResume() {
        super.onResume()
        // Bisa berubah kalau v2ray mengambil alih VPN saat layar ini di latar belakang.
        enabled = FirewallService.isEnabled(this)
    }

    private fun toggle(on: Boolean) {
        if (!on) {
            FirewallService.stop(this)
            enabled = false
            return
        }
        if (!FirewallPrefs.allowOnly() && FirewallPrefs.blocked().isEmpty()) {
            android.widget.Toast.makeText(this, R.string.firewall_pick_first, android.widget.Toast.LENGTH_LONG).show()
            return
        }
        val intent = VpnService.prepare(this)
        if (intent == null) {
            FirewallService.start(this)
            enabled = true
        } else {
            vpnPermission.launch(intent)
        }
    }

    @Composable
    override fun ScreenContent() {
        var apps by remember { mutableStateOf<List<AppInfo>>(emptyList()) }
        var loading by remember { mutableStateOf(true) }
        var blocked by remember { mutableStateOf(FirewallPrefs.blocked()) }
        var withV2ray by remember { mutableStateOf(FirewallPrefs.withV2ray()) }
        var allowOnly by remember { mutableStateOf(FirewallPrefs.allowOnly()) }
        var showSearch by rememberSaveable { mutableStateOf(false) }
        var query by rememberSaveable { mutableStateOf("") }
        val listState = rememberLazyListState()

        LaunchedEffect(Unit) {
            val collator = Collator.getInstance()
            val list = AppManagerUtil.loadNetworkAppList(this@FirewallActivity)
                .filter { it.packageName != packageName }
                .sortedWith { a, b ->
                    when {
                        a.isSystemApp && !b.isSystemApp -> 1
                        !a.isSystemApp && b.isSystemApp -> -1
                        else -> collator.compare(a.appName, b.appName)
                    }
                }
            apps = list
            loading = false
        }

        val shown = if (query.isEmpty()) apps else apps.filter {
            it.appName.contains(query, ignoreCase = true) || it.packageName.contains(query, ignoreCase = true)
        }

        Scaffold(
            contentWindowInsets = WindowInsets(0),
            topBar = {
                AppTopBar(
                    title = stringResource(R.string.firewall_title),
                    onBackClick = { finish() },
                    isLoading = loading,
                    isSearchActive = showSearch,
                    searchQuery = query,
                    onSearchQueryChange = { query = it },
                    onSearchClose = { query = ""; showSearch = false },
                    searchPlaceholder = stringResource(R.string.menu_item_search),
                    actions = {
                        if (!showSearch) {
                            androidx.compose.material3.IconButton(onClick = { showSearch = true }) {
                                androidx.compose.material3.Icon(
                                    androidx.compose.ui.res.painterResource(R.drawable.ic_search_24dp),
                                    contentDescription = stringResource(R.string.acc_search)
                                )
                            }
                        }
                    }
                )
            }
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surface) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                stringResource(R.string.firewall_switch),
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Switch(checked = enabled, onCheckedChange = { toggle(it) })
                        }
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                stringResource(R.string.firewall_with_v2ray),
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Switch(checked = withV2ray, onCheckedChange = {
                                withV2ray = it
                                FirewallPrefs.setWithV2ray(it)
                            })
                        }
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                stringResource(R.string.firewall_allow_only),
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Switch(checked = allowOnly, onCheckedChange = {
                                allowOnly = it
                                FirewallPrefs.setAllowOnly(it)
                                // Mode mandiri: terapkan ulang. Mode v2ray: filter membaca sendiri.
                                if (enabled) FirewallService.start(this@FirewallActivity)
                            })
                        }
                        Text(
                            stringResource(R.string.firewall_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                AppDivider()
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().verticalScrollbar(listState),
                    contentPadding = NavigationBarsBottomPadding()
                ) {
                    items(items = shown, key = { it.packageName }) { app ->
                        AppListItem(
                            appName = app.appName,
                            packageName = app.packageName,
                            icon = null,
                            checked = app.packageName in blocked,
                            onCheckedChange = { checked ->
                                blocked = if (checked) blocked + app.packageName else blocked - app.packageName
                                FirewallPrefs.setBlocked(blocked)
                                // Mode mandiri: muat ulang aturan. Mode v2ray: filter membaca daftar sendiri.
                                if (enabled) FirewallService.start(this@FirewallActivity)
                            }
                        )
                        ItemDivider()
                    }
                }
            }
        }
    }
}
