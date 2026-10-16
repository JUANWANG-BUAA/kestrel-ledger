package cn.market.assistant

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

@Composable
internal fun ExportEncryptedBackupDialog(
    password: String,
    confirmation: String,
    error: String?,
    busy: Boolean,
    onPasswordChange: (String) -> Unit,
    onConfirmationChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("加密导出账本") },
        text = {
            Column {
                Text(
                    "备份使用 AES-GCM 和密码加密。密码不会保存；换机恢复时需要再次输入。请妥善保存密码。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                PasswordField("设置备份密码（至少 12 位）", password, onPasswordChange)
                PasswordField("再次输入密码", confirmation, onConfirmationChange)
                if (error != null) {
                    Text(
                        error,
                        Modifier.padding(top = 6.dp),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = onConfirm, enabled = !busy) { Text(if (busy) "正在加密…" else "加密并导出") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("取消") } }
    )
}

@Composable
internal fun UnlockEncryptedBackupDialog(
    password: String,
    error: String?,
    busy: Boolean,
    onPasswordChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("解锁加密备份") },
        text = {
            Column {
                Text("输入导出时设置的密码。解锁成功后，仍会先显示恢复确认。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                PasswordField("备份密码", password, onPasswordChange)
                if (error != null) {
                    Text(
                        error,
                        Modifier.padding(top = 6.dp),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = onConfirm, enabled = !busy && password.isNotEmpty()) {
                Text(if (busy) "正在解锁…" else "解锁")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("取消") } }
    )
}

@Composable
private fun PasswordField(label: String, value: String, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        label = { Text(label) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
    )
}
