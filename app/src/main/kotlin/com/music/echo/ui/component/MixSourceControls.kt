package echo.music.iad1tya.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import echo.music.iad1tya.LocalPlayerConnection
import echo.music.iad1tya.R
import echo.music.iad1tya.constants.AutomixPickBestNextKey
import echo.music.iad1tya.utils.rememberPreference

/**
 * "Mix", an action like Play and Shuffle: starts the playlist, album or radio ([onPlay]) DJ-mixed,
 * for this listening only. Asks first whether to also pick the best-mixing next song, unless the
 * settings say always.
 */
@Composable
fun MixPlayButton(modifier: Modifier = Modifier, onPlay: () -> Unit) {
  val playerConnection = LocalPlayerConnection.current ?: return
  val alwaysPickBest by rememberPreference(AutomixPickBestNextKey, false)
  var asking by remember { mutableStateOf(false) }
  fun start(pickBest: Boolean) {
    playerConnection.service.startMixSession(pickBest)
    onPlay()
  }
  FilledTonalButton(
    onClick = { if (alwaysPickBest) start(true) else asking = true },
    modifier = modifier.height(48.dp),
  ) {
    Icon(painterResource(R.drawable.graphic_eq), contentDescription = null, modifier = Modifier.size(20.dp))
    Spacer(Modifier.size(8.dp))
    Text(stringResource(R.string.mix_source), style = MaterialTheme.typography.labelLarge)
  }
  if (asking) {
    AlertDialog(
      onDismissRequest = { asking = false },
      icon = { Icon(painterResource(R.drawable.graphic_eq), contentDescription = null) },
      title = { Text(stringResource(R.string.mix_source)) },
      text = { Text(stringResource(R.string.mix_ask_pick_best)) },
      confirmButton = {
        TextButton(
          onClick = {
            asking = false
            start(true)
          }
        ) { Text(stringResource(R.string.mix_ask_pick_best_yes)) }
      },
      dismissButton = {
        TextButton(
          onClick = {
            asking = false
            start(false)
          }
        ) { Text(stringResource(R.string.mix_ask_pick_best_no)) }
      },
    )
  }
}

/** The mix switches for the queue playing now, in the queue sheet (for this listening only). */
@Composable
fun MixQueueSwitches(modifier: Modifier = Modifier) {
  val playerConnection = LocalPlayerConnection.current ?: return
  val on by playerConnection.service.mixOn.collectAsState()
  val pickBest by playerConnection.service.mixPickBest.collectAsState()
  Column(modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
      Icon(painter = painterResource(R.drawable.graphic_eq), contentDescription = null, modifier = Modifier.size(22.dp))
      Spacer(Modifier.size(12.dp))
      Column(modifier = Modifier.weight(1f)) {
        Text(stringResource(R.string.mix_source), style = MaterialTheme.typography.titleSmall)
        Text(
          stringResource(R.string.mix_source_desc),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      Switch(checked = on, onCheckedChange = { playerConnection.service.setMixForCurrentQueue(it) })
    }
    if (on) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
      ) {
        Spacer(Modifier.size(22.dp))
        Column(modifier = Modifier.weight(1f)) {
          Text(stringResource(R.string.automix_pick_best_next), style = MaterialTheme.typography.titleSmall)
          Text(
            stringResource(R.string.automix_pick_best_next_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        Switch(checked = pickBest, onCheckedChange = { playerConnection.service.setPickBestForCurrentQueue(it) })
      }
    }
  }
}
