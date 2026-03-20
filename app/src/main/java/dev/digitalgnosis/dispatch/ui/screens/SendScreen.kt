package dev.digitalgnosis.dispatch.ui.screens

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import dev.digitalgnosis.dispatch.ui.components.IncognitoInput
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.digitalgnosis.dispatch.data.ThreadInfo
import dev.digitalgnosis.dispatch.ui.SendDraft
import dev.digitalgnosis.dispatch.ui.components.AgentAvatar
import dev.digitalgnosis.dispatch.ui.viewmodels.SendViewModel
import dev.digitalgnosis.dispatch.util.formatRelativeTime
import dev.digitalgnosis.dispatch.util.isoAgeMs
import timber.log.Timber

/** Sentinel value for "start a new thread" in the thread picker. */
private const val NEW_THREAD_ID = "__new_thread__"

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun SendScreen(
    modifier: Modifier = Modifier,
    viewModel: SendViewModel = hiltViewModel(),
    draft: SendDraft? = null,
    onDismiss: () -> Unit,
) {
    val departments by viewModel.departments.collectAsState()
    val recentThreads by viewModel.recentThreads.collectAsState()
    val isSending by viewModel.isSending.collectAsState()
    val statusText by viewModel.statusText.collectAsState()

    // Handle back button
    BackHandler {
        onDismiss()
    }

    // ... rest of state ...
    var messageText by remember { mutableStateOf(draft?.messageText ?: "") }
    var subjectText by remember { mutableStateOf("") }
    var selectedDepts by remember { mutableStateOf(draft?.selectedDepts ?: emptySet()) }
    var attachedFiles by remember { mutableStateOf(draft?.attachedFiles ?: emptyList()) }
    var invokeAgent by remember { mutableStateOf(draft?.invokeAgent ?: true) }

    // null = no selection yet (will auto-select), NEW_THREAD_ID = explicit new thread, else = thread ID
    var selectedThreadId by remember { mutableStateOf<String?>(draft?.selectedThreadId) }

    // Sync changes back to draft so they survive tab switches
    fun syncDraft() {
        draft?.messageText = messageText
        draft?.selectedDepts = selectedDepts
        draft?.attachedFiles = attachedFiles
        draft?.invokeAgent = invokeAgent
        draft?.selectedThreadId = selectedThreadId
    }

    val context = LocalContext.current

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        if (uris.isNotEmpty()) {
            val resolver = context.contentResolver
            val newFiles = mutableListOf<SendDraft.DraftFile>()
            for (uri in uris) {
                try {
                    val cursor = resolver.query(uri, null, null, null, null)
                    val name = cursor?.use {
                        if (it.moveToFirst()) {
                            val idx = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                            if (idx >= 0) it.getString(idx) else null
                        } else null
                    } ?: uri.lastPathSegment ?: "attachment"

                    val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
                    if (bytes != null) {
                        newFiles.add(SendDraft.DraftFile(name, bytes))
                        Timber.i("SendScreen: attached %s (%d bytes)", name, bytes.size)
                    }
                } catch (e: Exception) {
                    Timber.w(e, "SendScreen: file pick failed for %s: %s", uri, e.message)
                }
            }
            if (newFiles.isNotEmpty()) {
                attachedFiles = attachedFiles + newFiles
                syncDraft()
            } else {
                Toast.makeText(context, "Failed to read files", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Fetch recent threads when department selection changes (single dept only)
    LaunchedEffect(selectedDepts) {
        if (selectedDepts.size == 1) {
            val dept = selectedDepts.first()
            viewModel.loadRecentThreads(dept)
        } else {
            selectedThreadId = NEW_THREAD_ID
        }
    }

    // Auto-select logic for threads
    LaunchedEffect(recentThreads) {
        if (selectedDepts.size == 1) {
            if (selectedThreadId == null || selectedThreadId == NEW_THREAD_ID) {
                val recentThread = recentThreads.firstOrNull { thread ->
                    val age = isoAgeMs(thread.lastActivity)
                    age in 0..24 * 60 * 60 * 1000
                }
                selectedThreadId = recentThread?.threadId ?: NEW_THREAD_ID
                syncDraft()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Compose") },
                navigationIcon = {
                    IconButton(onClick = onDismiss) {
                        Icon(imageVector = Icons.Default.Close, contentDescription = "Cancel")
                    }
                },
                actions = {
                    IconButton(onClick = { filePickerLauncher.launch("*/*") }, enabled = !isSending) {
                        Icon(imageVector = Icons.Default.AttachFile, contentDescription = "Attach")
                    }
                    IconButton(
                        onClick = {
                            viewModel.send(
                                depts = selectedDepts.toList(),
                                message = messageText.trim(),
                                invoke = invokeAgent,
                                threadId = selectedThreadId?.takeIf { it != NEW_THREAD_ID },
                                files = attachedFiles,
                                onSuccess = {
                                    messageText = ""
                                    attachedFiles = emptyList()
                                    selectedThreadId = null
                                    draft?.clearDraft()
                                }
                            )
                        },
                        enabled = !isSending && selectedDepts.isNotEmpty() && messageText.isNotBlank()
                    ) {
                        if (isSending) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Send,
                                contentDescription = "Send",
                                tint = if (selectedDepts.isNotEmpty() && messageText.isNotBlank()) 
                                    MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                            )
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // "To" field
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.Top
            ) {
                Text(
                    text = "To",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp, end = 16.dp)
                )
                FlowRow(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    selectedDepts.forEach { dept ->
                        InputChip(
                            selected = true,
                            onClick = { 
                                selectedDepts = selectedDepts - dept
                                syncDraft()
                            },
                            label = { Text(dept) },
                            avatar = {
                                AgentAvatar(name = dept, size = 24.dp)
                            },
                            trailingIcon = {
                                Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(14.dp))
                            }
                        )
                    }
                    
                    // Add more departments button
                    var showDeptPicker by remember { mutableStateOf(false) }
                    IconButton(onClick = { showDeptPicker = true }, modifier = Modifier.size(32.dp)) {
                        Icon(
                            imageVector = Icons.Default.PersonAdd,
                            contentDescription = "Add department",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    
                    if (showDeptPicker) {
                        DepartmentPickerDialog(
                            allDepartments = departments.map { it.name },
                            selectedDepts = selectedDepts,
                            onSelectionChanged = { 
                                selectedDepts = it
                                syncDraft()
                            },
                            onDismiss = { showDeptPicker = false }
                        )
                    }
                }
            }
            
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), thickness = 0.5.dp)

            // Thread/Subject field
            if (selectedDepts.size == 1) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Thread",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(end = 16.dp)
                    )
                    ThreadPicker(
                        threads = recentThreads,
                        loading = false,
                        selectedThreadId = selectedThreadId ?: NEW_THREAD_ID,
                        onThreadSelected = {
                            selectedThreadId = it
                            syncDraft()
                        }
                    )
                }
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), thickness = 0.5.dp)
            } else {
                // GAP-S1: Keyboard incognito — message content must not feed keyboard suggestions
                IncognitoInput {
                    OutlinedTextField(
                        value = subjectText,
                        onValueChange = { subjectText = it },
                        placeholder = { Text("Subject", style = MaterialTheme.typography.bodyLarge) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color.Transparent,
                            unfocusedBorderColor = Color.Transparent,
                        ),
                        textStyle = MaterialTheme.typography.bodyLarge
                    )
                }
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), thickness = 0.5.dp)
            }

            // Message Body
            // GAP-S1: Keyboard incognito — message content must not feed keyboard suggestions
            IncognitoInput {
                OutlinedTextField(
                    value = messageText,
                    onValueChange = {
                        messageText = it
                        syncDraft()
                    },
                    placeholder = { Text("Compose message", style = MaterialTheme.typography.bodyLarge) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color.Transparent,
                        unfocusedBorderColor = Color.Transparent,
                    ),
                    textStyle = MaterialTheme.typography.bodyLarge
                )
            }

            // Attachments
            if (attachedFiles.isNotEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Attachments",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    attachedFiles.forEachIndexed { index, file ->
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.AttachFile, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = file.name,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                IconButton(
                                    onClick = {
                                        attachedFiles = attachedFiles.toMutableList().also { it.removeAt(index) }
                                        syncDraft()
                                    },
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(14.dp))
                                }
                            }
                        }
                    }
                }
            }
            
            // Wake agent footer
            val isOnlyGemini = selectedDepts.size == 1 && selectedDepts.first().equals("Gemini CLI", ignoreCase = true)
            
            if (!isOnlyGemini) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Wake agent on send",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Switch(
                        checked = invokeAgent,
                        onCheckedChange = { 
                            invokeAgent = it
                            syncDraft()
                        }
                    )
                }
            }
            
            if (statusText != null) {
                Text(
                    text = statusText!!,
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
private fun DepartmentPickerDialog(
    allDepartments: List<String>,
    selectedDepts: Set<String>,
    onSelectionChanged: (Set<String>) -> Unit,
    onDismiss: () -> Unit
) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 400.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(text = "Select Departments", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(16.dp))
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(allDepartments) { dept ->
                        val isSelected = selectedDepts.contains(dept)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    val next = if (isSelected) selectedDepts - dept else selectedDepts + dept
                                    onSelectionChanged(next)
                                }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            AgentAvatar(name = dept, size = 32.dp)
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(text = dept, modifier = Modifier.weight(1f))
                            androidx.compose.material3.Checkbox(checked = isSelected, onCheckedChange = null)
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                    Text("Done")
                }
            }
        }
    }
}

@Composable
private fun ThreadPicker(
    threads: List<ThreadInfo>,
    loading: Boolean,
    selectedThreadId: String,
    onThreadSelected: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedThread = threads.find { it.threadId == selectedThreadId }

    Box {
        Row(
            modifier = Modifier
                .clickable { expanded = true }
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (selectedThreadId == NEW_THREAD_ID) "New Thread" 
                       else selectedThread?.subject?.ifBlank { "Existing Conversation" } ?: "Thread",
                style = MaterialTheme.typography.bodyLarge,
                color = if (selectedThreadId == NEW_THREAD_ID) MaterialTheme.colorScheme.onSurface 
                        else MaterialTheme.colorScheme.primary,
                fontWeight = if (selectedThreadId == NEW_THREAD_ID) FontWeight.Normal else FontWeight.Bold
            )
            Icon(Icons.Default.KeyboardArrowDown, contentDescription = null, modifier = Modifier.size(20.dp))
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            DropdownMenuItem(
                text = { Text("New Thread") },
                onClick = {
                    onThreadSelected(NEW_THREAD_ID)
                    expanded = false
                },
                leadingIcon = { Icon(Icons.Default.Add, contentDescription = null) }
            )
            threads.forEach { thread ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(thread.subject.ifBlank { "Untitled Thread" })
                            Text(
                                text = formatRelativeTime(thread.lastActivity),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    onClick = {
                        onThreadSelected(thread.threadId)
                        expanded = false
                    }
                )
            }
        }
    }
}

