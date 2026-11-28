package nl.greaper.bnplanner.service

import nl.greaper.bnplanner.model.Gamemode
import nl.greaper.bnplanner.model.NominationGroup
import nl.greaper.bnplanner.model.RoleChange
import nl.greaper.bnplanner.model.RoleChangeKind
import nl.greaper.bnplanner.model.RoleChangeSource
import nl.greaper.bnplanner.model.User
import nl.greaper.bnplanner.model.beatmap.BeatmapEvent
import nl.greaper.bnplanner.model.beatmap.BeatmapEventType
import nl.greaper.bnplanner.model.beatmap.BeatmapNominator
import nl.greaper.bnplanner.model.beatmap.BeatmapStatus
import nl.greaper.bnplanner.support.Planner
import nl.greaper.bnplanner.support.Planner.Companion.beatmap
import nl.greaper.bnplanner.support.Planner.Companion.mode
import nl.greaper.bnplanner.support.TestMongo
import org.bson.Document
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import tools.jackson.module.kotlin.jacksonMapperBuilder
import java.time.Instant
import java.time.LocalDate

class TransferServiceTest {
    private val planner = Planner()
    private val service = planner.transferService
    private val ranked = Instant.parse("2015-03-08T10:00:00Z")

    // Three nominators, like some old beatmaps, and a mode marked as guessed
    private val old =
        beatmap("1", mode(Gamemode.osu, "20", "21", true, true), status = BeatmapStatus.Ranked, ranked = ranked).let {
            it.copy(
                gamemodes =
                    listOf(
                        it.gamemodes[0].copy(nominators = it.gamemodes[0].nominators + BeatmapNominator("22", true), guessed = true),
                    ),
            )
        }
    private val role =
        RoleChange(
            "20",
            "Old name",
            NominationGroup.BAT,
            RoleChangeKind.Added,
            LocalDate.parse("2012-01-01"),
            source = RoleChangeSource.StaffLog,
        )

    private fun export(): ByteArray {
        planner.save(old)
        planner.save(beatmap("2", status = BeatmapStatus.Pending))
        listOf("10", "20", "21", "22").forEach { planner.user(it, "User $it") }
        planner.events.insert(BeatmapEvent("1", BeatmapEventType.Added, ranked, null, listOf(Gamemode.osu), status = BeatmapStatus.Ranked))
        planner.roleHistory.saveAll(listOf(role))

        return service.export()
    }

    /** Like another planner, which doesn't have the beatmaps and knows only some of the users */
    private fun forget() {
        planner.beatmaps.deleteById("1")
        planner.beatmaps.deleteById("2")
        TestMongo.raw.getCollection("beatmapEvents").deleteMany(Document())
        planner.users.forgetInMemoryUsers()
        listOf("20", "21").forEach { id -> planner.users.saveUser(User(id, "Known $id", emptyList())) }
    }

    @Test
    fun `Should only move the ranked beatmaps with their history and users`() {
        val file = export()
        forget()

        val summary = service.check(file)
        assertEquals(1, summary.newBeatmaps)
        assertEquals(0, summary.knownBeatmaps)
        assertEquals(2, summary.newUsers)
        assertEquals(2, summary.knownUsers)
        assertNull(planner.beatmaps.findById("1"))

        service.start(file)
        service.finish()

        assertEquals(old, planner.stored("1"))
        assertNull(planner.beatmaps.findById("2"))
        assertEquals(1, planner.events.findNewest("1", 10).size)
        // Who the planner knows stays as they are
        assertEquals("Known 20", planner.users.findUser("20")!!.username)
        assertEquals("User 22", planner.users.findUser("22")!!.username)
        assertEquals(listOf(role), planner.roleHistory.findAll())
        assertEquals(false, service.findStatus().running)
        assertNull(service.findStatus().failed)
    }

    @Test
    fun `Should leave the beatmaps it has as they are`() {
        val file = export()
        planner.beatmaps.modify("1") { it.copy(title = "Changed here") }

        assertEquals(1, service.check(file).knownBeatmaps)
        service.start(file)
        service.finish()

        assertEquals("Changed here", planner.stored("1").title)
        assertEquals(1, planner.events.findNewest("1", 10).size)
        assertEquals(0, service.findStatus().last!!.newBeatmaps)
    }

    @Test
    fun `Should never import a beatmap that is not ranked`() {
        val mapper = jacksonMapperBuilder().build()
        val export =
            TransferService.PlannerExport(
                1,
                Instant.now(),
                listOf(beatmap("3", status = BeatmapStatus.Nominated)),
                emptyList(),
                emptyList(),
                emptyList(),
            )

        val file = mapper.writeValueAsBytes(export)
        assertEquals(1, service.check(file).notRanked)
        service.start(file)
        service.finish()

        assertNull(planner.beatmaps.findById("3"))
    }

    @Test
    fun `Should refuse what is not an export of this version`() {
        val wrong =
            jacksonMapperBuilder().build().writeValueAsBytes(
                TransferService.PlannerExport(2, Instant.now(), emptyList(), emptyList(), emptyList(), emptyList()),
            )

        assertEquals(
            "This export has version 2, this planner reads version 1",
            assertThrows<TransferService.ImportException> {
                service.check(wrong)
            }.message,
        )
        assertEquals(
            "This is not an export of the planner",
            assertThrows<TransferService.ImportException> {
                service.check("nope".toByteArray())
            }.message,
        )
    }
}
