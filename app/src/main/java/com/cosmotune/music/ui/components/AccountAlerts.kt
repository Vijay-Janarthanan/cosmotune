package com.cosmotune.music.ui.components

import com.cosmotune.music.R

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cosmotune.music.data.settings.AppSettings
import com.cosmotune.ui.sheets.CosmoDialogBody
import com.cosmotune.ui.sheets.CosmoDialogCard
import com.cosmotune.ui.sheets.CosmoDialogQuietButton
import androidx.compose.foundation.layout.Arrangement
import kotlinx.coroutines.launch
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.materials.HazeMaterials

/**
 * The token alert in Orbit: the dialog card ([AlertScaffold]) with Orbit's body, the question, a line, the field and Save as the one
 * peach action beside a quiet Cancel.
 */
@Composable
fun ListenBrainzTokenAlert(
    hazeState: HazeState,
    tokenInput: String,
    onTokenInputChange: (String) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertScaffold(hazeState = hazeState, onDismiss = onDismiss) {
        CosmoDialogBody(
            overline = null,
            title = stringResource(R.string.listenbrainz_token),
            message = stringResource(R.string.listenbrainz_token_description),
            confirmLabel = stringResource(R.string.save),
            onConfirm = onSave,
            dismissLabel = stringResource(R.string.cancel),
            onDismiss = onDismiss,
        ) {
            PillTextField(
                value = tokenInput,
                onValueChange = onTokenInputChange,
                placeholder = stringResource(R.string.api_token),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onSave() }),
            )
        }
    }
}

@Composable
fun LastfmLoginAlert(
    hazeState: HazeState,
    usernameInput: String,
    onUsernameInputChange: (String) -> Unit,
    passwordInput: String,
    onPasswordInputChange: (String) -> Unit,
    error: String?,
    loading: Boolean,
    onSignIn: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertScaffold(hazeState = hazeState, onDismiss = onDismiss) {
        CosmoDialogBody(
            overline = null,
            title = stringResource(R.string.lastfm_login),
            message = null,
            confirmLabel = if (loading) stringResource(R.string.signing_in) else stringResource(R.string.sign_in),
            onConfirm = onSignIn,
            confirmEnabled = !loading && usernameInput.isNotBlank() && passwordInput.isNotBlank(),
            dismissLabel = stringResource(R.string.cancel),
            onDismiss = onDismiss,
            dismissEnabled = !loading,
        ) {
            AlertLine(error ?: stringResource(R.string.lastfm_login_description), isError = error != null)
            PillTextField(
                value = usernameInput,
                onValueChange = onUsernameInputChange,
                placeholder = stringResource(R.string.username),
                enabled = !loading,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            )
            Spacer(Modifier.height(8.dp))
            PillTextField(
                value = passwordInput,
                onValueChange = onPasswordInputChange,
                placeholder = stringResource(R.string.password),
                enabled = !loading,
                isPassword = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(
                    onDone = { if (usernameInput.isNotBlank() && passwordInput.isNotBlank()) onSignIn() },
                ),
            )
        }
    }
}

/**
 * Manual token entry, for when the in-app login can't run — a WebView an OEM
 * has broken, or a token lifted from a desktop client.
 *
 * [error] carries back what the verification attempt said, because a token that
 * was mistyped or has expired is indistinguishable from one that works until
 * Discord is asked about it.
 */
@Composable
fun DiscordTokenAlert(
    hazeState: HazeState,
    tokenInput: String,
    onTokenInputChange: (String) -> Unit,
    error: String?,
    loading: Boolean,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertScaffold(hazeState = hazeState, onDismiss = onDismiss) {
        CosmoDialogBody(
            overline = null,
            title = stringResource(R.string.discord_token),
            message = null,
            confirmLabel = if (loading) stringResource(R.string.checking) else stringResource(R.string.save),
            onConfirm = onSave,
            confirmEnabled = !loading && tokenInput.isNotBlank(),
            dismissLabel = stringResource(R.string.cancel),
            onDismiss = onDismiss,
            dismissEnabled = !loading,
        ) {
            AlertLine(error ?: stringResource(R.string.discord_token_description), isError = error != null)
            PillTextField(
                value = tokenInput,
                onValueChange = onTokenInputChange,
                placeholder = stringResource(R.string.token),
                enabled = !loading,
                isPassword = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (tokenInput.isNotBlank()) onSave() }),
            )
        }
    }
}

/**
 * One free-text presence field — an activity name, a button label.
 *
 * [message] is where the caller explains the field, including which `{...}`
 * variables it accepts, since that is the only place a user would find out.
 */
@Composable
fun TextValueAlert(
    hazeState: HazeState,
    title: String,
    message: String,
    placeholder: String,
    value: String,
    onValueChange: (String) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
    /** False greys Save out — for a field that isn't worth saving empty. */
    saveEnabled: Boolean = true,
    /** A further, quiet action under the field, for a value that can be cleared. */
    onRemove: (() -> Unit)? = null,
) {
    AlertScaffold(hazeState = hazeState, onDismiss = onDismiss) {
        CosmoDialogBody(
            overline = null,
            title = title,
            message = message,
            confirmLabel = stringResource(R.string.save),
            onConfirm = onSave,
            confirmEnabled = saveEnabled,
            dismissLabel = stringResource(R.string.cancel),
            onDismiss = onDismiss,
        ) {
            PillTextField(
                value = value,
                onValueChange = onValueChange,
                placeholder = placeholder,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onSave() }),
            )
            if (onRemove != null) {
                CosmoDialogQuietButton(stringResource(R.string.remove), onRemove, Modifier.padding(top = 12.dp))
            }
        }
    }
}

/**
 * One field of a server editor: the value, its change handler, the hint, and
 * how the keyboard behaves on it. The last field always gets Done-to-save
 * while the rest advance with Next, which is what every editor below already
 * did by hand.
 */
data class EditorField(
    val value: String,
    val onChange: (String) -> Unit,
    val placeholder: String,
    val keyboardType: KeyboardType = KeyboardType.Text,
    val isPassword: Boolean = false,
)

/**
 * The shape every server editor in this app shares: the title, the last test result in place of the description, the fields, then
 * Test (and Remove, for an entry that can be removed) as quiet buttons under them, and Save as the one peach action beside Cancel.
 */
@Composable
fun ServerEditorAlert(
    hazeState: HazeState,
    title: String,
    description: String,
    fields: List<EditorField>,
    /** What the last test said, or null before one has been run. */
    status: String?,
    statusIsGood: Boolean,
    testing: Boolean,
    /** Whether there is enough typed in to be worth testing or saving. */
    canSubmit: Boolean,
    onTest: () -> Unit,
    onSave: () -> Unit,
    removeLabel: String? = null,
    /** Offered only when there is something stored to remove. */
    onRemove: (() -> Unit)? = null,
    onDismiss: () -> Unit,
) {
    AlertScaffold(hazeState = hazeState, onDismiss = { if (!testing) onDismiss() }) {
        CosmoDialogBody(
            overline = null,
            title = title,
            message = null,
            confirmLabel = stringResource(R.string.save),
            onConfirm = onSave,
            confirmEnabled = canSubmit && !testing,
            dismissLabel = stringResource(R.string.cancel),
            onDismiss = onDismiss,
            dismissEnabled = !testing,
        ) {
            AlertLine(status ?: description, isError = status != null && !statusIsGood, isGood = status != null && statusIsGood)
            fields.forEachIndexed { index, field ->
                if (index > 0) Spacer(Modifier.height(8.dp))
                PillTextField(
                    value = field.value,
                    onValueChange = field.onChange,
                    placeholder = field.placeholder,
                    enabled = !testing,
                    isPassword = field.isPassword,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = field.keyboardType,
                        imeAction = if (index == fields.lastIndex) ImeAction.Done else ImeAction.Next,
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = { if (canSubmit && !testing) onSave() },
                    ),
                )
            }
            // Test is worth running before Save; it and Remove are quiet, so Save stays the one peach action.
            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CosmoDialogQuietButton(
                    label = if (testing) stringResource(R.string.testing) else stringResource(R.string.test),
                    onClick = onTest,
                    enabled = canSubmit && !testing,
                )
                if (onRemove != null && removeLabel != null) {
                    CosmoDialogQuietButton(label = removeLabel, onClick = onRemove, enabled = !testing)
                }
            }
        }
    }
}

/**
 * One field of a hosted server editor: the saved value it opens with, the
 * hint, and how the keyboard behaves on it.
 */
data class FieldConfig(
    val initial: String,
    val placeholder: String,
    val keyboardType: KeyboardType = KeyboardType.Text,
    val isPassword: Boolean = false,
)

/**
 * A server editor that owns its own state: field values, the last test
 * result and the testing flag. The caller hands over fixed field configs
 * plus what test and save mean for those values, and gets back the same
 * card as [ServerEditorAlert] — without the dozen remembered states
 * each host otherwise repeats.
 *
 * The configs are fixed per call site, so remembering once per opening is
 * sound; the host leaves composition on dismiss, which is when the next
 * opening starts over from the saved values.
 */
@Composable
fun ServerEditorHost(
    hazeState: HazeState,
    title: String,
    description: String,
    fields: List<FieldConfig>,
    canSubmit: (List<String>) -> Boolean,
    testFailedRes: Int,
    onTest: suspend (List<String>) -> Result<Unit>,
    onSave: (List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val states = remember { fields.map { mutableStateOf(it.initial) } }
    var status by remember { mutableStateOf<String?>(null) }
    var statusIsGood by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    val values = states.map { it.value }
    ServerEditorAlert(
        hazeState = hazeState,
        title = title,
        description = description,
        fields = fields.mapIndexed { index, field ->
            EditorField(
                value = states[index].value,
                onChange = { states[index].value = it; status = null },
                placeholder = field.placeholder,
                keyboardType = field.keyboardType,
                isPassword = field.isPassword,
            )
        },
        status = status,
        statusIsGood = statusIsGood,
        testing = testing,
        canSubmit = canSubmit(values),
        onTest = {
            testing = true
            status = null
            scope.launch {
                onTest(values).fold(
                    onSuccess = {
                        status = context.getString(R.string.connected)
                        statusIsGood = true
                    },
                    onFailure = {
                        status = context.getString(
                            testFailedRes,
                            it.message ?: context.getString(R.string.failed),
                        )
                        statusIsGood = false
                    },
                )
                testing = false
            }
        },
        onSave = { onSave(values) },
        onDismiss = onDismiss,
    )
}

/**
 * Add or edit a source that has an address — the addon editor.
 *
 * One field, Test and Remove as quiet buttons and Save as the one action. There is deliberately no name
 * field: an addon states its own name in its manifest, so asking the user to
 * invent one is asking for information the addon is about to supply anyway —
 * and a blank field would leave the row showing a bare hostname next to a
 * perfectly good published name.
 *
 * [status] is the one thing here that no other alert in the file needs:
 * testing an address has *three* outcomes rather than the usual two, and "it
 * answered, but not with something this app can use" is the one worth reading
 * — so a result replaces the description in place, coloured by [statusIsGood],
 * the way [LastfmLoginAlert] surfaces a failed sign-in.
 */
@Composable
fun AddonEditorAlert(
    hazeState: HazeState,
    title: String,
    description: String,
    urlValue: String,
    onUrlChange: (String) -> Unit,
    urlPlaceholder: String,
    /** What the last test said, or null before one has been run. */
    status: String?,
    statusIsGood: Boolean,
    testing: Boolean,
    /** Whether there is enough typed in to be worth testing or saving. */
    canSubmit: Boolean,
    onTest: () -> Unit,
    onSave: () -> Unit,
    /** Offered only for a source already stored — there is nothing to remove otherwise. */
    onRemove: (() -> Unit)?,
    /**
     * What [onRemove] is called, for the callers that are not removing a source.
     *
     * The party server's address wears this same card — one field, an address
     * to test — and "Remove source" would be the one line on it still talking about addons.
     */
    removeLabel: String? = null,
    onDismiss: () -> Unit,
) {
    ServerEditorAlert(
        hazeState = hazeState,
        title = title,
        description = description,
        fields = listOf(
            EditorField(
                value = urlValue,
                onChange = onUrlChange,
                placeholder = urlPlaceholder,
                keyboardType = KeyboardType.Uri,
            ),
        ),
        status = status,
        statusIsGood = statusIsGood,
        testing = testing,
        canSubmit = canSubmit,
        onTest = onTest,
        onSave = onSave,
        removeLabel = removeLabel ?: stringResource(R.string.remove_source),
        onRemove = onRemove,
        onDismiss = onDismiss,
    )
}

/**
 * A name clash mid-upload: the server already holds a file under the name a
 * track would land as.
 *
 * Three outcomes rather than a yes/no — overwriting destroys the server copy, so the non-destructive answer, Keep both, is the
 * peach action, Skip the quiet one beside it and Overwrite a quiet button over them. [showApplyToAll] is only true mid-batch, where
 * one answer can carry every clash still queued behind this one.
 */
@Composable
fun WebDavConflictAlert(
    hazeState: HazeState,
    fileName: String,
    showApplyToAll: Boolean,
    applyToAll: Boolean,
    onApplyToAllChange: (Boolean) -> Unit,
    onOverwrite: () -> Unit,
    onKeepBoth: () -> Unit,
    onSkip: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertScaffold(hazeState = hazeState, onDismiss = onDismiss) {
        CosmoDialogBody(
            overline = null,
            title = stringResource(R.string.webdav_conflict_title, fileName),
            message = stringResource(R.string.webdav_conflict_message),
            confirmLabel = stringResource(R.string.webdav_keep_both),
            onConfirm = onKeepBoth,
            dismissLabel = stringResource(R.string.webdav_skip),
            onDismiss = onSkip,
        ) {
            CosmoDialogQuietButton(stringResource(R.string.webdav_overwrite), onOverwrite)
            if (showApplyToAll) {
                ChoiceRow(
                    label = stringResource(R.string.webdav_apply_to_all),
                    detail = null,
                    checked = applyToAll,
                    onClick = { onApplyToAllChange(!applyToAll) },
                )
            }
        }
    }
}

/**
 * A two-action confirmation in Orbit's dialog body. Kept here so warnings opened from settings do not fall back
 * to a visually unrelated Material dialog.
 */
@Composable
fun ConfirmationAlert(
    hazeState: HazeState,
    title: String,
    description: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertScaffold(hazeState = hazeState, onDismiss = onDismiss) {
        CosmoDialogBody(
            overline = null,
            title = title,
            message = description,
            confirmLabel = confirmLabel,
            onConfirm = onConfirm,
            dismissLabel = stringResource(R.string.cancel),
            onDismiss = onDismiss,
        )
    }
}

/**
 * Single-select list, ticked like [LyricsSourcesDialog] rather than with radio
 * buttons.
 *
 * Picking commits immediately and closes, so there is no Save action to reach
 * for; Cancel is the only one, and it's the dismiss.
 */
@Composable
fun <T> ChoiceAlert(
    hazeState: HazeState,
    title: String,
    message: String?,
    options: List<T>,
    selected: T,
    label: (T) -> String,
    detail: (T) -> String? = { null },
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertScaffold(hazeState = hazeState, onDismiss = onDismiss) {
        CosmoDialogBody(
            overline = null,
            title = title,
            message = message,
            confirmLabel = null,
            onConfirm = {},
            dismissLabel = stringResource(R.string.cancel),
            onDismiss = onDismiss,
        ) {
            options.forEach { option ->
                ChoiceRow(
                    label = label(option),
                    detail = detail(option),
                    checked = option == selected,
                    onClick = { onSelect(option) },
                )
            }
        }
    }
}

/** The line under an alert's title when it can turn into a result: the dim ink, the error red, or the accent for a good result. */
@Composable
private fun AlertLine(text: String, isError: Boolean = false, isGood: Boolean = false) {
    Text(
        text = text,
        modifier = Modifier.padding(bottom = 14.dp),
        style = MaterialTheme.typography.bodyMedium,
        color = when {
            isError -> MaterialTheme.colorScheme.error
            isGood -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        },
    )
}

@Composable
private fun ChoiceRow(
    label: String,
    detail: String?,
    checked: Boolean,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = ACTION_HEIGHT)
            // iOS washes the whole row instead of drawing a ripple inside it.
            .background(
                if (pressed) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.09f) else Color.Transparent,
            )
            .clickable(
                indication = null,
                interactionSource = interactionSource,
                onClick = onClick,
            )
            .padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp),
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (detail != null) {
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp, lineHeight = 15.sp),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        if (checked) {
            Icon(
                imageVector = Icons.Rounded.Check,
                contentDescription = stringResource(R.string.selected),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(19.dp),
            )
        }
    }
}

/**
 * The frame every alert of the phone shares: Orbit's dialog card ([CosmoDialogCard]: the scrim that closes it, the card on the
 * floating ground, radius 32), over the whole app as an overlay. [hazeState] is kept for the callers; Orbit's cards are flat.
 */
@Composable
internal fun AlertScaffold(
    @Suppress("UNUSED_PARAMETER") hazeState: HazeState,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    CosmoDialogCard(onDismiss = onDismiss, maxWidth = 420.dp, content = content)
}
