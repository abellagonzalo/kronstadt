package kronstadt.core.application

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import kronstadt.core.domain.CodeSymbol
import kronstadt.core.domain.Position
import kronstadt.core.domain.SymbolKind
import kronstadt.core.domain.SymbolLocation
import kronstadt.core.domain.TextRange
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** `kt_find_symbol`: the index finds names; each file's `documentSymbol` tree gives the canonical Symbol Path. */
class FindSymbolTest {
    private val root = Path.of("/project")

    private fun range(startLine: Int, startChar: Int, endLine: Int, endChar: Int) =
        TextRange(Position(startLine, startChar), Position(endLine, endChar))

    private fun symbol(name: String, kind: SymbolKind, range: TextRange, nameLine: Int, nameChar: Int, detail: String? = null, children: List<CodeSymbol> = emptyList()) =
        CodeSymbol(name, kind, range, range(nameLine, nameChar, nameLine, nameChar + name.length), children, detail)

    private val userKt = "src/main/kotlin/com/example/User.kt"
    private val userSource = """
        |package com.example
        |
        |interface UserRepository {
        |    fun find(id: Int): String
        |}
        |
        |class UserRepositoryImpl : UserRepository {
        |    override fun find(id: Int): String = ""
        |}
        |""".trimMargin()
    private val interfaceFind = symbol("find", SymbolKind.FUNCTION, range(3, 4, 3, 29), 3, 8, "(Int): String")
    private val repository = symbol("UserRepository", SymbolKind.INTERFACE, range(2, 0, 4, 1), 2, 10, children = listOf(interfaceFind))
    private val implFind = symbol("find", SymbolKind.FUNCTION, range(7, 4, 7, 43), 7, 17, "(Int): String")
    private val repositoryImpl = symbol("UserRepositoryImpl", SymbolKind.CLASS, range(6, 0, 8, 1), 6, 6, children = listOf(implFind))

    private val lookupKt = "src/main/kotlin/com/example/Lookup.kt"
    private val lookupSource = """
        |package com.example
        |
        |fun findUserRepository(): UserRepository {
        |    fun local(): Int = 1
        |    return UserRepositoryImpl()
        |}
        |fun parse(s: String): Int = 1
        |fun parse(i: Int): Int = i
        |""".trimMargin()
    // kotlin-lsp omits local functions from documentSymbol.
    private val findUserRepository = symbol("findUserRepository", SymbolKind.FUNCTION, range(2, 0, 5, 1), 2, 4, "(): UserRepository")
    private val parseString = symbol("parse", SymbolKind.FUNCTION, range(6, 0, 6, 29), 6, 4, "(String): Int")
    private val parseInt = symbol("parse", SymbolKind.FUNCTION, range(7, 0, 7, 26), 7, 4, "(Int): Int")

    private val files = FakeFileStore(root, mapOf(userKt to userSource, lookupKt to lookupSource, "build.gradle.kts" to ""))

    private fun hit(file: String, symbol: CodeSymbol) =
        SymbolLocation(symbol.name, symbol.kind, root.resolve(file), symbol.selectionRange)

    private val index = listOf(
        hit(lookupKt, findUserRepository),
        hit(userKt, repositoryImpl),
        hit(userKt, repository),
        hit(userKt, interfaceFind),
        hit(userKt, implFind),
        hit(lookupKt, parseString),
        hit(lookupKt, parseInt),
        SymbolLocation("local", SymbolKind.FUNCTION, root.resolve(lookupKt), range(3, 8, 3, 13)),
    )

    private fun intelligence(index: List<SymbolLocation> = this.index, notReadyPhase: String? = null) = FakeCodeIntelligence(
        index = index,
        notReadyPhase = notReadyPhase,
        symbolsByFile = mapOf(
            root.resolve(userKt) to listOf(repository, repositoryImpl),
            root.resolve(lookupKt) to listOf(findUserRepository, parseString, parseInt),
        ),
    )

    private fun found(kind: SymbolKind, path: String, firstLine: Int, lastLine: Int) = FoundSymbol(kind, path, firstLine, lastLine)

    @Test
    fun `ranks exact matches first, then prefix matches, grouped by file with canonical paths`() = runTest {
        val result = assertIs<FindSymbolResult.Found>(FindSymbol(files, intelligence())("userrepository"))
        assertEquals(
            listOf(
                FileMatches(
                    userKt,
                    listOf(found(SymbolKind.INTERFACE, "UserRepository", 3, 5), found(SymbolKind.CLASS, "UserRepositoryImpl", 7, 9)),
                ),
                FileMatches(lookupKt, listOf(found(SymbolKind.FUNCTION, "findUserRepository", 3, 6))),
            ),
            result.files,
        )
        assertEquals(false, result.truncated)
    }

    @Test
    fun `paths carry enclosing declarations and signatures only where a name alone is ambiguous`() = runTest {
        val finds = assertIs<FindSymbolResult.Found>(FindSymbol(files, intelligence())("find", SymbolKind.FUNCTION))
        assertEquals(
            listOf("UserRepository/find", "UserRepositoryImpl/find"),
            finds.files.single { it.file == userKt }.symbols.map { it.path },
        )
        val parses = assertIs<FindSymbolResult.Found>(FindSymbol(files, intelligence())("parse"))
        assertEquals(listOf("parse(Int)", "parse(String)"), parses.files.single().symbols.map { it.path }.sorted())
    }

    @Test
    fun `filters by kind and truncates`() = runTest {
        val findSymbol = FindSymbol(files, intelligence())
        val classes = assertIs<FindSymbolResult.Found>(findSymbol("User", SymbolKind.CLASS))
        assertEquals(listOf("UserRepositoryImpl"), classes.files.flatMap { it.symbols }.map { it.path })

        val limited = assertIs<FindSymbolResult.Found>(findSymbol("User", limit = 1))
        assertEquals(listOf("UserRepository"), limited.files.flatMap { it.symbols }.map { it.path })
        assertTrue(limited.truncated)
    }

    @Test
    fun `returns only addressable declarations in Kotlin files inside the project`() = runTest {
        val outside = SymbolLocation("UserCache", SymbolKind.CLASS, Path.of("/elsewhere/UserCache.kt"), range(0, 6, 0, 15))
        val script = SymbolLocation("UserTask", SymbolKind.CLASS, root.resolve("build.gradle"), range(0, 6, 0, 14))
        val deleted = SymbolLocation("UserGone", SymbolKind.CLASS, root.resolve("src/Gone.kt"), range(0, 6, 0, 14))
        val findSymbol = FindSymbol(files, intelligence(index + listOf(outside, script, deleted)))

        val found = assertIs<FindSymbolResult.Found>(findSymbol("User"))
        assertEquals(setOf(userKt, lookupKt), found.files.map { it.file }.toSet())
        // A local function is in the index but isn't addressable.
        assertIs<FindSymbolResult.NoMatches>(findSymbol("local"))
    }

    @Test
    fun `closes every document it analysed`() = runTest {
        val intelligence = intelligence()
        FindSymbol(files, intelligence)("User")
        assertEquals(setOf(root.resolve(userKt), root.resolve(lookupKt)), intelligence.analysed.map { it.first }.toSet())
        assertEquals(emptySet(), intelligence.openDocuments)
    }

    @OptIn(ExperimentalCoroutinesApi::class) // testTimeSource
    @Test
    fun `waits until the index is ready, then answers fully`() = runTest {
        val intelligence = intelligence(notReadyPhase = "Indexing")
        val start = testTimeSource.markNow()
        launch {
            delay(30.seconds)
            intelligence.becomeReady()
        }
        val found = assertIs<FindSymbolResult.Found>(FindSymbol(files, intelligence, readinessTimeout = 90.seconds)("UserRepository"))
        assertEquals(3, found.files.sumOf { it.symbols.size })
        assertEquals(30.seconds, start.elapsedNow())
    }

    @Test
    fun `fails with the phase and elapsed time when the index is not ready in time, without searching`() = runTest {
        val intelligence = intelligence(notReadyPhase = "Indexing")
        val result = FindSymbol(files, intelligence, readinessTimeout = 90.seconds)("UserRepository")
        assertEquals(FindSymbolResult.NotReady(phase = "Indexing", elapsed = 90.seconds), result)
        assertEquals(emptyList(), intelligence.searched)
    }

    @Test
    fun `reports no matches, blank queries and limits below one`() = runTest {
        assertIs<FindSymbolResult.NoMatches>(FindSymbol(files, intelligence())("Nope"))
        assertIs<FindSymbolResult.InvalidQuery>(FindSymbol(files, intelligence())(" "))
        assertIs<FindSymbolResult.InvalidQuery>(FindSymbol(files, intelligence())("User", limit = 0))
    }
}
