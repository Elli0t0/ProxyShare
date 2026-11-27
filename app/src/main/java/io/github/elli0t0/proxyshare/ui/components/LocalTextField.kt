package io.github.elli0t0.proxyshare.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.input.VisualTransformation

/**
 * A text field whose value lives in local state (0.7.2): the field is
 * driven by what the user typed, not by the stored setting, so an
 * async DataStore emission arriving mid-keystroke never overwrites
 * in-progress typing or jumps the cursor. The local text adopts the
 * stored [value] only while the field is not focused — an outside
 * change (import, regenerate) still shows up once the user leaves
 * the field. [onValueChange] is called on every keystroke, so the
 * caller still saves live.
 */
@Composable
fun LocalTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    supportingText: (@Composable () -> Unit)? = null,
) {
    var text by rememberSaveable { mutableStateOf(value) }
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(value, focused) {
        if (!focused) text = value
    }
    OutlinedTextField(
        value = text,
        onValueChange = { new ->
            text = new
            onValueChange(new)
        },
        label = { Text(label) },
        visualTransformation = visualTransformation,
        keyboardOptions = keyboardOptions,
        supportingText = supportingText,
        singleLine = true,
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused },
    )
}
