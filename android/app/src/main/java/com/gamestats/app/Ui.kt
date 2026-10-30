@file:OptIn(ExperimentalMaterial3Api::class)

package com.gamestats.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class Sort(val label: String) {
    RECENT("Last played"),
    PLAYTIME("Playtime"),
    PROGRESS("Progress"),
    TROPHIES("Trophies earned"),
    NAME("Name"),
}

enum class StatusFilter(val label: String) {
    ALL("All"),
    IN_PROGRESS("In progress"),
    COMPLETED("Completed"),
    NOT_STARTED("Not started"),
    NO_TROPHIES("No trophies"),
}

enum class AchFilter(val label: String) { ALL("All"), UNLOCKED("Unlocked"), LOCKED("Locked") }

// ---------- shared pieces ----------
@Composable
fun chipColors() = FilterChipDefaults.filterChipColors(
    selectedContainerColor = Accent.copy(alpha = 0.30f),
    selectedLabelColor = TextMain,
)

@Composable
fun ProgressBar(progress: Float, complete: Boolean, modifier: Modifier = Modifier, height: Dp = 6.dp) {
    Box(modifier.height(height).clip(RoundedCornerShape(height / 2)).background(Track)) {
        Box(
            Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).fillMaxHeight().background(
                Brush.horizontalGradient(
                    if (complete) listOf(Gold, Color(0xFFFF9F43)) else listOf(Accent, Cyan),
                ),
            ),
        )
    }
}

// ---------- formatting ----------
fun fmtHours(min: Long): String =
    if (min < 60) "$min min" else String.format(Locale.US, "%.1f h", min / 60.0)

fun fmtDate(sec: Long): String =
    if (sec <= 0) "-" else SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(Date(sec * 1000))

fun fmtAgo(sec: Long): String {
    val d = System.currentTimeMillis() / 1000 - sec
    return when {
        d < 60 -> "just now"
        d < 3600 -> "${d / 60} min ago"
        d < 86400 -> "${d / 3600} h ago"
        d < 86400 * 30 -> "${d / 86400} d ago"
        else -> fmtDate(sec)
    }
}

fun directionLabel(sort: Sort, desc: Boolean): String = when (sort) {
    Sort.NAME -> if (desc) "Z to A" else "A to Z"
    Sort.RECENT -> if (desc) "Newest first" else "Oldest first"
    else -> if (desc) "High to low" else "Low to high"
}

// ---------- game art (tries every URL, then falls back to a colored placeholder) ----------
@Composable
fun GameArt(game: Game, modifier: Modifier = Modifier) {
    var index by remember(game.appId) { mutableIntStateOf(0) }
    val url = game.art.getOrNull(index)
    val hue = ((game.name.hashCode().toLong() and 0x7fffffffL) % 360L).toFloat()
    Box(
        modifier.background(
            Brush.linearGradient(
                listOf(Color.hsv(hue, 0.45f, 0.38f), Color.hsv((hue + 40f) % 360f, 0.55f, 0.18f)),
            ),
        ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            game.name.firstOrNull()?.uppercase() ?: "?",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = Color.White.copy(alpha = 0.45f),
        )
        if (url != null) {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                onError = { index++ },
            )
        }
    }
}

// ---------- home ----------
@Composable
fun HomeScreen(
    vm: MainViewModel,
    listState: LazyListState,
    onOpen: (Game) -> Unit,
    onSettings: () -> Unit,
) {
    val snap = vm.snapshot
    val loading = vm.loading
    val error = vm.error
    val sort = vm.sort
    val desc = vm.sortDesc
    val statusFilter = vm.filter
    val query = vm.query
    val scope = rememberCoroutineScope()
    val toTop: () -> Unit = { scope.launch { listState.scrollToItem(0) } }

    val games = remember(snap, sort, desc, statusFilter, query) {
        val q = query.trim()
        val base = snap?.games.orEmpty()
            .filter { it.name.contains(q, ignoreCase = true) }
            .filter {
                when (statusFilter) {
                    StatusFilter.ALL -> true
                    StatusFilter.IN_PROGRESS -> it.total > 0 && it.unlocked > 0 && !it.complete
                    StatusFilter.COMPLETED -> it.complete
                    StatusFilter.NOT_STARTED -> it.total > 0 && it.unlocked == 0
                    StatusFilter.NO_TROPHIES -> it.total == 0
                }
            }
        val cmp: Comparator<Game> = when (sort) {
            Sort.RECENT -> compareBy<Game> { it.lastPlayed }
            Sort.PLAYTIME -> compareBy<Game> { it.minutes }
            Sort.PROGRESS -> compareBy<Game> { it.percent }
            Sort.TROPHIES -> compareBy<Game> { it.unlocked }
            Sort.NAME -> compareBy<Game> { it.name.lowercase() }
        }.thenBy { it.name.lowercase() }
        base.sortedWith(if (desc) cmp.reversed() else cmp)
    }
    val recent = remember(snap) { snap?.recentUnlocks(10).orEmpty() }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "HubTools",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        if (snap != null) "Synced ${fmtAgo(snap.generatedAt)}" else "Not synced yet",
                        style = MaterialTheme.typography.bodySmall,
                        color = Muted,
                    )
                }
                IconButton(onClick = { vm.refresh() }) { Icon(Icons.Default.Refresh, "Refresh") }
                IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, "Settings") }
            }
        }
        if (loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        if (error != null) item { ErrorBox(error) }

        if (snap == null) {
            item {
                Text(
                    if (vm.configured) "No data yet. Tap refresh." else "Open Settings to connect your PC.",
                    color = Muted,
                    modifier = Modifier.padding(vertical = 32.dp),
                )
            }
        } else {
            item { SummaryCard(snap) }

            if (recent.isNotEmpty()) {
                item { SectionTitle("Recent unlocks") }
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(recent, key = { "${it.first.appId}-${it.second.id}" }) { (g, a) ->
                            RecentCard(g, a) { onOpen(g) }
                        }
                    }
                }
            }

            item { SectionTitle("Library") }
            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = { vm.query = it; toTop() },
                    placeholder = { Text("Search games") },
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    StatusFilter.entries.forEach { f ->
                        FilterChip(
                            selected = statusFilter == f,
                            onClick = { vm.filter = f; toTop() },
                            label = { Text(f.label) },
                            colors = chipColors(),
                        )
                    }
                }
            }
            item {
                var menuOpen by remember { mutableStateOf(false) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box {
                        AssistChip(
                            onClick = { menuOpen = true },
                            label = { Text("Sort: ${sort.label}") },
                        )
                        DropdownMenu(
                            expanded = menuOpen,
                            onDismissRequest = { menuOpen = false },
                            modifier = Modifier.background(CardHi),
                        ) {
                            Sort.entries.forEach { s ->
                                DropdownMenuItem(
                                    text = { Text(s.label) },
                                    onClick = {
                                        vm.sort = s
                                        vm.sortDesc = s != Sort.NAME
                                        menuOpen = false
                                        toTop()
                                    },
                                )
                            }
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    AssistChip(
                        onClick = { vm.sortDesc = !desc; toTop() },
                        label = { Text(directionLabel(sort, desc)) },
                    )
                    Spacer(Modifier.weight(1f))
                    Text("${games.size} games", style = MaterialTheme.typography.bodySmall, color = Muted)
                }
            }

            if (games.isEmpty()) {
                item {
                    Text(
                        "No games match.",
                        color = Muted,
                        modifier = Modifier.padding(vertical = 24.dp),
                    )
                }
            }
            items(games, key = { it.appId }) { g -> GameRow(g) { onOpen(g) } }
        }
    }
}

@Composable
fun SectionTitle(text: String) {
    Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(4.dp).height(16.dp).clip(RoundedCornerShape(2.dp)).background(Accent))
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun ErrorBox(msg: String) {
    Box(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF3A1D22))
            .padding(12.dp),
    ) { Text(msg, color = Color(0xFFFF9C9C), style = MaterialTheme.typography.bodySmall) }
}

@Composable
fun SummaryCard(s: Snapshot) {
    val pct = if (s.totalAchievements == 0) 0f else s.totalUnlocked.toFloat() / s.totalAchievements
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF2B2170), Color(0xFF12152A))))
            .padding(18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(92.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(
                progress = { pct },
                modifier = Modifier.fillMaxSize(),
                color = Gold,
                trackColor = Color(0x33FFFFFF),
                strokeWidth = 8.dp,
                strokeCap = StrokeCap.Round,
            )
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "${(pct * 100).toInt()}%",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text("trophies", style = MaterialTheme.typography.labelSmall, color = Muted)
            }
        }
        Spacer(Modifier.width(20.dp))
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SummaryLine("Playtime", "%,d h".format(Locale.US, s.totalMinutes / 60))
            SummaryLine("Earned", "${s.totalUnlocked} / ${s.totalAchievements}")
            SummaryLine("Completed", "${s.completed} of ${s.withAchievements} games")
        }
    }
}

@Composable
fun SummaryLine(label: String, value: String) {
    Column {
        Text(value, fontWeight = FontWeight.SemiBold)
        Text(label, style = MaterialTheme.typography.labelSmall, color = Muted)
    }
}

@Composable
fun RecentCard(g: Game, a: Achievement, onClick: () -> Unit) {
    Column(
        Modifier.width(150.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(CardBg)
            .border(1.dp, Gold.copy(alpha = 0.25f), RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(10.dp),
    ) {
        AsyncImage(
            model = a.icon,
            contentDescription = null,
            modifier = Modifier.size(56.dp).clip(RoundedCornerShape(10.dp)).background(CardHi),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            a.name,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            g.name,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.labelSmall,
            color = Muted,
        )
        Text(fmtAgo(a.time), style = MaterialTheme.typography.labelSmall, color = Gold)
    }
}

@Composable
fun GameRow(g: Game, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Box(
        Modifier.fillMaxWidth()
            .height(88.dp)
            .clip(shape)
            .background(CardBg)
            .border(1.dp, if (g.complete) Gold.copy(alpha = 0.55f) else Outline, shape)
            .clickable(onClick = onClick),
    ) {
        GameArt(g, Modifier.align(Alignment.CenterEnd).fillMaxWidth(0.62f).fillMaxHeight())
        Box(
            Modifier.matchParentSize().background(
                Brush.horizontalGradient(
                    0f to CardBg,
                    0.45f to CardBg,
                    0.85f to CardBg.copy(alpha = 0f),
                ),
            ),
        )
        Column(
            Modifier.align(Alignment.CenterStart).fillMaxWidth(0.6f).padding(start = 14.dp, end = 4.dp),
        ) {
            Text(g.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Bold)
            Text(
                fmtHours(g.minutes) + " - " + if (g.lastPlayed > 0) fmtAgo(g.lastPlayed) else "never launched",
                style = MaterialTheme.typography.bodySmall,
                color = Muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (g.total > 0) {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ProgressBar(g.percent, g.complete, Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    if (g.complete) {
                        Icon(Icons.Default.Star, null, tint = Gold, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(2.dp))
                    }
                    Text(
                        "${g.unlocked}/${g.total}",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (g.complete) Gold else TextMain,
                    )
                }
            }
        }
    }
}

// ---------- game detail ----------
@Composable
fun GameScreen(g: Game, onBack: () -> Unit) {
    var filter by rememberSaveable { mutableStateOf(AchFilter.ALL) }
    val list = remember(g, filter) {
        g.achievements
            .filter {
                when (filter) {
                    AchFilter.ALL -> true
                    AchFilter.UNLOCKED -> it.unlocked
                    AchFilter.LOCKED -> !it.unlocked
                }
            }
            .sortedWith(compareByDescending<Achievement> { it.unlocked }.thenByDescending { it.time })
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Box {
                GameArt(g, Modifier.fillMaxWidth().aspectRatio(460f / 215f))
                Box(
                    Modifier.matchParentSize()
                        .background(Brush.verticalGradient(0.3f to Color.Transparent, 1f to Bg)),
                )
                IconButton(
                    onClick = onBack,
                    modifier = Modifier.padding(8.dp).background(Color(0x99000000), CircleShape),
                ) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                Column(Modifier.align(Alignment.BottomStart).padding(16.dp)) {
                    Text(g.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    if (g.complete) {
                        Text(
                            "Completed " + fmtDate(g.lastUnlock),
                            style = MaterialTheme.typography.bodyMedium,
                            color = Gold,
                        )
                    }
                }
            }
        }
        item {
            Column(Modifier.padding(horizontal = 16.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatTile("Playtime", fmtHours(g.minutes), Modifier.weight(1f))
                    StatTile(
                        "Last played",
                        if (g.lastPlayed > 0) fmtDate(g.lastPlayed) else "-",
                        Modifier.weight(1f),
                    )
                    StatTile("Trophies", "${g.unlocked}/${g.total}", Modifier.weight(1f))
                }
                if (g.total > 0) {
                    Spacer(Modifier.height(14.dp))
                    ProgressBar(g.percent, g.complete, Modifier.fillMaxWidth(), height = 8.dp)
                }
            }
        }
        if (g.total == 0) {
            item {
                Text(
                    "No achievement data for this game.",
                    color = Muted,
                    modifier = Modifier.padding(16.dp),
                )
            }
        } else {
            item {
                Row(
                    Modifier.padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    AchFilter.entries.forEach { f ->
                        FilterChip(selected = filter == f, onClick = { filter = f }, label = { Text(f.label) }, colors = chipColors())
                    }
                }
            }
            items(list, key = { it.id }) { a -> AchievementRow(a) }
        }
    }
}

@Composable
fun StatTile(label: String, value: String, modifier: Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(14.dp)).background(CardBg)
            .border(1.dp, Outline, RoundedCornerShape(14.dp)).padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(value, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(label, style = MaterialTheme.typography.labelSmall, color = Muted)
    }
}

@Composable
fun AchievementRow(a: Achievement) {
    val icon = if (a.unlocked) a.icon else (a.iconGray ?: a.icon)
    Row(
        Modifier.padding(horizontal = 16.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(CardBg)
            .border(1.dp, if (a.unlocked) Gold.copy(alpha = 0.3f) else Outline, RoundedCornerShape(14.dp))
            .padding(12.dp)
            .alpha(if (a.unlocked) 1f else 0.6f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = icon,
            contentDescription = null,
            modifier = Modifier.size(52.dp).clip(RoundedCornerShape(10.dp)).background(CardHi),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(a.name, fontWeight = FontWeight.SemiBold)
            if (a.desc.isNotBlank()) {
                Text(a.desc, style = MaterialTheme.typography.bodySmall, color = Muted)
            }
            Spacer(Modifier.height(4.dp))
            Text(
                if (a.unlocked) "Unlocked " + fmtDate(a.time) else if (a.hidden) "Hidden - locked" else "Locked",
                style = MaterialTheme.typography.labelSmall,
                color = if (a.unlocked) Gold else Muted,
            )
        }
    }
}

// ---------- settings ----------
@Composable
fun PcRow(pc: Pc, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier.fillMaxWidth()
            .clip(shape)
            .background(CardBg)
            .border(1.dp, if (selected) Accent else Outline, shape)
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(pc.name, fontWeight = FontWeight.SemiBold)
            Text(pc.host, style = MaterialTheme.typography.bodySmall, color = Muted)
        }
        if (selected) Text("Selected", color = Accent, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
fun SettingsScreen(vm: MainViewModel, onClose: () -> Unit) {
    val s = vm.settings
    val scope = rememberCoroutineScope()
    var scanning by remember { mutableStateOf(false) }
    var found by remember { mutableStateOf<List<Pc>?>(null) }
    var picked by remember { mutableStateOf<Pc?>(null) }
    var code by remember { mutableStateOf("") }
    var manual by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var repo by remember { mutableStateOf(s.repo) }
    var branch by remember { mutableStateOf(s.branch) }
    var path by remember { mutableStateOf(s.path) }
    var token by remember { mutableStateOf(s.token) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Text("Connect your PC", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        }
        if (!s.useGithub && s.host.isNotBlank()) {
            Text(
                "Connected to " + s.pcName.ifBlank { "your PC" } + " (" + s.host + ")",
                color = Gold,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Text(
            "1. Run HubTools PC on your computer.\n2. Keep this phone on the same Wi-Fi.\n3. Tap Find my PC.",
            color = Muted,
        )
        Button(
            onClick = {
                scanning = true
                message = null
                picked = null
                scope.launch {
                    found = Discovery.scan()
                    scanning = false
                }
            },
            enabled = !scanning,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (scanning) "Searching..." else "Find my PC") }

        found?.let { list ->
            if (list.isEmpty() && !scanning) {
                Text(
                    "No PC found. Check that HubTools PC is running, the phone is on the same Wi-Fi, " +
                        "and Windows allows it on private networks. You can also type the address below.",
                    color = Muted,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            list.forEach { pc -> PcRow(pc, picked?.id == pc.id) { picked = pc; code = ""; message = null } }
        }

        if (picked == null) {
            OutlinedTextField(
                value = manual,
                onValueChange = { manual = it.trim() },
                label = { Text("PC address (only if it is not found)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            if (manual.isNotBlank()) {
                Button(
                    onClick = {
                        busy = true
                        message = null
                        scope.launch {
                            val pc = Discovery.ping(manual)
                            busy = false
                            if (pc == null) message = "Nothing answered at that address." else picked = pc
                        }
                    },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Use this address") }
            }
        }

        picked?.let { pc ->
            OutlinedTextField(
                value = code,
                onValueChange = { code = it.filter { c -> c.isDigit() }.take(6) },
                label = { Text("Code shown on your PC") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = {
                    busy = true
                    message = null
                    scope.launch {
                        val err = Discovery.verify(pc.host, pc.port, code)
                        busy = false
                        if (err == null) {
                            s.mode = "local"
                            s.host = pc.host
                            s.port = pc.port
                            s.code = code
                            s.serverId = pc.id
                            s.pcName = pc.name
                            vm.refresh()
                            onClose()
                        } else {
                            message = err
                        }
                    }
                },
                enabled = code.length == 6 && !busy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Connect") }
        }
        message?.let { ErrorBox(it) }

        Box(Modifier.fillMaxWidth().height(1.dp).background(Outline))
        Text("Advanced: check from anywhere with GitHub", fontWeight = FontWeight.SemiBold)
        Text(
            "Optional. Uses a private GitHub repo and a read-only token instead of your home Wi-Fi.",
            color = Muted,
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedTextField(
            value = repo,
            onValueChange = { repo = it },
            label = { Text("Repository (owner/name)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = branch,
            onValueChange = { branch = it },
            label = { Text("Branch") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = path,
            onValueChange = { path = it },
            label = { Text("File path") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = token,
            onValueChange = { token = it },
            label = { Text("Read-only token") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = {
                s.repo = repo
                s.branch = branch
                s.path = path
                s.token = token
                s.mode = "github"
                vm.refresh()
                onClose()
            },
            enabled = repo.contains("/") && token.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Save and use GitHub") }
    }
}
