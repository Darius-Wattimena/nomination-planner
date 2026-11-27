package nl.greaper.bnplanner.controller

import nl.greaper.bnplanner.datasource.BeatmapEventDataSource
import nl.greaper.bnplanner.model.Gamemode
import nl.greaper.bnplanner.model.beatmap.BeatmapEvent
import nl.greaper.bnplanner.model.beatmap.BeatmapEventType
import nl.greaper.bnplanner.model.beatmap.BeatmapStatus
import nl.greaper.bnplanner.model.osu.BeatmapsetEventType
import nl.greaper.bnplanner.support.ApiTest
import nl.greaper.bnplanner.support.Planner.Companion.UPDATED
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/** The history of a beatmap and the weekly summary */
class HistoryApiTest : ApiTest() {
    @Autowired
    lateinit var events: BeatmapEventDataSource

    @Test
    fun `Should find the history of a beatmap`() {
        events.insert(BeatmapEvent("1", BeatmapEventType.NominatorJoined, UPDATED, "3", listOf(Gamemode.taiko), "2"))
        events.insert(
            BeatmapEvent(
                "1",
                BeatmapEventType.StatusChanged,
                UPDATED.plusSeconds(1),
                status = BeatmapStatus.Qualified,
                osuEvent = BeatmapsetEventType.QUALIFY,
            ),
        )

        get("/v2/beatmap/1/events").assertJson(
            """
            [{"type":"StatusChanged","at":"2024-02-02T11:12:14.456Z","gamemodes":[],"status":"Qualified","osuEvent":"QUALIFY"},
            {"type":"NominatorJoined","at":"2024-02-02T11:12:13.456Z","gamemodes":["taiko"],
            "user":{"osuId":"3","username":"Admin","gamemodes":[{"gamemode":"mania","role":"NominationAssessment"}]},
            "nominator":{"osuId":"2","username":"Editor","gamemodes":[{"gamemode":"osu","role":"Nominator"}]}}]
            """,
        )
        get("/v2/beatmap/2/events").assertJson("[]")
        assertEquals(401, get("/v2/beatmap/1/events", As.NOBODY).status)
    }

    @Test
    fun `Should preview the weekly summary for admins only`() {
        val preview = call(As.ADMIN, "POST", "/v2/admin/weekly-summary/preview")

        assertEquals(200, preview.status)
        assertTrue(preview.contentType!!.startsWith("text/plain"))
        assertTrue(preview.contentAsString.startsWith("# osu\n**Gone quiet"), preview.contentAsString)
        assertEquals(403, call(As.EDITOR, "POST", "/v2/admin/weekly-summary/preview").status)
    }
}
