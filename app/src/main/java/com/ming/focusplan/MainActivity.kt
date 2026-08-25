package com.ming.focusplan

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.ming.focusplan.ui.FocusPlanApp
import com.ming.focusplan.ui.theme.FocusPlanTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            FocusPlanTheme {
                FocusPlanApp((application as FocusPlanApplication).container)
            }
        }
    }
}
