package com.jarves.mh.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarves.mh.R
import com.jarves.mh.runtime.LocalModelStatus

/** Plain chat with the on-device model: questions get answers, nothing is built or edited. */
@Composable
fun LocalChatScreen(
    chat: LocalChatState,
    models: List<LocalModelStatus>,
    onSelectModel: (String) -> Unit,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    onClear: () -> Unit,
    onRefreshModels: () -> Unit,
) {
    LaunchedEffect(Unit) { onRefreshModels() }
    val downloaded = models.filter { it.downloaded }.map { it.model }
    val current = downloaded.firstOrNull { it.id == chat.modelId } ?: downloaded.firstOrNull()
    var input by rememberSaveable { mutableStateOf("") }
    var pickerOpen by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val lastLength = chat.messages.lastOrNull()?.let { it.text.length + it.thinking.length } ?: 0
    LaunchedEffect(chat.messages.size, lastLength) {
        if (chat.messages.isNotEmpty()) listState.scrollToItem(chat.messages.size - 1, Int.MAX_VALUE / 2)
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.chat_title), fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                Box {
                    TextButton(onClick = { pickerOpen = true }, enabled = downloaded.isNotEmpty() && !chat.busy) {
                        Text(current?.title ?: stringResource(R.string.chat_no_model_short), fontSize = 13.sp)
                    }
                    DropdownMenu(expanded = pickerOpen, onDismissRequest = { pickerOpen = false }) {
                        downloaded.forEach { model ->
                            DropdownMenuItem(text = { Text(model.title) }, onClick = { pickerOpen = false; onSelectModel(model.id) })
                        }
                    }
                }
            }
            TextButton(onClick = onClear, enabled = chat.messages.isNotEmpty() && !chat.busy) {
                Text(stringResource(R.string.chat_clear))
            }
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (chat.messages.isEmpty()) {
                item {
                    Text(
                        stringResource(if (downloaded.isEmpty()) R.string.chat_no_model else R.string.chat_empty),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 14.sp,
                    )
                }
            }
            itemsIndexed(chat.messages) { index, message ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = if (message.fromUser) Arrangement.End else Arrangement.Start) {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = if (message.fromUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.widthIn(max = 340.dp),
                    ) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (message.thinking.isNotBlank()) {
                                Text(
                                    message.thinking.trim(),
                                    fontSize = 12.sp,
                                    fontStyle = FontStyle.Italic,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            when {
                                message.fromUser -> Text(message.text)
                                message.text.isNotBlank() -> MarkdownText(message.text.trim())
                                index == chat.messages.lastIndex && chat.status != null ->
                                    Text(chat.status, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
            chat.error?.let { error ->
                item { Text(error, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
            }
        }
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                placeholder = { Text(stringResource(R.string.chat_hint)) },
                modifier = Modifier.weight(1f),
                maxLines = 5,
            )
            if (chat.busy) {
                IconButton(onClick = onStop) { Icon(Icons.Default.Stop, contentDescription = stringResource(R.string.chat_stop)) }
            } else {
                IconButton(
                    onClick = { onSend(input); input = "" },
                    enabled = input.isNotBlank() && downloaded.isNotEmpty(),
                ) { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(R.string.chat_send)) }
            }
        }
    }
}
