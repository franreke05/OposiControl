package com.oposicontrol.admin.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.material3.Text
import androidx.compose.ui.tooling.preview.Preview
import com.oposicontrol.admin.core.common.platform.AppPlatformContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        setContent {
            OposiControlAdminApp(AppPlatformContext(applicationContext))
        }
    }
}

@Preview
@Composable
fun AppAndroidPreview() {
    Text("Preview disponible en runtime Android")
}
