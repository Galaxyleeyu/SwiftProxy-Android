package com.swiftproxy.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Background = Color(0xFF0B1020)
private val Panel = Color(0xFF151D31)
private val Accent = Color(0xFF70E1C1)
private val Muted = Color(0xFF9AA7BD)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(
                primary = Accent,
                background = Background,
                surface = Panel
            )) {
                SwiftProxyHome()
            }
        }
    }
}

@Composable
private fun SwiftProxyHome() {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 22.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("SwiftProxy", color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.Bold)
                Text("0.3.0", color = Muted, fontSize = 12.sp)
            }

            Spacer(Modifier.height(42.dp))

            Box(
                modifier = Modifier
                    .background(Color(0xFF172B36), CircleShape)
                    .padding(28.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("OFF", color = Accent, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            }

            Spacer(Modifier.height(18.dp))
            Text("Not connected", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text("Core integration in progress", color = Muted, fontSize = 13.sp)

            Spacer(Modifier.height(32.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = Panel)
            ) {
                Column(Modifier.padding(18.dp)) {
                    Text("Current profile", color = Muted, fontSize = 12.sp)
                    Spacer(Modifier.height(8.dp))
                    Text("No subscription imported", color = Color.White, fontSize = 16.sp)
                    Spacer(Modifier.height(14.dp))
                    Text(
                        "HTTPS subscription and QR import are not available yet.",
                        color = Muted,
                        fontSize = 12.sp
                    )
                }
            }

            Spacer(Modifier.height(18.dp))

            Button(
                onClick = { },
                enabled = false,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    disabledContainerColor = Color(0xFF263448),
                    disabledContentColor = Muted
                )
            ) {
                Text("Connect (coming soon)", fontWeight = FontWeight.SemiBold)
            }

            Spacer(Modifier.weight(1f))
            Text(
                "SwiftProxy · Android client",
                color = Muted,
                fontSize = 11.sp
            )
        }
    }
}
