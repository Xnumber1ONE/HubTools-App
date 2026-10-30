package com.gamestats.app

import org.json.JSONObject

data class Achievement(
    val id: String,
    val name: String,
    val desc: String,
    val hidden: Boolean,
    val unlocked: Boolean,
    val time: Long,
    val icon: String?,
    val iconGray: String?,
)

data class Game(
    val appId: Long,
    val name: String,
    val minutes: Long,
    val lastPlayed: Long,
    val art: List<String>,
    val unlocked: Int,
    val total: Int,
    val achievements: List<Achievement>,
) {
    val complete: Boolean get() = total > 0 && unlocked == total
    val percent: Float get() = if (total == 0) 0f else unlocked.toFloat() / total
    val lastUnlock: Long get() = achievements.maxOfOrNull { it.time } ?: 0L
}

data class Snapshot(val generatedAt: Long, val games: List<Game>) {
    val totalMinutes: Long get() = games.sumOf { it.minutes }
    val totalUnlocked: Int get() = games.sumOf { it.unlocked }
    val totalAchievements: Int get() = games.sumOf { it.total }
    val withAchievements: Int get() = games.count { it.total > 0 }
    val completed: Int get() = games.count { it.complete }

    fun recentUnlocks(n: Int): List<Pair<Game, Achievement>> =
        games.flatMap { g -> g.achievements.filter { it.unlocked && it.time > 0 }.map { g to it } }
            .sortedByDescending { it.second.time }
            .take(n)
}

private fun iconUrl(appId: Long, v: String): String? = when {
    v.isEmpty() -> null
    v.startsWith("http") -> v
    else -> "https://cdn.cloudflare.steamstatic.com/steamcommunity/public/images/apps/$appId/$v"
}

fun parseSnapshot(text: String): Snapshot {
    val root = JSONObject(text)
    val arr = root.getJSONArray("games")
    val games = (0 until arr.length()).map { i ->
        val g = arr.getJSONObject(i)
        val appId = g.getLong("appid")
        val ach = g.optJSONObject("achievements")
        val list = ach?.optJSONArray("list")
        val items = if (list == null) emptyList() else (0 until list.length()).map { j ->
            val a = list.getJSONObject(j)
            Achievement(
                id = a.optString("id"),
                name = a.optString("name"),
                desc = a.optString("desc"),
                hidden = a.optBoolean("hidden"),
                unlocked = a.optBoolean("unlocked"),
                time = a.optLong("time"),
                icon = iconUrl(appId, a.optString("icon")),
                iconGray = iconUrl(appId, a.optString("icon_gray")),
            )
        }
        val artArr = g.optJSONArray("art")
        val art = if (artArr != null) {
            (0 until artArr.length()).map { artArr.getString(it) }
        } else {
            listOf(g.optString("cover")).filter { it.isNotEmpty() }
        }
        Game(
            appId = appId,
            name = g.optString("name"),
            minutes = g.optLong("minutes"),
            lastPlayed = g.optLong("last_played"),
            art = art,
            unlocked = ach?.optInt("unlocked") ?: 0,
            total = ach?.optInt("total") ?: 0,
            achievements = items,
        )
    }
    return Snapshot(root.optLong("generated_at"), games)
}
