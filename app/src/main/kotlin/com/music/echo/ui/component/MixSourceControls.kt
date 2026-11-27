package echo.music.iad1tya.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import echo.music.iad1tya.LocalDatabase
import echo.music.iad1tya.LocalPlayerConnection
import echo.music.iad1tya.R
import echo.music.iad1tya.db.entities.MixSourceEntity
import kotlinx.coroutines.flow.flowOf

/** The mix setting of [sourceKey] as it is now, null when never set (mix off). */
@Composable
private fun rememberMixSource(sourceKey: String): MixSourceEntity? {
  val playerConnection = LocalPlayerConnection.current
  if (sourceKey.startsWith("session:")) {
    // A queue of no lasting source: its setting lives in the player, for the queue playing now.
    val current by (playerConnection?.service?.currentMixSource ?: kotlinx.coroutines.flow.MutableStateFlow<MixSourceEntity?>(null)).collectAsState()
    return current?.takeIf { it.sourceKey == sourceKey }
  }
  val database = LocalDatabase.current
  val flow = remember(sourceKey) { database.mixSource(sourceKey) }
  val source by flow.collectAsState(initial = null)
  return source
}

private fun setMix(
  playerConnection: echo.music.iad1tya.playback.PlayerConnection?,
  database: echo.music.iad1tya.db.MusicDatabase,
  key: String,
  old: MixSourceEntity?,
  mix: Boolean? = null,
  pickBestNext: Boolean? = null,
) {
  if (playerConnection != null) {
    playerConnection.service.setMixSource(key, mix, pickBestNext)
    return
  }
  database.query {
    upsert(
      MixSourceEntity(
        sourceKey = key,
        mix = mix ?: old?.mix ?: false,
        pickBestNext = pickBestNext ?: old?.pickBestNext ?: false,
      )
    )
  }
}

/**
 * "Mix" button for a playlist, album or radio page, the same everywhere: songs played from it are
 * DJ-mixed into each other (beat-matched when their tempos are close). Remembered per source; off
 * by default. Turning it on asks whether to also pick the best-mixing next song (unless settings
 * say always), then starts playing the source ([onPlay]) in mix mode.
 */
@Composable
fun MixSourceChip(sourceKey: String, modifier: Modifier = Modifier, onPlay: (() -> Unit)? = null) {
  val playerConnection = LocalPlayerConnection.current
  val database = LocalDatabase.current
  val source = rememberMixSource(sourceKey)
  val on = source?.mix == true
  val alwaysPickBest by
    echo.music.iad1tya.utils.rememberPreference(echo.music.iad1tya.constants.AutomixPickBestNextKey, false)
  var asking by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
  fun turnOn(pickBest: Boolean) {
    setMix(playerConnection, database, sourceKey, source, mix = true, pickBestNext = pickBest)
    onPlay?.invoke()
  }
  androidx.compose.material3.FilledTonalButton(
    onClick = {
      when {
        on -> setMix(playerConnection, database, sourceKey, source, mix = false)
        alwaysPickBest -> turnOn(true)
        else -> asking = true
      }
    },
    colors =
      if (on) {
        androidx.compose.material3.ButtonDefaults.filledTonalButtonColors(
          containerColor = MaterialTheme.colorScheme.primary,
          contentColor = MaterialTheme.colorScheme.onPrimary,
        )
      } else {
        androidx.compose.material3.ButtonDefaults.filledTonalButtonColors()
      },
    modifier = modifier.height(40.dp),
  ) {
    Icon(
      painter = painterResource(if (on) R.drawable.check else R.drawable.graphic_eq),
      contentDescription = null,
      modifier = Modifier.size(18.dp),
    )
    Spacer(Modifier.size(8.dp))
    Text(stringResource(R.string.mix_source), style = MaterialTheme.typography.labelLarge)
  }
  if (asking) {
    androidx.compose.material3.AlertDialog(
      onDismissRequest = { asking = false },
      icon = { Icon(painterResource(R.drawable.graphic_eq), contentDescription = null) },
      title = { Text(stringResource(R.string.mix_source)) },
      text = { Text(stringResource(R.string.mix_ask_pick_best)) },
      confirmButton = {
        androidx.compose.material3.TextButton(
          onClick = {
            asking = false
            turnOn(true)
          }
        ) { Text(stringResource(R.string.mix_ask_pick_best_yes)) }
      },
      dismissButton = {
        androidx.compose.material3.TextButton(
          onClick = {
            asking = false
            turnOn(false)
          }
        ) { Text(stringResource(R.string.mix_ask_pick_best_no)) }
      },
    )
  }
}

/** The mix switches for the source playing now, for the queue sheet. */
@Composable
fun MixSourceSwitches(sourceKey: String, modifier: Modifier = Modifier) {
  val playerConnection = LocalPlayerConnection.current
  val database = LocalDatabase.current
  val source = rememberMixSource(sourceKey)
  val on = source?.mix == true
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
      Switch(checked = on, onCheckedChange = { setMix(playerConnection, database, sourceKey, source, mix = it) })
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
        Switch(
          checked = source?.pickBestNext == true,
          onCheckedChange = { setMix(playerConnection, database, sourceKey, source, pickBestNext = it) },
        )
      }
    }
  }
}
