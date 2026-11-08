package com.melavonmen.an.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.melavonmen.an.data.DocumentReaderPreferences

@Composable
internal fun DocumentReaderOptions(
    current: DocumentReaderPreferences,
    isPdf: Boolean,
    onChange: (DocumentReaderPreferences) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("阅读设置") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (!isPdf) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("无缝连续阅读", modifier = Modifier.weight(1f))
                        Switch(checked = current.seamless, onCheckedChange = {
                            onChange(current.copy(seamless = it))
                        })
                    }
                    Text("字体大小 · ${current.fontSizeSp} sp")
                    Slider(
                        value = current.fontSizeSp.toFloat(),
                        onValueChange = { onChange(current.copy(fontSizeSp = it.toInt(), lineHeightSp = maxOf(current.lineHeightSp, it.toInt() + 2))) },
                        valueRange = 12f..36f,
                    )
                    Text("行距 · ${current.lineHeightSp} sp")
                    Slider(
                        value = current.lineHeightSp.toFloat(),
                        onValueChange = { onChange(current.copy(lineHeightSp = it.toInt())) },
                        valueRange = (current.fontSizeSp + 2).toFloat()..64f,
                    )
                    Text("字体")
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf("default" to "系统", "serif" to "衬线", "sans" to "无衬线", "mono" to "等宽").forEach { (key, label) ->
                            TextButton(onClick = { onChange(current.copy(fontFamily = key)) }) {
                                Text((if (current.fontFamily == key) "✓ " else "") + label)
                            }
                        }
                    }
                    Text("段间距 · ${current.paragraphSpacingDp} dp")
                    Slider(
                        value = current.paragraphSpacingDp.toFloat(),
                        onValueChange = { onChange(current.copy(paragraphSpacingDp = it.toInt())) },
                        valueRange = 0f..36f,
                    )
                    Text("左右页边距 · ${current.horizontalMarginDp} dp")
                    Slider(
                        value = current.horizontalMarginDp.toFloat(),
                        onValueChange = { onChange(current.copy(horizontalMarginDp = it.toInt())) },
                        valueRange = 0f..48f,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("深色阅读背景", modifier = Modifier.weight(1f))
                        Switch(checked = current.nightMode, onCheckedChange = {
                            onChange(current.copy(nightMode = it))
                        })
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("PDF 适宽", modifier = Modifier.weight(1f))
                        Switch(checked = current.pdfFitWidth, onCheckedChange = {
                            onChange(current.copy(pdfFitWidth = it))
                        })
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("横屏双页", modifier = Modifier.weight(1f))
                        Switch(checked = current.pdfTwoPage, onCheckedChange = {
                            onChange(current.copy(pdfTwoPage = it))
                        })
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } },
    )
}
