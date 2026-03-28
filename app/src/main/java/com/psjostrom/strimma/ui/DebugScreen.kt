package com.psjostrom.strimma.ui

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.psjostrom.strimma.R
import com.psjostrom.strimma.receiver.DebugLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugScreen(onBack: () -> Unit) {
    val liveEntries by DebugLog.entries.collectAsState()
    var fileEntries by remember { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(Unit) {
        fileEntries = withContext(Dispatchers.IO) { DebugLog.readLogFiles() }
    }
    val context = LocalContext.current
    val shareChooserTitle = stringResource(R.string.debug_share_chooser)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.debug_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_content_desc_back))
                    }
                },
                actions = {
                    IconButton(onClick = {
                        val logFile = DebugLog.currentLogFile() ?: return@IconButton
                        val uri = FileProvider.getUriForFile(
                            context, "${context.packageName}.fileprovider", logFile
                        )
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(Intent.createChooser(intent, shareChooserTitle))
                    }) {
                        Icon(Icons.Default.Share, contentDescription = stringResource(R.string.common_content_desc_share_log))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        val entries = if (liveEntries.isNotEmpty()) {
            listOf(stringResource(R.string.debug_live_marker)) + liveEntries.reversed()
        } else emptyList()
        val allEntries = entries + fileEntries

        if (allEntries.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 12.dp)
            ) {
                Text(stringResource(R.string.debug_empty), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 12.dp)
            ) {
                items(allEntries) { entry ->
                    Text(
                        text = entry,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(vertical = 2.dp)
                    )
                }
            }
        }
    }
}
