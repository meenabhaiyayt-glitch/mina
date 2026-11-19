package com.meenai.mina.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meenai.mina.R
import com.meenai.mina.offline.NeedleRuntime
import com.meenai.mina.ui.components.GroupCard
import com.meenai.mina.ui.components.GroupLabel
import com.meenai.mina.ui.theme.MinaTheme

/** 设置 › 模型:Needle (offline) 卡片。模型下载一次;net na ho tab chhote kaam isi pe jate hain. */
@Composable
fun NeedleCard() {
    val context = LocalContext.current
    val c = MinaTheme.colors
    var downloaded by remember { mutableStateOf(false) }
    var enabled by remember { mutableStateOf(false) }
    val downloading by NeedleRuntime.downloading.collectAsState()
    LaunchedEffect(Unit) {
        downloaded = NeedleRuntime.downloaded(context)
        enabled = NeedleRuntime.enabled(context)
    }
    LaunchedEffect(downloading) {
        if (!downloading) downloaded = NeedleRuntime.downloaded(context)
    }
    Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        GroupLabel(stringResource(R.string.needle_title))
        GroupCard {
            Text(
                stringResource(R.string.needle_card_desc),
                Modifier.padding(horizontal = 16.dp).padding(top = 12.dp),
                fontSize = 13.sp, lineHeight = 20.sp, color = c.ink2,
            )
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(
                        when {
                            downloading -> R.string.needle_downloading
                            downloaded -> R.string.needle_ready
                            else -> R.string.needle_download
                        },
                    ),
                    Modifier.weight(1f), fontSize = 13.sp, color = c.accent, fontWeight = FontWeight.Medium,
                )
                Switch(
                    checked = enabled && downloaded,
                    onCheckedChange = { on ->
                        if (!downloaded || downloading) return@Switch
                        enabled = on
                        NeedleRuntime.setEnabled(context, on)
                    },
                )
            }
            if (!downloaded) {
                TextButton(
                    { NeedleRuntime.startDownload(context) },
                    Modifier.padding(start = 8.dp, bottom = 4.dp),
                ) {
                    Text(stringResource(R.string.needle_download), color = c.accent, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}
