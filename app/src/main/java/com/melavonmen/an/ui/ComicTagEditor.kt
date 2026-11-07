package com.melavonmen.an.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.melavonmen.an.data.LibraryRepository

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ComicTagEditor(comicId: String, tags: List<String>) {
    var currentTags by remember(comicId, tags) { mutableStateOf(tags) }
    var newTag by remember(comicId) { mutableStateOf("") }

    Column(Modifier.fillMaxWidth()) {
        Text("标签", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
        GlassSurface(
            modifier = Modifier.fillMaxWidth().padding(top = 9.dp),
            shape = RoundedCornerShape(18.dp)
        ) {
            Column(Modifier.fillMaxWidth().padding(12.dp)) {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                    verticalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    currentTags.forEach { tag ->
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant
                        ) {
                            Text(
                                tag,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp)
                            )
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = newTag,
                        onValueChange = { newTag = it.take(64) },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        label = { Text("新增自定义标签") }
                    )
                    Button(
                        enabled = newTag.trim().isNotEmpty(),
                        onClick = {
                            currentTags = LibraryRepository.addUserTag(comicId, newTag)
                            newTag = ""
                        }
                    ) { Text("添加") }
                }
            }
        }
    }
}
