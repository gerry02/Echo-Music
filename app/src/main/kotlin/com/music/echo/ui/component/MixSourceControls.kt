package echo.music.iad1tya.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
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
 * "Mix" chip for a playlist, album or radio header: songs played from it are DJ-mixed into each
 * other (beat-matched when their tempos are close). Remembered per source; off by default.
 */
@Composable
fun MixSourceChip(sourceKey: String, modifier: Modifier = Modifier) {
  val playerConnection = LocalPlayerConnection.current
  val database = LocalDatabase.current
  val source = rememberMixSource(sourceKey)
  val on = source?.mix == true
  FilterChip(
    selected = on,
    onClick = { setMix(playerConnection, database, sourceKey, source, mix = !on) },
    label = { Text(stringResource(R.string.mix_source)) },
    leadingIcon = {
      Icon(
        painter = painterResource(R.drawable.graphic_eq),
        contentDescription = null,
        modifier = Modifier.size(FilterChipDefaults.IconSize),
      )
    },
    modifier = modifier,
  )
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
