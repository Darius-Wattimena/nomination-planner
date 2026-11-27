package nl.greaper.bnplanner.service

import nl.greaper.bnplanner.client.DiscordClient
import nl.greaper.bnplanner.datasource.BeatmapDataSource
import nl.greaper.bnplanner.datasource.BeatmapEventDataSource
import nl.greaper.bnplanner.model.Gamemode
import nl.greaper.bnplanner.model.beatmap.Beatmap
import nl.greaper.bnplanner.model.beatmap.BeatmapStatus
import nl.greaper.bnplanner.model.discord.EmbedColor
import nl.greaper.bnplanner.model.discord.EmbedFooter
import nl.greaper.bnplanner.model.discord.EmbedThumbnail
import nl.greaper.bnplanner.service.UserService.Companion.MISSING_USER_ID
import nl.greaper.bnplanner.util.isInProgress
import nl.greaper.bnplanner.util.toReadableName
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.Instant

/** What happened in a mode last week and what needs attention, posted to the public Discord channels of that mode */
@Service
class WeeklySummaryService(
    private val beatmaps: BeatmapDataSource,
    private val events: BeatmapEventDataSource,
    private val userService: UserService,
    private val discordClient: DiscordClient?,
) {
    companion object {
        /** The same as the website calls gone quiet */
        val QUIET: Duration = Duration.ofDays(21)
        val WEEK: Duration = Duration.ofDays(7)
        const val MAX_QUIET_SHOWN = 10
        const val MAX_WAITING_SHOWN = 5
    }

    /** For each mode */
    fun summarize(now: Instant): Map<Gamemode, String> = Gamemode.entries.associateWith { summarize(it, now) }

    /** Everything that would be posted, with the mode it goes to */
    fun preview(now: Instant = Instant.now()): String =
        summarize(now).entries.joinToString("\n\n") { (gamemode, text) -> "# ${gamemode.toReadableName()}\n$text" }

    fun post(now: Instant = Instant.now()) {
        summarize(now).forEach { (gamemode, text) ->
            discordClient?.send(
                description = text,
                color = EmbedColor.BLUE,
                thumbnail = EmbedThumbnail(""),
                footer = EmbedFooter("Weekly summary of ${gamemode.toReadableName()}"),
                confidential = false,
                gamemodes = listOf(gamemode),
            )
        }
    }

    private fun summarize(
        gamemode: Gamemode,
        now: Instant,
    ): String {
        // Qualified ones only wait for osu!
        val open = beatmaps.findAll { it.isInProgress() && it.status != BeatmapStatus.Qualified && hasMode(it, gamemode) }
        val quiet = open.filter { Duration.between(it.dateUpdated, now) >= QUIET }.sortedBy { it.dateUpdated }
        val waiting = open.filter { waitsForSecondNominator(it, gamemode) }.sortedBy { it.dateUpdated }
        val weekAgo = now.minus(WEEK)

        return listOf(
            section("Gone quiet (no change for ${QUIET.toDays()} days or more)", quiet, MAX_QUIET_SHOWN, "Nothing has gone quiet.") {
                "${link(it)}, ${days(it, now)}"
            },
            section("Longest waiting for a second nominator", waiting, MAX_WAITING_SHOWN, "Nobody is waiting for a second nominator.") {
                "${link(it)}, with ${partnerName(it, gamemode)} for ${days(it, now)}"
            },
            "**Last 7 days:** ${countReaching(BeatmapStatus.Qualified, gamemode, weekAgo)} qualified, " +
                "${countReaching(BeatmapStatus.Ranked, gamemode, weekAgo)} ranked",
        ).joinToString("\n\n")
    }

    private fun section(
        title: String,
        found: List<Beatmap>,
        max: Int,
        none: String,
        line: (Beatmap) -> String,
    ): String {
        if (found.isEmpty()) {
            return "**$title**\n$none"
        }

        val more = (found.size - max).takeIf { it > 0 }?.let { "\nand $it more" }.orEmpty()
        return "**$title**\n" + found.take(max).joinToString("\n") { "- ${line(it)}" } + more
    }

    private fun hasMode(
        beatmap: Beatmap,
        gamemode: Gamemode,
    ) = beatmap.gamemodes.any { it.gamemode == gamemode }

    /** One nominator and an open spot, in a mode that needs two */
    private fun waitsForSecondNominator(
        beatmap: Beatmap,
        gamemode: Gamemode,
    ): Boolean {
        val nominators = nominatorIds(beatmap, gamemode)
        val needsTwo =
            beatmap.gamemodes.size == 1 ||
                beatmap.mainGamemodes
                    .orEmpty()
                    .ifEmpty { listOf(gamemode) }
                    .contains(gamemode)

        return needsTwo && nominators.count { it == MISSING_USER_ID } == 1
    }

    private fun nominatorIds(
        beatmap: Beatmap,
        gamemode: Gamemode,
    ) = beatmap.gamemodes
        .filter { it.gamemode == gamemode }
        .flatMap { it.nominators }
        .map { it.nominatorId }

    private fun partnerName(
        beatmap: Beatmap,
        gamemode: Gamemode,
    ): String =
        nominatorIds(beatmap, gamemode)
            .filter { it != MISSING_USER_ID }
            .joinToString { userService.findUserById(it)?.username ?: "Unknown" }

    private fun countReaching(
        status: BeatmapStatus,
        gamemode: Gamemode,
        since: Instant,
    ): Int {
        val ids = events.findBeatmapsReaching(status, since)
        return beatmaps.count { it.osuId in ids && hasMode(it, gamemode) }
    }

    private fun link(beatmap: Beatmap) = "[${beatmap.artist} - ${beatmap.title}](https://osu.ppy.sh/beatmapsets/${beatmap.osuId})"

    private fun days(
        beatmap: Beatmap,
        now: Instant,
    ): String {
        val days = Duration.between(beatmap.dateUpdated, now).toDays()
        return if (days == 1L) "1 day" else "$days days"
    }
}
