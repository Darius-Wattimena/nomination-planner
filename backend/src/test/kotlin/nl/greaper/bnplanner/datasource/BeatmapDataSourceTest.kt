package nl.greaper.bnplanner.datasource

import com.mongodb.client.model.WriteModel
import com.nhaarman.mockitokotlin2.any
import com.nhaarman.mockitokotlin2.doReturn
import com.nhaarman.mockitokotlin2.doThrow
import com.nhaarman.mockitokotlin2.mock
import com.nhaarman.mockitokotlin2.spy
import com.nhaarman.mockitokotlin2.verify
import com.nhaarman.mockitokotlin2.whenever
import nl.greaper.bnplanner.datasource.BeatmapDataSource.Companion.COLLECTION
import nl.greaper.bnplanner.datasource.BeatmapDataSource.Companion.MIGRATIONS
import nl.greaper.bnplanner.datasource.BeatmapDataSource.Companion.OLD_COLLECTION
import nl.greaper.bnplanner.datasource.BeatmapDataSource.MigrationResult
import nl.greaper.bnplanner.model.Gamemode
import nl.greaper.bnplanner.model.PageLimit
import nl.greaper.bnplanner.model.beatmap.Beatmap
import nl.greaper.bnplanner.model.beatmap.BeatmapStatus
import nl.greaper.bnplanner.support.Planner
import nl.greaper.bnplanner.support.Planner.Companion.UPDATED
import nl.greaper.bnplanner.support.Planner.Companion.beatmap
import nl.greaper.bnplanner.support.Planner.Companion.mode
import nl.greaper.bnplanner.support.TestMongo
import nl.greaper.bnplanner.task.BeatmapMigration
import org.bson.Document
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.time.Instant

class BeatmapDataSourceTest {
    private val source = Planner().beatmaps
    private val all = { _: Beatmap -> true }

    private val complete =
        beatmap(
            "1",
            mode(Gamemode.fruits, "5", "0", firstNominated = true, isReady = true),
            mode(Gamemode.osu, "0", "-2"),
            mode(Gamemode.taiko),
            status = BeatmapStatus.Disqualified,
            note = "note",
            ranked = Instant.parse("2024-03-03T00:00:00Z"),
            main = listOf(Gamemode.fruits, Gamemode.osu),
        )

    private fun open(keepOld: Boolean = true) = BeatmapDataSource(TestMongo.database, keepOld)

    private fun stored() = TestMongo.documents(COLLECTION)

    private fun storedOld() = TestMongo.documents(OLD_COLLECTION)

    // Storing

    @Test
    fun `Should store a beatmap in the expected document shape`() {
        source.insertOne(complete)

        assertEquals(
            Document.parse(
                """
                {"_id": 1, "artist": "Artist 1", "title": "Title 1", "mapper": 10, "mapperName": "Mapper", "status": 3,
                "modes": [
                    {"mode": "fruits", "by": [{"user": 5, "done": true}], "ready": true},
                    {"mode": "osu", "by": [{"user": 0}, {"user": -2}]},
                    {"mode": "taiko", "by": []}],
                "added": {"${'$'}date": "2024-01-01T10:00:00Z"}, "updated": {"${'$'}date": "2024-02-02T11:12:13.456Z"},
                "note": "note", "ranked": {"${'$'}date": "2024-03-03T00:00:00Z"}, "main": ["fruits", "osu"]}
                """,
            ),
            stored().single(),
        )
    }

    @Test
    fun `Should store the old document shape as well`() {
        source.insertOne(complete.copy(gamemodes = complete.gamemodes.take(1)))

        assertEquals(
            Document.parse(
                """
                {"_id": "1", "artist": "Artist 1", "title": "Title 1", "note": "note", "mapper": "Mapper", "mapperId": "10",
                "status": 3,
                "gamemodes": [{"gamemode": "fruits", "nominators": [
                    {"nominatorId": "5", "hasNominated": true}, {"nominatorId": "0", "hasNominated": false}], "isReady": true}],
                "dateAdded": {"${'$'}date": "2024-01-01T10:00:00Z"},
                "dateUpdated": {"${'$'}date": "2024-02-02T11:12:13.456Z"},
                "dateRanked": {"${'$'}date": "2024-03-03T00:00:00Z"}, "mainGamemodes": ["fruits", "osu"]}
                """,
            ),
            storedOld().single(),
        )
    }

    @Test
    fun `Should store as little as possible`() {
        source.insertOne(beatmap())

        assertEquals(
            setOf("_id", "artist", "title", "mapper", "mapperName", "status", "modes", "added", "updated"),
            stored().single().keys,
        )
        val old = storedOld().single()
        assertTrue("dateRanked" in old.keys)
        assertNull(old["dateRanked"])
        assertNull(old["mainGamemodes"])
    }

    @Test
    fun `Should read the same from both collections`() {
        val maps =
            listOf(complete, beatmap("2"), beatmap("text"), beatmap("007"), beatmap("99999999999")) +
                BeatmapStatus.entries.map { beatmap("5${it.ordinal}", status = it) }
        source.insertMany(maps)
        source.migrate()

        assertEquals(maps.toSet(), open().list().toSet())
        assertEquals(maps.toSet(), open(keepOld = false).list().toSet())
    }

    @Test
    fun `Should store dates in milliseconds`() {
        source.insertOne(
            beatmap(updated = Instant.parse("2024-02-02T11:12:13.456789Z"), ranked = Instant.parse("2024-02-02T11:12:13.456789Z")),
        )

        assertEquals(UPDATED, source.findById("1")!!.dateUpdated)
        assertEquals(UPDATED, source.findById("1")!!.dateRanked)
        assertEquals(source.list(), open().list())
    }

    @Test
    fun `Should always have two nominators`() {
        val single = mode(Gamemode.osu, "5").let { it.copy(nominators = it.nominators.take(1)) }
        source.insertOne(beatmap("1", single))

        assertEquals(listOf(mode(Gamemode.osu, "5")), source.findById("1")!!.gamemodes)
    }

    @Test
    fun `Should replace a beatmap or add it when it doesn't exist`() {
        source.update(beatmap("1", note = "added", ranked = UPDATED))
        source.update(beatmap("1", note = "replaced"))

        assertEquals(listOf(beatmap("1", note = "replaced")), source.list())
        assertNull(storedOld().single()["dateRanked"])
        assertFalse("ranked" in stored().single().keys)
    }

    @Test
    fun `Should not insert a beatmap twice`() {
        source.insertOne(beatmap())

        assertThrows<Exception> { source.insertOne(beatmap(note = "other")) }
        assertEquals("", source.findById("1")!!.note)
    }

    @Test
    fun `Should list, count, find and delete`() {
        assertNull(source.findById("1"))
        assertFalse(source.deleteById("1"))

        insertSorted(3)
        assertEquals(setOf("1", "2", "3"), source.list().map { it.osuId }.toSet())
        assertEquals(3, source.count(all))
        assertEquals(1, source.count { it.osuId == "2" })
        assertTrue(source.deleteById("2"))
        assertEquals(setOf("1", "3"), open().list().map { it.osuId }.toSet())
        assertEquals(2, stored().size)
        assertEquals(2, storedOld().size)
    }

    // Finding

    private fun insertSorted(amount: Int) =
        source.insertMany((1..amount).map { beatmap("$it", updated = UPDATED.minusSeconds(it.toLong())) })

    @Test
    fun `Should sort on status and then on the latest update`() {
        source.insertOne(beatmap("old", status = BeatmapStatus.Pending, updated = UPDATED.minusSeconds(5)))
        source.insertOne(beatmap("ranked", status = BeatmapStatus.Ranked, updated = UPDATED.plusSeconds(5)))
        source.insertOne(beatmap("same", status = BeatmapStatus.Pending))
        source.insertOne(beatmap("new", status = BeatmapStatus.Pending))
        source.insertOne(beatmap("qualified", status = BeatmapStatus.Qualified, updated = UPDATED.minusSeconds(9)))

        val expected = listOf("qualified", "new", "same", "old", "ranked")
        assertEquals(expected, source.findAll(all, 0, 10).map { it.osuId })
        assertEquals(expected, source.findAll(all, 1, PageLimit.TEN).map { it.osuId })
        assertEquals(expected, source.findAll(all).map { it.osuId })
        assertEquals(listOf("ranked"), source.findAll { it.status == BeatmapStatus.Ranked }.map { it.osuId })
    }

    @Test
    fun `Should find a page`() {
        insertSorted(120)

        fun page(
            number: Int,
            limit: PageLimit,
        ) = source.findAll(all, number, limit).map { it.osuId.toInt() }

        assertEquals((1..10).toList(), page(1, PageLimit.TEN))
        assertEquals((1..10).toList(), page(0, PageLimit.TEN))
        assertEquals((1..10).toList(), page(-1, PageLimit.TEN))
        assertEquals((11..20).toList(), page(2, PageLimit.TEN))
        assertEquals((41..60).toList(), page(3, PageLimit.TWENTY))
        assertEquals((101..120).toList(), page(3, PageLimit.FIFTY))
        assertEquals(emptyList<Int>(), page(4, PageLimit.FIFTY))
    }

    @Test
    fun `Should find a range`() {
        insertSorted(30)

        fun range(
            from: Int,
            to: Int,
        ) = source.findAll(all, from, to).map { it.osuId.toInt() }

        assertEquals((1..10).toList(), range(0, 10))
        assertEquals((6..10).toList(), range(5, 10))
        assertEquals((26..30).toList(), range(25, 100))
        assertEquals((1..30).toList(), range(0, 2000))
        assertEquals((1..10).toList(), range(-5, 5))
        assertEquals(emptyList<Int>(), range(5, 5))
        assertEquals(emptyList<Int>(), range(10, 5))
    }

    // Migrating

    private fun insertOld(
        id: String,
        status: Int = 5,
        extra: String = "",
    ) = TestMongo.insert(
        OLD_COLLECTION,
        """
        {"_id": "$id", "artist": "Artist $id", "title": "Title $id", "note": "", "mapper": "Mapper", "mapperId": "10",
        "status": $status, $extra "gamemodes": [{"gamemode": "osu", "nominators": [{"nominatorId": "0", "hasNominated": false},
        {"nominatorId": "0", "hasNominated": false}], "isReady": false}],
        "dateAdded": {"${'$'}date": "2024-01-01T10:00:00Z"}, "dateUpdated": {"${'$'}date": "2024-02-02T11:12:13.456Z"}}
        """,
    )

    @ParameterizedTest
    @EnumSource(BeatmapStatus::class)
    fun `Should read every stored status`(status: BeatmapStatus) {
        insertOld("7", status.ordinal + 1)

        assertEquals(beatmap("7", status = status), open().findById("7"))
    }

    @Test
    fun `Should ignore unknown stored fields and read missing or null dates`() {
        insertOld("7", extra = """"dateRanked": null, "legacy": {"a": 1},""")
        insertOld("8")

        assertEquals(setOf(beatmap("7"), beatmap("8")), open().list().toSet())
    }

    @Test
    fun `Should use the old collection right away and copy it`() {
        (1..1203).forEach { insertOld("$it") }
        val before = storedOld()
        val source = open()

        assertEquals(1203, source.list().size)
        assertEquals(0, stored().size)
        assertEquals(MigrationResult(written = 1203, removed = 0, different = 0, unreadable = 0), source.migrate())
        assertEquals(1203, stored().size)
        assertEquals(before, storedOld())
        assertEquals(source.list().toSet(), open(keepOld = false).list().toSet())
    }

    @Test
    fun `Should continue where it stopped and only copy what changed`() {
        insertOld("1")
        insertOld("2")
        open().migrate()
        insertOld("3")
        // Changed and deleted by the previous version
        TestMongo.raw.getCollection(OLD_COLLECTION).updateOne(
            Document("_id", "2"),
            Document("\$set", Document("note", "changed")),
        )
        TestMongo.raw.getCollection(OLD_COLLECTION).deleteOne(Document("_id", "1"))

        assertEquals(MigrationResult(written = 2, removed = 1, different = 0, unreadable = 0), open().migrate())
        assertEquals(MigrationResult(written = 0, removed = 0, different = 0, unreadable = 0), open().migrate())
        assertEquals(setOf("2", "3"), open(keepOld = false).list().map { it.osuId }.toSet())
    }

    @Test
    fun `Should migrate while beatmaps change`() {
        (1..50).forEach { insertOld("$it") }
        val source = open()
        val thread = Thread { (1..50).forEach { source.update(beatmap("$it", note = "changed $it")) } }
        thread.start()
        source.migrate()
        thread.join()

        assertEquals(50, source.list().count { it.note == "changed ${it.osuId}" })
        assertEquals(source.list().toSet(), open().list().toSet())
        source.migrate()
        assertEquals(source.list().toSet(), open(keepOld = false).list().toSet())
    }

    @Test
    fun `Should keep using the old collection until it is fully copied`() {
        insertOld("1")
        insertOld("2", status = 9)
        val source = open(keepOld = false)

        assertEquals(listOf("1"), source.list().map { it.osuId })
        assertEquals(MigrationResult(written = 1, removed = 0, different = 0, unreadable = 1), source.migrate())
        assertEquals(0, TestMongo.documents(MIGRATIONS).size)
        // Still the old one
        insertOld("3")
        assertEquals(setOf("1", "3"), open(keepOld = false).list().map { it.osuId }.toSet())
    }

    @Test
    fun `Should never go back to the old collection once it is not kept anymore`() {
        insertOld("1")
        open().migrate()
        val source = open(keepOld = false)
        source.update(beatmap("2"))
        source.deleteById("1")

        assertNull(source.migrate())
        assertEquals(listOf("1"), storedOld().map { it["_id"] })
        // Even when it has to be kept again, as it is outdated now
        assertEquals(listOf("2"), open(keepOld = true).list().map { it.osuId })
        assertNull(open(keepOld = true).migrate())
        assertEquals(listOf(2), stored().map { it["_id"] })
    }

    @Test
    fun `Should report what could not be copied`() {
        insertOld("1")
        insertOld("2")
        val ignoringWrites = spy(TestMongo.database.getCollection(COLLECTION))
        doReturn(null).whenever(ignoringWrites).bulkWrite(any<List<WriteModel<Document>>>())
        val database = spy(TestMongo.database)
        doReturn(ignoringWrites).whenever(database).getCollection(COLLECTION)
        val source = BeatmapDataSource(database, true)

        assertEquals(MigrationResult(written = 2, removed = 0, different = 2, unreadable = 0), source.migrate())
        assertEquals(0, TestMongo.documents(MIGRATIONS).size)
        // Still the old one
        assertEquals(2, open(keepOld = false).list().size)
    }

    @Test
    fun `Should migrate in the background`() {
        insertOld("1")
        BeatmapMigration(open()).start().join()

        assertEquals(1, stored().size)
    }

    @Test
    fun `Should keep running when migrating fails`() {
        val source = mock<BeatmapDataSource> { on { migrate() } doThrow IllegalStateException("Not available") }
        BeatmapMigration(source).start().join()

        verify(source).migrate()
    }
}
