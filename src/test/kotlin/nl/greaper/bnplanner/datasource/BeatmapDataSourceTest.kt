package nl.greaper.bnplanner.datasource

import nl.greaper.bnplanner.model.Gamemode
import nl.greaper.bnplanner.model.PageLimit
import nl.greaper.bnplanner.model.beatmap.BeatmapStatus
import nl.greaper.bnplanner.support.Planner
import nl.greaper.bnplanner.support.Planner.Companion.UPDATED
import nl.greaper.bnplanner.support.Planner.Companion.beatmap
import nl.greaper.bnplanner.support.Planner.Companion.mode
import nl.greaper.bnplanner.support.TestMongo
import org.bson.Document
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.time.Instant

class BeatmapDataSourceTest {
    private val source = Planner().beatmaps
    private val all = Document()

    @Test
    fun `Should store a beatmap in the expected document shape`() {
        source.insertOne(
            beatmap(
                "1",
                mode(Gamemode.fruits, "5", "0", firstNominated = true, isReady = true),
                status = BeatmapStatus.Disqualified,
                note = "note",
                ranked = Instant.parse("2024-03-03T00:00:00Z"),
            ),
        )

        val expected =
            """
            {"_id": "1", "artist": "Artist 1", "title": "Title 1", "note": "note", "mapper": "Mapper", "mapperId": "10",
            "status": 3,
            "gamemodes": [{"gamemode": "fruits", "nominators": [
                {"nominatorId": "5", "hasNominated": true}, {"nominatorId": "0", "hasNominated": false}], "isReady": true}],
            "dateAdded": {"${'$'}date": "2024-01-01T10:00:00Z"},
            "dateUpdated": {"${'$'}date": "2024-02-02T11:12:13.456Z"},
            "dateRanked": {"${'$'}date": "2024-03-03T00:00:00Z"}}
            """
        assertEquals(Document.parse(expected), TestMongo.documents("beatmaps").single())
    }

    @Test
    fun `Should store a missing ranked date as null`() {
        source.insertOne(beatmap())

        val stored = TestMongo.documents("beatmaps").single()
        assertTrue("dateRanked" in stored.keys)
        assertNull(stored["dateRanked"])
        assertNull(source.findById("1")!!.dateRanked)
    }

    @Test
    fun `Should store dates in milliseconds`() {
        source.insertOne(beatmap(updated = Instant.parse("2024-02-02T11:12:13.456789Z")))

        assertEquals(UPDATED, source.findById("1")!!.dateUpdated)
    }

    @ParameterizedTest
    @EnumSource(BeatmapStatus::class)
    fun `Should read every stored status`(status: BeatmapStatus) {
        TestMongo.insert("beatmaps", storedBeatmap("7", (status.ordinal + 1).toString()))

        assertEquals(beatmap("7", status = status), source.findById("7"))
        source.update(source.findById("7")!!.copy(note = "changed"))
        assertEquals(status.ordinal + 1, TestMongo.documents("beatmaps").single()["status"])
    }

    @Test
    fun `Should ignore unknown stored fields and read missing or null dates`() {
        TestMongo.insert("beatmaps", storedBeatmap("7", "5", """"dateRanked": null, "legacy": {"a": 1},"""))
        TestMongo.insert("beatmaps", storedBeatmap("8", "5"))

        assertEquals(beatmap("7"), source.findById("7"))
        assertEquals(beatmap("8"), source.findById("8"))
    }

    @Test
    fun `Should fail to read a status that does not exist`() {
        TestMongo.insert("beatmaps", storedBeatmap("7", "9"))

        assertThrows<Exception> { source.findById("7") }
    }

    private fun storedBeatmap(
        id: String,
        status: String,
        extra: String = "",
    ) = """
        {"_id": "$id", "artist": "Artist $id", "title": "Title $id", "note": "", "mapper": "Mapper", "mapperId": "10",
        "status": $status, $extra "gamemodes": [{"gamemode": "osu", "nominators": [{"nominatorId": "0", "hasNominated": false},
        {"nominatorId": "0", "hasNominated": false}], "isReady": false}],
        "dateAdded": {"${'$'}date": "2024-01-01T10:00:00Z"}, "dateUpdated": {"${'$'}date": "2024-02-02T11:12:13.456Z"}}
        """

    @Test
    fun `Should create the index`() {
        TestMongo.raw.getCollection("beatmaps").drop()
        BeatmapDataSource(TestMongo.database).list()
        // Creating it again is fine
        BeatmapDataSource(TestMongo.database).list()

        val indexes =
            TestMongo.raw
                .getCollection("beatmaps")
                .listIndexes()
                .toList()
        assertEquals(listOf("_id_", "status_1_dateUpdated_1_artist_1_title_1"), indexes.map { it.getString("name") })
        assertEquals(Document.parse("""{"status": 1, "dateUpdated": 1, "artist": 1, "title": 1}"""), indexes[1]["key"])
        assertNull(indexes[1]["unique"])
    }

    private fun insertSorted(amount: Int) =
        source.insertMany((1..amount).map { beatmap("$it", updated = UPDATED.minusSeconds(it.toLong())) })

    @Test
    fun `Should sort on status and then on the latest update`() {
        source.insertOne(beatmap("old", status = BeatmapStatus.Pending, updated = UPDATED.minusSeconds(5)))
        source.insertOne(beatmap("ranked", status = BeatmapStatus.Ranked, updated = UPDATED.plusSeconds(5)))
        source.insertOne(beatmap("new", status = BeatmapStatus.Pending))
        source.insertOne(beatmap("qualified", status = BeatmapStatus.Qualified, updated = UPDATED.minusSeconds(9)))

        val expected = listOf("qualified", "new", "old", "ranked")
        assertEquals(expected, source.findAll(all, 0, 10).map { it.osuId })
        assertEquals(expected, source.findAll(all, 1, PageLimit.TEN).map { it.osuId })
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
    }

    @Test
    fun `SUSPECTED BUG - An empty range has no limit and a reversed range is used as its size`() {
        insertSorted(30)

        assertEquals(25, source.findAll(all, 5, 5).size)
        assertEquals((11..15).toList(), source.findAll(all, 10, 5).map { it.osuId.toInt() })
    }

    @Test
    fun `Should find all using a filter`() {
        insertSorted(30)

        assertEquals(30, source.findAll(all).size)
        assertEquals(listOf("3"), source.findAll(Document("_id", "3")).map { it.osuId })
    }

    @Test
    fun `Should replace a beatmap or add it when it doesn't exist`() {
        source.update(beatmap("1", note = "added"))
        source.update(beatmap("1", note = "replaced"))

        assertEquals(listOf(beatmap("1", note = "replaced")), source.list())
    }

    @Test
    fun `Should clear the ranked date when replacing`() {
        source.update(beatmap(ranked = UPDATED))
        assertEquals(UPDATED, source.findById("1")!!.dateRanked)
        source.update(beatmap())

        assertNull(TestMongo.documents("beatmaps").single()["dateRanked"])
    }

    @Test
    fun `Should list, count, find and delete`() {
        assertNull(source.findFirst())
        assertNull(source.findById("1"))
        assertEquals(0, source.deleteById("1").deletedCount)

        insertSorted(3)
        assertEquals(setOf("1", "2", "3"), source.list().map { it.osuId }.toSet())
        assertEquals("1", source.findFirst()!!.osuId)
        assertEquals(3, source.count(all))
        assertEquals(1, source.count(Document("_id", "2")))
        assertEquals(1, source.deleteById("2").deletedCount)
        assertEquals(setOf("1", "3"), source.list().map { it.osuId }.toSet())
        assertEquals(2, source.deleteMany().deletedCount)
        assertEquals(0, source.count(all))
    }

    @Test
    fun `Should insert in batches of 100`() {
        val results = source.insertMany((1..205).map { beatmap("$it") })

        assertEquals(listOf(100, 100, 5), results.map { it.insertedCount })
        assertEquals(205, source.count(all))
        assertEquals(emptyList<Int>(), source.insertMany(emptyList()).map { it.insertedCount })
    }

    @Test
    fun `Should not insert a beatmap twice`() {
        source.insertOne(beatmap())

        assertThrows<Exception> { source.insertOne(beatmap()) }
    }
}
