package com.example.safetymonitor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

@Composable
fun WorkSafeCard(
    modifier: Modifier = Modifier,
    muted: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(5.dp),
        color = if (muted) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

@Composable
fun WorkSafeField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    password: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Next,
    onDone: (() -> Unit)? = null,
    outlined: Boolean = false,
    isError: Boolean = false,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    supportingText: String? = null
) {
    val colors = MaterialTheme.colorScheme
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = if (enabled) colors.onSurfaceVariant else colors.onSurfaceVariant.copy(alpha = 0.5f)
        )
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth()
                .semantics { contentDescription = label },
            enabled = enabled,
            readOnly = readOnly,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(
                color = if (enabled) colors.onSurface else colors.onSurfaceVariant.copy(alpha = 0.6f)
            ),
            cursorBrush = SolidColor(colors.primary),
            visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(
                keyboardType = if (password) KeyboardType.Password else keyboardType,
                imeAction = imeAction,
                autoCorrectEnabled = false
            ),
            keyboardActions = KeyboardActions(onDone = { onDone?.invoke() }),
            decorationBox = { innerTextField ->
                Box(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .background(
                            when {
                                !enabled -> colors.surfaceVariant.copy(alpha = 0.4f)
                                outlined -> colors.surface
                                else -> colors.surfaceVariant
                            },
                            RoundedCornerShape(3.dp)
                        )
                        .border(
                            width = 1.dp,
                            color = when {
                                isError -> colors.error
                                !enabled -> colors.outline.copy(alpha = 0.3f)
                                outlined -> colors.outline
                                else -> colors.surfaceVariant
                            },
                            shape = RoundedCornerShape(3.dp)
                        )
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    if (value.isEmpty()) {
                        Text(placeholder, style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant)
                    }
                    innerTextField()
                }
            }
        )
        if (supportingText != null) {
            Text(
                supportingText,
                style = MaterialTheme.typography.bodySmall,
                color = if (isError) colors.error else colors.onSurfaceVariant
            )
        }
    }
}

@Composable
fun WorkSafeButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    danger: Boolean = false,
    emphasized: Boolean = false,
    secondary: Boolean = false,
    enabled: Boolean = true
) {
    val colors = MaterialTheme.colorScheme
    val background = when {
        danger -> colors.error
        emphasized -> colors.secondary
        secondary -> colors.surfaceVariant
        else -> colors.primary
    }
    val foreground = when {
        danger -> colors.onError
        emphasized -> colors.onSecondary
        secondary -> colors.onSurface
        else -> colors.onPrimary
    }
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 48.dp),
        shape = RoundedCornerShape(3.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = background,
            contentColor = foreground,
            disabledContainerColor = colors.surfaceVariant.copy(alpha = 0.5f),
            disabledContentColor = colors.onSurfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun WorkSafeHeader(
    title: String,
    subtitle: String? = null,
    brand: Boolean = false,
    onTitleTap: (() -> Unit)? = null,
    onMenuClick: (() -> Unit)? = null
) {
    Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val titleModifier = Modifier
                .weight(1f)
                .padding(start = if (brand) 0.dp else 12.dp)
                .then(
                    if (onTitleTap != null) {
                        Modifier.clickable(
                            role = Role.Button,
                            onClickLabel = "비콘 설정 열기",
                            onClick = onTitleTap
                        )
                    } else Modifier
                )
            Column(titleModifier) {
                Text(title, style = if (brand) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleMedium)
                if (subtitle != null) {
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (brand) {
                    Box(Modifier.padding(top = 3.dp).width(56.dp).height(4.dp).background(WorkSafeBrandAccent))
                }
            }
            if (onMenuClick != null) {
                IconButton(onClick = onMenuClick, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Default.Menu, contentDescription = "설정 메뉴 열기")
                }
            }
        }
    }
}
