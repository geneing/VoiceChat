package com.voicechat.agent.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.voicechat.agent.AppInfo
import com.voicechat.agent.R
import com.voicechat.agent.ui.theme.VoiceAgentTheme

/**
 * Root of the Compose tree.
 *
 * It is intentionally a static placeholder: the conversation UI, live transcript,
 * manual composer, and voice controls arrive in M06 and later milestones, each
 * bound to a state holder rather than to a speech SDK. Keeping this composable
 * feature-free is what makes the scaffold reviewable.
 */
@Composable
fun VoiceAgentRoot(modifier: Modifier = Modifier) {
    Scaffold(modifier = modifier.fillMaxSize()) { innerPadding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineMedium,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.scaffold_status, AppInfo.MIN_SUPPORTED_API),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun VoiceAgentRootPreview() {
    VoiceAgentTheme {
        VoiceAgentRoot()
    }
}
