package nl.greaper.bnplanner.datasource

import nl.greaper.bnplanner.model.Gamemode
import nl.greaper.bnplanner.model.Role
import nl.greaper.bnplanner.model.User
import nl.greaper.bnplanner.model.UserGamemode
import nl.greaper.bnplanner.model.osu.AuthToken
import nl.greaper.bnplanner.support.Planner
import nl.greaper.bnplanner.support.TestMongo
import org.bson.Document
import org.bson.types.ObjectId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.concurrent.thread

/** Users, recalculations and tokens as stored in a real MongoDB */
class StoredUserDataSourceTest {
    private val planner = Planner()
    private val users = planner.users
    private val bn = UserGamemode(Gamemode.osu, Role.Nominator)
    private val nat = UserGamemode(Gamemode.fruits, Role.NominationAssessment)
    private val mapper = UserGamemode(Gamemode.taiko, Role.Mapper)

    @Test
    fun `Should store a user in the expected document shape`() {
        users.saveUser(User("1", "BN", listOf(bn, nat), restricted = true))

        val expected =
            """
            {"_id": "1", "username": "BN", "gamemodes": [{"gamemode": "osu", "role": "Nominator"},
            {"gamemode": "fruits", "role": "NominationAssessment"}], "restricted": true}
            """
        assertEquals(Document.parse(expected), TestMongo.documents("users").single())
    }

    @Test
    fun `Should store a missing restriction as null`() {
        users.saveUser(User("1", "BN", emptyList()))

        assertEquals(
            Document.parse("""{"_id": "1", "username": "BN", "gamemodes": [], "restricted": null}"""),
            TestMongo.documents("users").single(),
        )
    }

    @Test
    fun `Should store the modes a user is interested in`() {
        val user = User("1", "Mapper", emptyList(), interests = listOf(Gamemode.taiko, Gamemode.mania))
        users.saveUser(user)

        assertEquals(
            Document.parse("""{"_id": "1", "username": "Mapper", "gamemodes": [], "restricted": null, "interests": ["taiko", "mania"]}"""),
            TestMongo.documents("users").single(),
        )
        assertEquals(user, UserDataSource(TestMongo.database).findUser("1"))
    }

    @Test
    fun `Should replace a saved user`() {
        users.saveUser(User("1", "Old", listOf(bn)))
        users.saveUser(User("1", "New", listOf(nat)))

        assertEquals(listOf(User("1", "New", listOf(nat))), users.list())
        assertEquals(User("1", "New", listOf(nat)), users.findUser("1"))
        assertEquals(listOf(User("1", "New", listOf(nat))), users.allUsers())
    }

    @Test
    fun `Should load the stored users when starting`() {
        TestMongo.insert("users", """{"_id": "5", "username": "Five", "gamemodes": [{"gamemode": "mania", "role": "Probation"}]}""")
        val expected = User("5", "Five", listOf(UserGamemode(Gamemode.mania, Role.Probation)))

        val restarted = UserDataSource(TestMongo.database)
        assertEquals(expected, restarted.findUser("5"))
        // Users are only read when starting
        assertNull(users.findUser("5"))
    }

    @Test
    fun `Should be safe to use from several requests at once`() {
        repeat(2000) { users.addInMemoryUser(User("$it", "Known", listOf(bn))) }
        val failures = ConcurrentLinkedQueue<Throwable>()

        fun start(action: () -> Unit) = thread { runCatching(action).onFailure { failures += it } }
        val readers = (1..4).map { start { repeat(100) { users.allUsers().forEach { users.findUser(it.osuId) } } } }
        val writers = (1..4).map { writer -> start { repeat(500) { users.addInMemoryUser(User("$writer-$it", "New", listOf(bn))) } } }
        (readers + writers).forEach { it.join() }

        assertEquals(emptyList<Throwable>(), failures.toList())
        assertEquals(4000, users.allUsers().size)
    }
}
