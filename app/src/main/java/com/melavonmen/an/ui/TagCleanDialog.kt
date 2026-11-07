package com.melavonmen.an.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.melavonmen.an.data.ComicEntry
import com.melavonmen.an.data.TagAliasStore
import com.melavonmen.an.data.TagNormalizer
import com.melavonmen.an.data.TagStat
import com.melavonmen.an.data.VariantGroup

/**
 * 合并草稿：确认前仅为草稿状态（仅内存，不持久化）。
 */
internal data class MergeDraft(val selectedVariants: Set<String>, val canonical: String)

/**
 * 合并确认对话框（纯展示 + 回调）。
 * 红线：本函数内不得调用 [TagAliasStore.merge]；合并只允许发生在 [onConfirm] 回调（调用方）内。
 */
@Composable
fun MergeConfirmDialog(
    variants: Set<String>,
    canonical: String,
    affectedCount: Int,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("确认合并标签", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("变体（${variants.size}）：${variants.sorted().joinToString("、")}", fontSize = 12.sp)
                Text("规范名：$canonical", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Text(
                    "影响条目数：$affectedCount 部",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFFF4B2B)
                )
                Text(
                    "合并仅写入本地别名表（不改写原档），确认后可在变体组卡片「撤销」。",
                    fontSize = 11.sp,
                    color = Color(0xFF838A9B)
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("确认合并", fontWeight = FontWeight.Bold, color = Color(0xFFFF4B2B))
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text("取消", color = Color(0xFF838A9B))
            }
        }
    )
}

/**
 * 清洗段：疑似变体组 → 勾选 ≥2 变体 → 单选规范名 → [MergeConfirmDialog] 确认后才合并；
 * 已合并组显示规范名 +「撤销」；标签频次列表 + 搜索。
 */
@Composable
internal fun TagCleanSection(
    comics: List<ComicEntry>,
    stats: List<TagStat>,
    variantGroups: List<VariantGroup>,
    modifier: Modifier = Modifier
) {
    // 别名表变更计数：merge/undo 后 +1，驱动已合并展示刷新
    var aliasVersion by remember { mutableIntStateOf(0) }
    // 各组草稿：normKey -> 勾选的变体；normKey -> 选定的规范名
    val checkedMap = remember { mutableStateMapOf<String, Set<String>>() }
    val canonicalMap = remember { mutableStateMapOf<String, String>() }
    var pendingNormKey by remember { mutableStateOf<String?>(null) }
    var pendingMerge by remember { mutableStateOf<MergeDraft?>(null) }
    var freqQuery by remember { mutableStateOf("") }

    val variantSizeByNormKey = remember(variantGroups) {
        variantGroups.associate { it.normKey to it.variants.size }
    }
    val freqRows = remember(stats, freqQuery) {
        val q = freqQuery.trim()
        val sorted = stats.sortedByDescending { it.count }
        if (q.isEmpty()) {
            sorted
        } else {
            val qk = TagNormalizer.normKey(q)
            sorted.filter { s ->
                s.tag.contains(q, ignoreCase = true) ||
                    (qk.isNotEmpty() && s.normKey.contains(qk, ignoreCase = true))
            }
        }
    }

    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Column {
                Text("疑似变体组", fontSize = 14.sp, fontWeight = FontWeight.Black)
                Text(
                    "归一键相同的不同写法 · 勾选 ≥2 个变体 → 单选规范名 → [合并] · 仅显式确认后写入别名表",
                    fontSize = 11.sp,
                    color = Color(0xFF838A9B),
                    modifier = Modifier.padding(top = 3.dp)
                )
            }
        }
        if (variantGroups.isEmpty()) {
            item {
                Text(
                    "未发现变体组（或全部标签已是唯一形态）",
                    fontSize = 12.sp,
                    color = Color(0xFF838A9B),
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }
        }
        items(variantGroups, key = { it.normKey }) { group ->
            VariantGroupCard(
                group = group,
                aliasVersion = aliasVersion,
                checked = checkedMap[group.normKey] ?: emptySet(),
                canonical = canonicalMap[group.normKey],
                onToggleVariant = { v ->
                    val cur = checkedMap[group.normKey] ?: emptySet()
                    val next = if (v in cur) cur - v else cur + v
                    checkedMap[group.normKey] = next
                    if (v !in next && canonicalMap[group.normKey] == v) {
                        canonicalMap.remove(group.normKey)
                    }
                },
                onPickCanonical = { v -> canonicalMap[group.normKey] = v },
                onMerge = {
                    val sel = checkedMap[group.normKey] ?: emptySet()
                    val canon = canonicalMap[group.normKey]
                    if (sel.size >= 2 && canon != null && canon in sel) {
                        pendingNormKey = group.normKey
                        pendingMerge = MergeDraft(selectedVariants = sel, canonical = canon)
                    }
                },
                onUndo = { canon ->
                    TagAliasStore.undo(canon)
                    aliasVersion++
                }
            )
        }
        item {
            Column(Modifier.padding(top = 12.dp)) {
                Text("标签频次", fontSize = 14.sp, fontWeight = FontWeight.Black)
                Text(
                    "次数为出现总次数，部数按去重条目计 · 可搜索标签或归一键",
                    fontSize = 11.sp,
                    color = Color(0xFF838A9B),
                    modifier = Modifier.padding(top = 3.dp)
                )
            }
        }
        item {
            OutlinedTextField(
                value = freqQuery,
                onValueChange = { freqQuery = it },
                placeholder = { Text("搜索标签 / 归一键…", fontSize = 12.sp) },
                leadingIcon = { Icon(Icons.Default.Search, null, Modifier.size(16.dp)) },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            )
        }
        if (freqRows.isEmpty()) {
            item {
                Text(
                    "没有匹配的标签",
                    fontSize = 12.sp,
                    color = Color(0xFF838A9B),
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }
        }
        items(freqRows, key = { "freq:${it.tag}" }) { s ->
            FrequencyRow(stat = s, variantSize = variantSizeByNormKey[s.normKey] ?: 1)
        }
    }

    val pending = pendingMerge
    if (pending != null) {
        // 影响条目数 = 命中任一待合并变体的条目数（合并涉及的全部条目）
        val affectedCount = remember(pending, comics) {
            comics.count { e -> e.tags.any { it in pending.selectedVariants } }
        }
        MergeConfirmDialog(
            variants = pending.selectedVariants,
            canonical = pending.canonical,
            affectedCount = affectedCount,
            onConfirm = {
                // ============ 红线 ============
                // 全工程唯一 TagAliasStore.merge() 调用点。
                // 仅允许出现在用户于 MergeConfirmDialog 显式点击「确认合并」的回调内；
                // 任何自动合并路径（初始化/LaunchedEffect/组合期等）禁止存在。
                TagAliasStore.merge(canonical = pending.canonical, variants = pending.selectedVariants)
                // ============ 红线结束 ============
                pendingNormKey?.let { nk ->
                    checkedMap.remove(nk)
                    canonicalMap.remove(nk)
                }
                pendingNormKey = null
                pendingMerge = null
                aliasVersion++
            },
            onCancel = {
                // 草稿保留在内存（不持久化），仅关闭弹窗
                pendingMerge = null
            }
        )
    }
}

// ---------- 疑似变体组卡片 ----------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VariantGroupCard(
    group: VariantGroup,
    aliasVersion: Int,
    checked: Set<String>,
    canonical: String?,
    onToggleVariant: (String) -> Unit,
    onPickCanonical: (String) -> Unit,
    onMerge: () -> Unit,
    onUndo: (String) -> Unit
) {
    // 已合并簇 = canonicalOf 归并到同一规范名的变体（≥2 个 = 已发生过合并）
    val clusters = remember(group, aliasVersion) {
        group.variants.keys.groupBy { TagAliasStore.canonicalOf(it) }
    }
    val mergedClusters = clusters.filterValues { it.size >= 2 }
    val unmergedVariants = clusters.filterValues { it.size == 1 }.keys.sorted()

    Column(
        Modifier
            .fillMaxWidth()
            .background(Color(0xFFFBFCFE), RoundedCornerShape(13.dp))
            .border(1.dp, Color(0xFFE6E9F0), RoundedCornerShape(13.dp))
            .padding(12.dp)
    ) {
        Text(
            "归一键：${group.normKey}",
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF838A9B),
            fontFamily = FontFamily.Monospace
        )
        Text(
            "${group.variants.size} 个变体",
            fontSize = 10.sp,
            color = Color(0xFF838A9B),
            modifier = Modifier.padding(top = 2.dp)
        )

        mergedClusters.forEach { (canon, vs) ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .padding(top = 8.dp)
                    .fillMaxWidth()
                    .background(Color(0x1A2BB673), RoundedCornerShape(9.dp))
                    .padding(horizontal = 10.dp, vertical = 7.dp)
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "已合并 → 规范名：$canon",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF2BB673)
                    )
                    Text(
                        "变体：" + vs.sorted().joinToString("、") { v -> "$v ${group.variants[v] ?: 0}次" },
                        fontSize = 10.sp,
                        color = Color(0xFF838A9B),
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                TextButton(onClick = { onUndo(canon) }) {
                    Text("撤销", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFFE0453C))
                }
            }
        }

        if (unmergedVariants.isNotEmpty()) {
            Text(
                "勾选变体（≥2 个可合并）",
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF838A9B),
                modifier = Modifier.padding(top = 8.dp)
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                modifier = Modifier.padding(top = 6.dp)
            ) {
                unmergedVariants.forEach { v ->
                    VariantChip(
                        label = v,
                        count = group.variants[v] ?: 0,
                        checked = v in checked,
                        isCanonical = canonical == v,
                        onToggle = { onToggleVariant(v) }
                    )
                }
            }
        }

        if (checked.size >= 2) {
            Text(
                "单选规范名（必为勾选变体之一）",
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF838A9B),
                modifier = Modifier.padding(top = 10.dp)
            )
            checked.sorted().forEach { v ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPickCanonical(v) }
                        .padding(vertical = 2.dp)
                ) {
                    RadioButton(selected = canonical == v, onClick = { onPickCanonical(v) })
                    Text(v, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(6.dp))
                    Text("${group.variants[v] ?: 0}次", fontSize = 10.sp, color = Color(0xFF838A9B))
                }
            }
            Button(
                onClick = onMerge,
                enabled = canonical != null && canonical in checked,
                shape = RoundedCornerShape(11.dp),
                modifier = Modifier
                    .padding(top = 8.dp)
                    .fillMaxWidth()
            ) {
                Text("合并", fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun VariantChip(
    label: String,
    count: Int,
    checked: Boolean,
    isCanonical: Boolean,
    onToggle: () -> Unit
) {
    Row(
        Modifier
            .background(if (checked) Color(0xFF4A6CF7) else Color.White, RoundedCornerShape(9.dp))
            .border(1.dp, if (checked) Color(0xFF4A6CF7) else Color(0xFFE6E9F0), RoundedCornerShape(9.dp))
            .clickable(onClick = onToggle)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (checked) {
            Icon(Icons.Default.Check, null, Modifier.size(11.dp), tint = Color.White)
            Spacer(Modifier.width(5.dp))
        }
        Text(
            label,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = if (checked) Color.White else Color(0xFF1B1E28),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.width(5.dp))
        Text(
            "${count}次",
            fontSize = 9.5.sp,
            color = if (checked) Color.White.copy(alpha = 0.85f) else Color(0xFF838A9B)
        )
        if (isCanonical) {
            Spacer(Modifier.width(4.dp))
            Text(
                "规范名",
                fontSize = 8.5.sp,
                fontWeight = FontWeight.Black,
                color = Color(0xFFFF4B2B),
                modifier = Modifier
                    .background(Color(0x19FF4B2B), RoundedCornerShape(5.dp))
                    .padding(horizontal = 4.dp, vertical = 1.dp)
            )
        }
    }
}

// ---------- 标签频次列表 ----------

@Composable
private fun FrequencyRow(stat: TagStat, variantSize: Int) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stat.tag,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (variantSize >= 2) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "变体×$variantSize",
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFE09A1F),
                        modifier = Modifier
                            .background(Color(0x22E09A1F), RoundedCornerShape(6.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
            Text(
                "归一键：${stat.normKey}",
                fontSize = 9.5.sp,
                color = Color(0xFF838A9B),
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            "${stat.count}次 · ${stat.entryCount}部",
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF5A6070)
        )
    }
}
