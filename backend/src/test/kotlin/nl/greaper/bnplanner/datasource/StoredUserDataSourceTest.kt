package nl.greaper.bnplanner.datasource

import nl.greaper.bnplanner.model.Gamemode
import nl.greaper.bnplanner.model.Role
import nl.greaper.bnplanner.model.User
import nl.greaper.bnplanner.model.UserGamemode
import nl.greaper.bnplanner.model.UserRecalculate
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

    private fun ids(found: Set<User>) = found.map { it.osuId }.toSet()

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
    fun `Should replace a saved user`() {
        users.saveUser(User("1", "Old", listOf(bn)))
        users.saveUser(User("1", "New", listOf(nat)))

        assertEquals(listOf(User("1", "New", listOf(nat))), users.list())
        assertEquals(User("1", "New", listOf(nat)), users.findUser("1"))
        assertEquals(emptySet<String>(), users.usersByGamemode[Gamemode.osu])
        assertEquals(setOf("1"), users.usersByGamemode[Gamemode.fruits])
    }

    @Test
    fun `Should load the stored users when starting`() {
        TestMongo.insert("users", """{"_id": "5", "username": "Five", "gamemodes": [{"gamemode": "mania", "role": "Probation"}]}""")
        val expected = User("5", "Five", listOf(UserGamemode(Gamemode.mania, Role.Probation)))

        val restarted = UserDataSource(TestMongo.database)
        assertEquals(expected, restarted.findUser("5"))
        assertEquals(setOf("5"), restarted.usersByGamemode[Gamemode.mania])
        // Users are only read when starting
        assertNull(users.findUser("5"))
    }

    @Test
    fun `Should delete a user`() {
        val user = User("1", "BN", listOf(bn, nat))
        users.saveUser(user)
        users.saveUser(User("2", "Other", listOf(bn)))
        users.deleteUser(user)

        assertNull(users.findUser("1"))
        assertEquals(setOf("2"), users.usersByGamemode[Gamemode.osu])
        assertEquals(emptySet<String>(), users.usersByGamemode[Gamemode.fruits])
        assertEquals(listOf("2"), users.list().map { it.osuId })
    }

    @Test
    fun `Should search users`() {
        users.saveUser(User("1", "Alice", listOf(bn)))
        users.saveUser(User("2", "Malice", listOf(nat, bn)))
        users.saveUser(User("3", "Bob", listOf(nat)))
        users.saveUser(User("4", "Mapper", listOf(mapper)))
        users.saveUser(User("5", "Both", listOf(bn, mapper)))
        users.saveUser(User("6", "No gamemodes", emptyList()))

        assertEquals(setOf("1", "2", "3"), ids(users.searchUser(null, emptySet(), emptySet())))
        assertEquals(setOf("1", "2"), ids(users.searchUser("ALICE", emptySet(), emptySet())))
        assertEquals(setOf("2", "3"), ids(users.searchUser(null, setOf(Gamemode.fruits), emptySet())))
        assertEquals(setOf("1", "2", "3"), ids(users.searchUser(null, setOf(Gamemode.fruits, Gamemode.osu), emptySet())))
        assertEquals(setOf("4", "5"), ids(users.searchUser(null, emptySet(), setOf(Role.Mapper))))
        assertEquals(setOf("1", "2", "3", "5"), ids(users.searchUser(null, emptySet(), setOf(Role.Nominator, Role.NominationAssessment))))
        assertEquals(setOf("2"), ids(users.searchUser("a", setOf(Gamemode.fruits), setOf(Role.Nominator))))
        assertEquals(emptySet<String>(), ids(users.searchUser("nobody", emptySet(), emptySet())))
    }

    @Test
    fun `SUSPECTED BUG - A role is found while it belongs to another gamemode than searched for`() {
        users.saveUser(User("2", "Osu BN and catch NAT", listOf(nat, bn)))

        assertEquals(setOf("2"), ids(users.searchUser(null, setOf(Gamemode.osu), setOf(Role.NominationAssessment))))
    }

    @Test
    fun `SUSPECTED BUG - Users without gamemodes can never be found`() {
        users.saveUser(User("6", "Former BN", emptyList()))

        assertEquals(emptySet<String>(), ids(users.searchUser("Former BN", emptySet(), emptySet())))
    }

    @Test
    fun `Should be safe to use from several requests at once`() {
        repeat(2000) { users.addInMemoryUser(User("$it", "Known", listOf(bn))) }
        val failures = ConcurrentLinkedQueue<Throwable>()

        fun start(action: () -> Unit) = thread { runCatching(action).onFailure { failures += it } }
        val readers = (1..4).map { start { repeat(100) { users.searchUser(null, emptySet(), emptySet()) } } }
        val writers = (1..4).map { writer -> start { repeat(500) { users.addInMemoryUser(User("$writer-$it", "New", listOf(bn))) } } }
        (readers + writers).forEach { it.join() }

        assertEquals(emptyList<Throwable>(), failures.toList())
        assertEquals(4000, users.usersByOsuId.size)
        assertEquals(4000, users.usersByGamemode[Gamemode.osu]!!.size)
    }

    @Test
    fun `Should cope with a gamemode missing in the cache`() {
        users.usersByGamemode.remove(Gamemode.osu)
        assertEquals(emptySet<User>(), users.searchUser(null, setOf(Gamemode.osu), emptySet()))

        users.addInMemoryUser(User("1", "BN", listOf(bn)))
        assertEquals(setOf("1"), users.usersByGamemode[Gamemode.osu])
    }

    @Test
    fun `Should store recalculations by the id of the user`() {
        planner.recalculations.insertOne(UserRecalculate("1"))

        assertEquals(listOf(Document("_id", "1")), TestMongo.documents("usersRecalculate"))
        assertEquals(UserRecalculate("1"), planner.recalculations.findFirst())
        assertEquals(1, planner.recalculations.deleteById("1").deletedCount)
    }

    @Test
    fun `Should store a token with a generated id`() {
        val token = AuthToken("Bearer", 86400, "made-up-access", "made-up-refresh")
        planner.tokens.insertOne(token)

        val stored = TestMongo.documents("authToken").single()
        assertTrue(stored["_id"] is ObjectId)
        stored.remove("_id")
        assertEquals(
            Document.parse(
                """{"token_type": "Bearer", "expires_in": 86400, "access_token": "made-up-access", "refresh_token": "made-up-refresh"}""",
            ),
            stored,
        )
        assertEquals(token, planner.tokens.findFirst())
        assertEquals(1, planner.tokens.deleteMany().deletedCount)
    }
}
