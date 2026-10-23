package com.rbmr.taigasleep.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Sensors
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.rbmr.taigasleep.R
import com.rbmr.taigasleep.ui.LocalComingSoon
import com.rbmr.taigasleep.ui.components.ScreenHeader
import com.rbmr.taigasleep.ui.components.TaigaCard

@Composable
fun HomeScreen() {
  val comingSoon = LocalComingSoon.current
  Box(
      modifier =
          Modifier.fillMaxSize()
              .background(
                  Brush.verticalGradient(
                      colors =
                          listOf(
                              MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
                              MaterialTheme.colorScheme.background,
                          ),
                      endY = 700f,
                  )
              )
  ) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      item {
        ScreenHeader(
            kicker = stringResource(R.string.home_kicker),
            title = stringResource(R.string.home_title),
            subtitle = stringResource(R.string.home_subtitle),
        )
      }

      item {
        TaigaCard {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier =
                    Modifier.size(48.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
              Icon(
                  imageVector = Icons.Outlined.Sensors,
                  contentDescription = null,
                  tint = MaterialTheme.colorScheme.onPrimaryContainer,
              )
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
              Text(
                  text = stringResource(R.string.home_sensor_title),
                  style = MaterialTheme.typography.titleLarge,
              )
              Text(
                  text = stringResource(R.string.home_sensor_body),
                  style = MaterialTheme.typography.bodyMedium,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }
          Spacer(Modifier.height(16.dp))
          Button(
              onClick = comingSoon,
              modifier = Modifier.fillMaxWidth(),
              shape = RoundedCornerShape(16.dp),
          ) {
            Text(stringResource(R.string.home_connect))
          }
        }
      }

      item {
        TaigaCard {
          Text(
              text = stringResource(R.string.home_readiness_title),
              style = MaterialTheme.typography.titleLarge,
          )
          Spacer(Modifier.height(8.dp))
          Text(
              text = stringResource(R.string.home_readiness_body),
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          Spacer(Modifier.height(16.dp))
          OutlinedButton(
              onClick = comingSoon,
              modifier = Modifier.fillMaxWidth(),
              shape = RoundedCornerShape(16.dp),
          ) {
            Text(stringResource(R.string.home_readiness_action))
          }
        }
      }

      item {
        Button(
            onClick = comingSoon,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = RoundedCornerShape(18.dp),
        ) {
          Text(
              text = stringResource(R.string.home_start),
              style = MaterialTheme.typography.titleMedium,
          )
        }
      }

      item {
        Text(
            text = stringResource(R.string.home_local_note),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
  }
}
