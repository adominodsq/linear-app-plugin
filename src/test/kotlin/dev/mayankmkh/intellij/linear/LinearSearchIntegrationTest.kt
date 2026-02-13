package dev.mayankmkh.intellij.linear

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.io.File

/**
 * Integration tests for Linear API search functionality.
 * These tests require valid credentials in a .env file or environment variables.
 *
 * Required variables:
 * - LINEAR_API_KEY: Your Linear API key
 * - LINEAR_TEAM_KEY: The team key (e.g., "DIS")
 *
 * These tests use data from our server, so you should customize them using tasks
 * from your server if you want to run them against your own Linear instance
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LinearSearchIntegrationTest {
    private lateinit var remoteDataSource: LinearRemoteDataSource
    private lateinit var teamId: String

    companion object {
        private const val API_URL = "https://api.linear.app/graphql"

        /**
         * Simple .env file parser. Reads key=value pairs, ignoring comments and blank lines.
         */
        private fun loadEnvFile(): Map<String, String> {
            val envFile = File(".env")
            if (!envFile.exists()) return emptyMap()

            return envFile.readLines()
                .filter { it.isNotBlank() && !it.startsWith("#") }
                .mapNotNull { line ->
                    val parts = line.split("=", limit = 2)
                    if (parts.size == 2) {
                        parts[0].trim() to parts[1].trim()
                    } else {
                        null
                    }
                }
                .toMap()
        }
    }

    @BeforeAll
    fun setup() {
        val envVars = loadEnvFile()

        val apiKey = envVars["LINEAR_API_KEY"] ?: System.getenv("LINEAR_API_KEY")
        val teamKey = envVars["LINEAR_TEAM_KEY"] ?: System.getenv("LINEAR_TEAM_KEY")

        assumeTrue(
            !apiKey.isNullOrBlank() && !teamKey.isNullOrBlank(),
            "Skipping integration tests: LINEAR_API_KEY and LINEAR_TEAM_KEY must be set in .env or environment",
        )

        val apiKeyProvider = ApiKeyProvider { apiKey }
        val apolloClient = createApolloClient(API_URL, apiKeyProvider)
        remoteDataSource = LinearRemoteDataSource(apolloClient)

        teamId =
            runBlocking {
                remoteDataSource.getTeamIdByKey(teamKey)
            }
        assertNotNull(teamId, "Failed to resolve team ID for team key: $teamKey")
    }

    @Test
    fun `search by text returns matching issues`() {
        // Search for a common word that should return results
        val issues =
            remoteDataSource.getIssues(
                teamId = teamId,
                query = "vendor",
                offset = 0,
                limit = 50000,
                withClosed = false,
            )

        assertTrue(issues.isNotEmpty(), "Expected to find issues matching 'vendor'")

        // Verify that DIS-1673 is in the results (it has "vendor" in title/description)
        val identifiers = issues.map { it.identifier }
        assertTrue(
            identifiers.contains("DIS-1673"),
            "Expected DIS-1673 to be in results for 'vendor' search. Found: $identifiers",
        )
    }

    @Test
    fun `search by issue identifier returns the specific issue`() {
        // Search using the full identifier format (e.g., "DIS-1673")
        val issues =
            remoteDataSource.getIssues(
                teamId = teamId,
                query = "DIS-1673",
                offset = 0,
                limit = 10,
                withClosed = false,
            )

        assertTrue(issues.isNotEmpty(), "Expected to find issue DIS-1673 by identifier")

        val identifiers = issues.map { it.identifier }
        assertTrue(
            identifiers.contains("DIS-1673"),
            "Expected DIS-1673 in results when searching by identifier. Found: $identifiers",
        )
    }

    @Test
    fun `search by issue number only returns the specific issue`() {
        // Search using just the number (e.g., "1673")
        val issues =
            remoteDataSource.getIssues(
                teamId = teamId,
                query = "1673",
                offset = 0,
                limit = 10,
                withClosed = false,
            )

        assertTrue(issues.isNotEmpty(), "Expected to find issue by number '1673'")

        val identifiers = issues.map { it.identifier }
        assertTrue(
            identifiers.contains("DIS-1673"),
            "Expected DIS-1673 in results when searching by number. Found: $identifiers",
        )
    }

    @Test
    fun `search without query returns recent issues`() {
        // Search with empty query should return issues ordered by updatedAt
        val issues =
            remoteDataSource.getIssues(
                teamId = teamId,
                query = null,
                offset = 0,
                limit = 10,
                withClosed = false,
            )

        assertTrue(issues.isNotEmpty(), "Expected to find recent issues with empty query")
        assertTrue(issues.size <= 10, "Expected at most 10 issues")
    }

    @Test
    fun `search respects limit parameter`() {
        val limit = 5
        val issues =
            remoteDataSource.getIssues(
                teamId = teamId,
                query = null,
                offset = 0,
                limit = limit,
                withClosed = false,
            )

        assertTrue(issues.size <= limit, "Expected at most $limit issues, got ${issues.size}")
    }
}
