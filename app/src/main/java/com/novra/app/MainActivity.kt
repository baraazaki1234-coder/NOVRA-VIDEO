package com.novra.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Ink = Color(0xFF10131A)
private val Panel = Color(0xFF1B202A)
private val Muted = Color(0xFF929BAA)
private val Accent = Color(0xFFB8E3D2)
private val Canvas = Color(0xFF0C0F14)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { NovraApp() }
    }
}

@Composable
private fun NovraApp() {
    var selected by remember { mutableStateOf("Home") }
    MaterialTheme(
        colorScheme = darkColorScheme(
            background = Canvas, surface = Panel, primary = Accent,
            onBackground = Color.White, onSurface = Color.White
        )
    ) {
        Scaffold(
            containerColor = Canvas,
            bottomBar = {
                NavigationBar(containerColor = Color(0xFF141922), tonalElevation = 0.dp) {
                    val tabs = listOf(
                        "Home" to Icons.Rounded.Home,
                        "Videos" to Icons.Rounded.OndemandVideo,
                        "Music" to Icons.Rounded.MusicNote,
                        "Library" to Icons.Rounded.CollectionsBookmark
                    )
                    tabs.forEach { (label, icon) ->
                        NavigationBarItem(
                            selected = selected == label,
                            onClick = { selected = label },
                            icon = { Icon(icon, contentDescription = label) },
                            label = { Text(label, fontSize = 11.sp) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = Accent,
                                selectedTextColor = Accent,
                                indicatorColor = Color(0xFF293A37),
                                unselectedIconColor = Muted,
                                unselectedTextColor = Muted
                            )
                        )
                    }
                }
            }
        ) { padding ->
            when (selected) {
                "Home" -> HomeScreen(Modifier.padding(padding))
                "Videos" -> EmptySection("Your videos", "Videos on this device will appear here.", Icons.Rounded.OndemandVideo, Modifier.padding(padding))
                "Music" -> EmptySection("Your music", "Your audio collection will live here.", Icons.Rounded.MusicNote, Modifier.padding(padding))
                else -> EmptySection("Your library", "Favorites, playlists and recent activity.", Icons.Rounded.CollectionsBookmark, Modifier.padding(padding))
            }
        }
    }
}

@Composable
private fun HomeScreen(modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = 22.dp)
    ) {
        Spacer(Modifier.height(24.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("NOVRA", color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.Black, letterSpacing = 3.sp)
                Text("Your media, in its own rhythm.", color = Muted, fontSize = 12.sp)
            }
            IconButton(onClick = { }) {
                Icon(Icons.Rounded.Search, "Search", tint = Color.White, modifier = Modifier.size(25.dp))
            }
            IconButton(onClick = { }) {
                Icon(Icons.Rounded.Settings, "Settings", tint = Color.White, modifier = Modifier.size(23.dp))
            }
        }
        Spacer(Modifier.height(30.dp))
        Text("GOOD TO SEE YOU", color = Accent, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
        Spacer(Modifier.height(7.dp))
        Text("Make room for
what moves you.", color = Color.White, fontSize = 34.sp, lineHeight = 39.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(22.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FeatureCard("VIDEO", "Watch something", Icons.Rounded.PlayCircle, Modifier.weight(1f))
            FeatureCard("MUSIC", "Find your sound", Icons.Rounded.GraphicEq, Modifier.weight(1f))
        }
        Spacer(Modifier.height(28.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Pick up where you left off", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text("VIEW ALL", color = Accent, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        }
        Spacer(Modifier.height(13.dp))
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp))
                .background(Brush.linearGradient(listOf(Color(0xFF31423F), Color(0xFF202B35), Color(0xFF29263A))))
                .padding(18.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(58.dp).clip(RoundedCornerShape(17.dp)).background(Color(0xFF465B55)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.PlayArrow, null, tint = Color.White, modifier = Modifier.size(34.dp))
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text("Nothing playing yet", color = Color.White, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text("Your recent media will show up here", color = Color(0xFFBAC5C2), fontSize = 11.sp)
                }
            }
        }
        Spacer(Modifier.height(28.dp))
        Text("YOUR SPACE", color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            QuickStat("VIDEOS", "—", Icons.Rounded.Movie, Modifier.weight(1f))
            QuickStat("TRACKS", "—", Icons.Rounded.MusicNote, Modifier.weight(1f))
            QuickStat("PLAYLISTS", "—", Icons.Rounded.QueueMusic, Modifier.weight(1f))
        }
        Spacer(Modifier.height(28.dp))
    }
}

@Composable
private fun FeatureCard(tag: String, title: String, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier = Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(22.dp))
            .background(Panel).clickable { }
            .padding(16.dp)
    ) {
        Box(Modifier.size(42.dp).clip(CircleShape).background(Color(0xFF293A37)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = Accent, modifier = Modifier.size(23.dp))
        }
        Spacer(Modifier.height(25.dp))
        Text(tag, color = Accent, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
        Spacer(Modifier.height(5.dp))
        Text(title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun QuickStat(label: String, value: String, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier = Modifier) {
    Column(modifier.clip(RoundedCornerShape(17.dp)).background(Panel).padding(13.dp)) {
        Icon(icon, null, tint = Accent, modifier = Modifier.size(19.dp))
        Spacer(Modifier.height(12.dp))
        Text(value, color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.Bold)
        Text(label, color = Muted, fontSize = 8.sp, letterSpacing = 1.sp)
    }
}

@Composable
private fun EmptySection(title: String, subtitle: String, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxSize().padding(28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(Modifier.size(76.dp).clip(RoundedCornerShape(25.dp)).background(Panel), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = Accent, modifier = Modifier.size(34.dp))
        }
        Spacer(Modifier.height(20.dp))
        Text(title, color = Color.White, fontSize = 23.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text(subtitle, color = Muted, fontSize = 13.sp)
    }
}
