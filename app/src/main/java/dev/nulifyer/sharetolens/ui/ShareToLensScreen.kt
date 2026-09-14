package dev.nulifyer.sharetolens.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeightIn
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.nulifyer.sharetolens.LensUiState
import dev.nulifyer.sharetolens.LensFailure
import dev.nulifyer.sharetolens.R
import dev.nulifyer.sharetolens.SearchOrigin
import dev.nulifyer.sharetolens.ui.theme.ShareToLensTheme

@Composable
internal fun ShareToLensScreen(
    state: LensUiState,
    onChoosePhoto: () -> Unit,
    onTakePhoto: () -> Unit,
    onRetry: () -> Unit,
    onNewSearch: () -> Unit,
    onExit: () -> Unit,
    onOpenLink: (String) -> Unit,
) {
    BackHandler { onExit() }
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        when (state) {
            LensUiState.Initializing -> BusyScreen(
                title = stringResource(R.string.title_initializing),
                message = stringResource(R.string.body_initializing),
                progress = null,
                onCancel = onExit,
            )
            LensUiState.Ready -> ReadyScreen(onChoosePhoto, onTakePhoto)
            is LensUiState.Preparing -> BusyScreen(
                title = stringResource(R.string.title_preparing),
                message = stringResource(R.string.body_preparing),
                progress = null,
                onCancel = onExit,
            )
            is LensUiState.Uploading -> BusyScreen(
                title = stringResource(R.string.title_uploading),
                message = stringResource(R.string.body_uploading),
                progress = state.percent / 100f,
                onCancel = onExit,
            )
            is LensUiState.Retrying -> BusyScreen(
                title = stringResource(R.string.title_retrying),
                message = stringResource(R.string.body_retrying, state.attempt, state.maxAttempts),
                progress = null,
                onCancel = onExit,
            )
            is LensUiState.Results -> LensResultScreen(
                state = state,
                onExit = onExit,
                onNewSearch = onNewSearch,
                onOpenLink = onOpenLink,
            )
            is LensUiState.Error -> ErrorScreen(
                failure = state.failure,
                onRetry = onRetry,
                onChooseAnother = onNewSearch,
                onCancel = onExit,
            )
        }
    }
}

@Composable
private fun ReadyScreen(
    onChoosePhoto: () -> Unit,
    onTakePhoto: () -> Unit,
) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(horizontal = 24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 520.dp)
                .fillMaxWidth()
                .requiredHeightIn(min = maxHeight)
                .verticalScroll(rememberScrollState())
                .padding(vertical = 32.dp),
            horizontalAlignment = Alignment.Start,
            verticalArrangement = Arrangement.Center,
        ) {
            FocusFrame(
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .size(132.dp),
            )
            Spacer(Modifier.height(36.dp))
            Text(
                text = stringResource(R.string.title_ready),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.body_ready),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(32.dp))
            Button(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp),
                onClick = onChoosePhoto,
            ) {
                Icon(painterResource(R.drawable.ic_photo), contentDescription = null)
                Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.action_choose_photo))
            }
            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp),
                onClick = onTakePhoto,
            ) {
                Icon(painterResource(R.drawable.ic_camera), contentDescription = null)
                Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.action_take_photo))
            }
            Spacer(Modifier.height(28.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
            Spacer(Modifier.height(20.dp))
            PrivacyNote()
        }
    }
}

@Composable
private fun PrivacyNote() {
    Row(verticalAlignment = Alignment.Top) {
        Icon(
            painter = painterResource(R.drawable.ic_privacy),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.secondary,
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = stringResource(R.string.privacy_note),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun BusyScreen(
    title: String,
    message: String,
    progress: Float?,
    onCancel: () -> Unit,
) {
    Scaffold(
        topBar = {
            QuietTopBar(
                title = stringResource(R.string.app_name),
                navigationDescription = stringResource(R.string.action_cancel),
                onNavigation = onCancel,
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            FocusFrame(Modifier.size(112.dp))
            Spacer(Modifier.height(32.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = message,
                modifier = Modifier
                    .widthIn(max = 480.dp)
                    .fillMaxWidth(),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(28.dp))
            if (progress == null) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .widthIn(max = 360.dp)
                        .fillMaxWidth(),
                )
            } else {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .widthIn(max = 360.dp)
                        .fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.progress_percent, (progress * 100).toInt()),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(24.dp))
            TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) }
        }
    }
}

@Composable
private fun ErrorScreen(
    failure: LensFailure,
    onRetry: () -> Unit,
    onChooseAnother: () -> Unit,
    onCancel: () -> Unit,
) {
    Scaffold(
        topBar = {
            QuietTopBar(
                title = stringResource(R.string.app_name),
                navigationDescription = stringResource(R.string.action_close),
                onNavigation = onCancel,
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 520.dp)
                    .fillMaxWidth(),
            ) {
                Text(
                    text = stringResource(
                        if (failure == LensFailure.ImageTooLarge) {
                            R.string.title_image_too_large
                        } else {
                            R.string.title_upload_error
                        },
                    ),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = stringResource(
                        if (failure == LensFailure.ImageTooLarge) {
                            R.string.body_image_too_large
                        } else {
                            R.string.body_upload_error
                        },
                    ),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(28.dp))
                if (failure == LensFailure.Generic) {
                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = onRetry,
                    ) { Text(stringResource(R.string.action_try_again)) }
                    Spacer(Modifier.height(8.dp))
                }
                TextButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onChooseAnother,
                ) { Text(stringResource(R.string.action_choose_another)) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QuietTopBar(
    title: String,
    navigationDescription: String,
    onNavigation: () -> Unit,
    actions: @Composable () -> Unit = {},
) {
    TopAppBar(
        title = { Text(title, fontWeight = FontWeight.Medium) },
        navigationIcon = {
            IconButton(onClick = onNavigation) {
                Icon(
                    painter = painterResource(R.drawable.ic_close),
                    contentDescription = navigationDescription,
                )
            }
        },
        actions = { actions() },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.background,
        ),
    )
}

@Composable
internal fun FocusFrame(modifier: Modifier = Modifier) {
    val primary = MaterialTheme.colorScheme.primary
    val accent = MaterialTheme.colorScheme.secondary
    val description = stringResource(R.string.focus_mark_description)
    Canvas(
        modifier = modifier.semantics { contentDescription = description },
    ) {
        val stroke = 5.dp.toPx()
        val arm = size.minDimension * 0.28f
        val inset = stroke
        val right = size.width - inset
        val bottom = size.height - inset
        val style = Stroke(width = stroke, cap = StrokeCap.Round)

        drawLine(primary, Offset(inset, inset + arm), Offset(inset, inset), stroke, StrokeCap.Round)
        drawLine(primary, Offset(inset, inset), Offset(inset + arm, inset), stroke, StrokeCap.Round)
        drawLine(primary, Offset(right - arm, inset), Offset(right, inset), stroke, StrokeCap.Round)
        drawLine(primary, Offset(right, inset), Offset(right, inset + arm), stroke, StrokeCap.Round)
        drawLine(primary, Offset(inset, bottom - arm), Offset(inset, bottom), stroke, StrokeCap.Round)
        drawLine(primary, Offset(inset, bottom), Offset(inset + arm, bottom), stroke, StrokeCap.Round)
        drawLine(primary, Offset(right - arm, bottom), Offset(right, bottom), stroke, StrokeCap.Round)
        drawLine(primary, Offset(right, bottom), Offset(right, bottom - arm), stroke, StrokeCap.Round)
        drawCircle(accent, radius = size.minDimension * 0.13f, style = style)
        drawCircle(accent, radius = size.minDimension * 0.035f)
    }
}

@Preview(name = "Ready light", showBackground = true, widthDp = 393, heightDp = 852)
@Composable
private fun ReadyPreview() {
    ShareToLensTheme(dynamicColor = false) {
        ShareToLensScreen(
            state = LensUiState.Ready,
            onChoosePhoto = {},
            onTakePhoto = {},
            onRetry = {},
            onNewSearch = {},
            onExit = {},
            onOpenLink = {},
        )
    }
}

@Preview(name = "Preparing dark", showBackground = true, widthDp = 393, heightDp = 852)
@Composable
private fun PreparingDarkPreview() {
    ShareToLensTheme(darkTheme = true, dynamicColor = false) {
        ShareToLensScreen(
            state = LensUiState.Preparing(SearchOrigin.Share),
            onChoosePhoto = {},
            onTakePhoto = {},
            onRetry = {},
            onNewSearch = {},
            onExit = {},
            onOpenLink = {},
        )
    }
}
