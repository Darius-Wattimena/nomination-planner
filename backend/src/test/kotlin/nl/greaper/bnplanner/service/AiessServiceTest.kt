package nl.greaper.bnplanner.service

import nl.greaper.bnplanner.datasource.BeatmapDataSource
import nl.greaper.bnplanner.datasource.UserDataSource
import nl.greaper.bnplanner.model.Gamemode
import nl.greaper.bnplanner.model.User
import nl.greaper.bnplanner.model.aiess.AiessBeatmapEvent
import nl.greaper.bnplanner.model.beatmap.Beatmap
import nl.greaper.bnplanner.model.beatmap.BeatmapGamemode
import nl.greaper.bnplanner.model.beatmap.BeatmapNominator
import nl.greaper.bnplanner.model.beatmap.BeatmapStatus
import nl.greaper.bnplanner.service.UserService.Companion.MISSING_USER_ID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.spy
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.Instant

class AiessServiceTest {
    private lateinit var beatmapService: BeatmapService
    private lateinit var beatmapDataSource: BeatmapDataSource
    private lateinit var userService: UserService
    private lateinit var userDataSource: UserDataSource
    private lateinit var aiessService: AiessService

    @BeforeEach
    fun setup() {
        beatmapService = spy(BeatmapService(mock(), mock(), mock(), null))
        beatmapDataSource = mock()
        userService = mock()
        userDataSource = mock()
        aiessService = AiessService(beatmapService, beatmapDataSource, userService, userDataSource, null)
    }

    companion object {
        private val NOW = Instant.now()
        private const val BEATMAP_ID = "123456"
        private const val NOMINATOR_ID = "999"
        private const val NOMINATOR_NAME = "TestNominator"

        fun createBeatmap(
            id: String = BEATMAP_ID,
            status: BeatmapStatus = BeatmapStatus.Pending,
            gamemodes: List<BeatmapGamemode>,
        ): Beatmap =
            Beatmap(
                osuId = id,
                artist = "Test Artist",
                title = "Test Title",
                note = "",
                mapper = "Test Mapper",
                mapperId = "12345",
                status = status,
                gamemodes = gamemodes,
                dateAdded = NOW,
                dateUpdated = NOW,
                dateRanked = null,
            )

        fun createGamemode(
            gamemode: Gamemode,
            nominator1Id: String = MISSING_USER_ID,
            nominator1HasNominated: Boolean = false,
            nominator2Id: String = MISSING_USER_ID,
            nominator2HasNominated: Boolean = false,
            isReady: Boolean = false,
        ): BeatmapGamemode =
            BeatmapGamemode(
                gamemode = gamemode,
                nominators =
                    listOf(
                        BeatmapNominator(nominator1Id, nominator1HasNominated),
                        BeatmapNominator(nominator2Id, nominator2HasNominated),
                    ),
                isReady = isReady,
            )

        fun createEvent(
            beatmapSetId: String = BEATMAP_ID,
            status: BeatmapStatus,
            userId: String? = NOMINATOR_ID,
            username: String? = NOMINATOR_NAME,
            modes: List<Gamemode> = listOf(Gamemode.osu),
        ): AiessBeatmapEvent =
            AiessBeatmapEvent(
                beatmapSetId = beatmapSetId,
                artist = "Test Artist",
                title = "Test Title",
                mapper = "Test Mapper",
                mapperId = "12345",
                status = status,
                userId = userId,
                username = username,
                modes = modes,
            )
    }

    @Test
    fun `processAiessBeatmapEvent with Ranked status updates beatmap immediately`() {
        // Given
        val beatmap = createBeatmap(gamemodes = listOf(createGamemode(Gamemode.osu)))
        val event = createEvent(status = BeatmapStatus.Ranked, userId = null, username = null, modes = emptyList())

        doReturn(beatmap).whenever(beatmapService).findBeatmap(BEATMAP_ID)

        // When
        val result = aiessService.processAiessBeatmapEvent(event)

        // Then
        assertTrue(result.result)
        assertNull(result.error)

        val captor = argumentCaptor<Beatmap>()
        verify(beatmapDataSource).update(captor.capture())

        assertEquals(BeatmapStatus.Ranked, captor.firstValue.status)
        assertNotNull(captor.firstValue.dateRanked)
    }

    @Test
    fun `processAiessBeatmapEvent with Nominated status and no userId returns success without update`() {
        // Given
        val event = createEvent(status = BeatmapStatus.Nominated, userId = null, username = null)

        // When
        val result = aiessService.processAiessBeatmapEvent(event)

        // Then
        assertTrue(result.result)
        assertNull(result.error)
        verify(beatmapDataSource, never()).update(any())
    }

    @Test
    fun `processAiessBeatmapEvent with Disqualified status resets all nominators`() {
        // Given
        val beatmap =
            createBeatmap(
                gamemodes =
                    listOf(
                        createGamemode(Gamemode.osu, "100", true, "101", true),
                        createGamemode(Gamemode.taiko, "102", true, "103", false),
                    ),
            )
        val event = createEvent(status = BeatmapStatus.Disqualified)

        doReturn(beatmap).whenever(beatmapService).findBeatmap(BEATMAP_ID)

        // When
        val result = aiessService.processAiessBeatmapEvent(event)

        // Then
        assertTrue(result.result)
        assertNull(result.error)

        val captor = argumentCaptor<Beatmap>()
        verify(beatmapDataSource).update(captor.capture())

        val updatedBeatmap = captor.firstValue
        assertEquals(BeatmapStatus.Disqualified, updatedBeatmap.status)

        // All nominators should have hasNominated = false
        updatedBeatmap.gamemodes.forEach { gamemode ->
            gamemode.nominators.forEach { nominator ->
                assertFalse(
                    nominator.hasNominated,
                    "Nominator ${nominator.nominatorId} in ${gamemode.gamemode} should have hasNominated=false",
                )
            }
        }
    }

    @Test
    fun `processAiessBeatmapEvent with Reset status resets all nominators`() {
        // Given
        val beatmap =
            createBeatmap(
                gamemodes =
                    listOf(
                        createGamemode(Gamemode.osu, "100", true, "101", true),
                        createGamemode(Gamemode.taiko, "102", true, "103", false),
                    ),
            )
        val event = createEvent(status = BeatmapStatus.Reset)

        doReturn(beatmap).whenever(beatmapService).findBeatmap(BEATMAP_ID)

        // When
        val result = aiessService.processAiessBeatmapEvent(event)

        // Then
        assertTrue(result.result)
        assertNull(result.error)

        val captor = argumentCaptor<Beatmap>()
        verify(beatmapDataSource).update(captor.capture())

        val updatedBeatmap = captor.firstValue
        assertEquals(BeatmapStatus.Reset, updatedBeatmap.status)

        // All nominators should have hasNominated = false
        updatedBeatmap.gamemodes.forEach { gamemode ->
            gamemode.nominators.forEach { nominator ->
                assertFalse(
                    nominator.hasNominated,
                    "Nominator ${nominator.nominatorId} in ${gamemode.gamemode} should have hasNominated=false",
                )
            }
        }
    }

    @Test
    fun `processAiessBeatmapEvent with Nominated status and empty modes returns error`() {
        // Given
        val beatmap = createBeatmap(gamemodes = listOf(createGamemode(Gamemode.osu)))
        val event = createEvent(status = BeatmapStatus.Nominated, modes = emptyList())

        doReturn(beatmap).whenever(beatmapService).findBeatmap(BEATMAP_ID)

        // When
        val result = aiessService.processAiessBeatmapEvent(event)

        // Then
        assertFalse(result.result)
        assertEquals("No modes are provided", result.error)
        verify(beatmapDataSource, never()).update(any())
    }

    @Test
    fun `processAiessBeatmapEvent with Nominated status on single gamemode beatmap updates only that gamemode`() {
        // Given
        val beatmap =
            createBeatmap(
                gamemodes = listOf(createGamemode(Gamemode.osu)),
            )
        val event = createEvent(status = BeatmapStatus.Nominated, modes = listOf(Gamemode.osu))

        doReturn(beatmap).whenever(beatmapService).findBeatmap(BEATMAP_ID)
        doReturn(User(NOMINATOR_ID, NOMINATOR_NAME, emptyList())).whenever(userService).findUserById(NOMINATOR_ID)

        // When
        val result = aiessService.processAiessBeatmapEvent(event)

        // Then
        assertTrue(result.result)
        assertNull(result.error)

        val captor = argumentCaptor<Beatmap>()
        verify(beatmapDataSource).update(captor.capture())

        val updatedBeatmap = captor.firstValue
        val osuGamemode = updatedBeatmap.gamemodes.find { it.gamemode == Gamemode.osu }!!

        // First nominator should be assigned
        assertEquals(NOMINATOR_ID, osuGamemode.nominators[0].nominatorId)
        assertTrue(osuGamemode.nominators[0].hasNominated)
        assertEquals(MISSING_USER_ID, osuGamemode.nominators[1].nominatorId)
        assertFalse(osuGamemode.nominators[1].hasNominated)
    }

    @Test
    fun `processAiessBeatmapEvent with Nominated status on multi-gamemode beatmap updates only specified gamemode`() {
        // Given - beatmap with both osu and taiko gamemodes
        val beatmap =
            createBeatmap(
                gamemodes =
                    listOf(
                        createGamemode(Gamemode.osu),
                        createGamemode(Gamemode.taiko),
                    ),
            )
        // Event nominates only osu mode
        val event = createEvent(status = BeatmapStatus.Nominated, modes = listOf(Gamemode.osu))

        doReturn(beatmap).whenever(beatmapService).findBeatmap(BEATMAP_ID)
        doReturn(User(NOMINATOR_ID, NOMINATOR_NAME, emptyList())).whenever(userService).findUserById(NOMINATOR_ID)

        // When
        val result = aiessService.processAiessBeatmapEvent(event)

        // Then
        assertTrue(result.result)
        assertNull(result.error)

        val captor = argumentCaptor<Beatmap>()
        verify(beatmapDataSource).update(captor.capture())

        val updatedBeatmap = captor.firstValue

        // OSU gamemode should be updated
        val osuGamemode = updatedBeatmap.gamemodes.find { it.gamemode == Gamemode.osu }!!
        assertEquals(NOMINATOR_ID, osuGamemode.nominators[0].nominatorId)
        assertTrue(osuGamemode.nominators[0].hasNominated)

        // TAIKO gamemode should remain unchanged
        val taikoGamemode = updatedBeatmap.gamemodes.find { it.gamemode == Gamemode.taiko }!!
        assertEquals(MISSING_USER_ID, taikoGamemode.nominators[0].nominatorId)
        assertFalse(taikoGamemode.nominators[0].hasNominated)
        assertEquals(MISSING_USER_ID, taikoGamemode.nominators[1].nominatorId)
        assertFalse(taikoGamemode.nominators[1].hasNominated)
    }

    @Test
    fun `processAiessBeatmapEvent with Qualified status on multi-gamemode beatmap updates only specified gamemode`() {
        // Given - beatmap with both osu and taiko gamemodes
        val beatmap =
            createBeatmap(
                gamemodes =
                    listOf(
                        createGamemode(Gamemode.osu),
                        createGamemode(Gamemode.taiko),
                    ),
            )
        // Event qualifies only osu mode
        val event = createEvent(status = BeatmapStatus.Qualified, modes = listOf(Gamemode.osu))

        doReturn(beatmap).whenever(beatmapService).findBeatmap(BEATMAP_ID)
        doReturn(User(NOMINATOR_ID, NOMINATOR_NAME, emptyList())).whenever(userService).findUserById(NOMINATOR_ID)

        // When
        val result = aiessService.processAiessBeatmapEvent(event)

        // Then
        assertTrue(result.result)
        assertNull(result.error)

        val captor = argumentCaptor<Beatmap>()
        verify(beatmapDataSource).update(captor.capture())

        val updatedBeatmap = captor.firstValue

        // OSU gamemode should be updated
        val osuGamemode = updatedBeatmap.gamemodes.find { it.gamemode == Gamemode.osu }!!
        assertEquals(NOMINATOR_ID, osuGamemode.nominators[0].nominatorId)
        assertTrue(osuGamemode.nominators[0].hasNominated)

        // TAIKO gamemode should remain unchanged
        val taikoGamemode = updatedBeatmap.gamemodes.find { it.gamemode == Gamemode.taiko }!!
        assertEquals(MISSING_USER_ID, taikoGamemode.nominators[0].nominatorId)
        assertFalse(taikoGamemode.nominators[0].hasNominated)
        assertEquals(MISSING_USER_ID, taikoGamemode.nominators[1].nominatorId)
        assertFalse(taikoGamemode.nominators[1].hasNominated)
    }
}
