package com.ming.focusplan.focus

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ming.focusplan.MainActivity
import com.ming.focusplan.ui.theme.FocusPlanTheme

class FocusGuardActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val blockedApp = intent.getStringExtra(EXTRA_BLOCKED_APP).orEmpty()
        setContent {
            FocusPlanTheme {
                BackHandler(enabled = FocusPreferences.isStrictSessionActive(this)) {}
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                    Column(
                        Modifier.fillMaxSize().padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(Icons.Default.Lock, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.height(20.dp))
                        Text("严格专注进行中", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            if (blockedApp.isBlank()) "当前应用不在白名单中" else "$blockedApp 不在白名单中",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(24.dp))
                        Button(onClick = {
                            startService(Intent(this@FocusGuardActivity, FocusTimerService::class.java).setAction(FocusTimerService.ACTION_RESUME))
                            startActivity(Intent(this@FocusGuardActivity, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
                            finish()
                        }) { Text("返回专注") }
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(onClick = { finish() }) { Text("忽略一次") }
                        Spacer(Modifier.height(10.dp))
                        Text("忽略后计时保持暂停，可从专注页继续", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }

    companion object { const val EXTRA_BLOCKED_APP = "blocked_app" }
}
