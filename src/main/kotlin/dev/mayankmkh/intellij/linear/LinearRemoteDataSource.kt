package dev.mayankmkh.intellij.linear

import com.apollographql.apollo3.ApolloClient
import com.apollographql.apollo3.api.Optional
import com.apollographql.apollo3.api.Query
import com.intellij.tasks.CustomTaskState
import com.intellij.tasks.Task
import dev.mayankmkh.intellij.linear.apolloGenerated.GetIssueStatesQuery
import dev.mayankmkh.intellij.linear.apolloGenerated.GetPageInfoQuery
import dev.mayankmkh.intellij.linear.apolloGenerated.GetSearchIssuesPageInfoQuery
import dev.mayankmkh.intellij.linear.apolloGenerated.GetTeamByKeyQuery
import dev.mayankmkh.intellij.linear.apolloGenerated.IssuesQuery
import dev.mayankmkh.intellij.linear.apolloGenerated.SearchIssuesQuery
import dev.mayankmkh.intellij.linear.apolloGenerated.TestConnectionQuery
import dev.mayankmkh.intellij.linear.apolloGenerated.UpdateIssueStateMutation
import dev.mayankmkh.intellij.linear.apolloGenerated.fragment.PageInfoIssueConnection
import dev.mayankmkh.intellij.linear.apolloGenerated.fragment.ShortIssueConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.util.logging.Logger

class LinearRemoteDataSource(private val apolloClient: ApolloClient) {
    suspend fun getTeamIdByKey(teamKey: String): String =
        withContext(Dispatchers.IO) {
            val response = apolloClient.query(GetTeamByKeyQuery()).execute()
            response.errors?.getOrNull(0)?.let {
                throw IllegalArgumentException(it.message)
            }
            val team = response.data?.teams?.nodes?.find { it.key == teamKey }
            require(team != null) { "Team with key '$teamKey' not found" }
            team.id
        }

    fun getIssues(
        teamId: String,
        query: String?,
        offset: Int,
        limit: Int,
        withClosed: Boolean,
    ): List<ShortIssueConnection.Node> {
        // FIXME: 04/04/21 Ignoring withClosed for now
        LOG.info(
            "getIssues called with teamId: $teamId, query: $query, " +
                "offset: $offset, limit: $limit, withClosed: $withClosed",
        )
        return try {
            val result = runBlocking(Job()) { getIssues(teamId, query, offset, limit) }
            LOG.info("getIssues returned ${result.size} issues")
            result
        } catch (e: InterruptedException) {
            LOG.severe("InterruptedException in getIssues: ${e.message}")
            emptyList()
        } catch (e: IllegalArgumentException) {
            LOG.severe("IllegalArgumentException in getIssues: ${e.message}")
            LOG.severe("Stack trace: ${e.stackTraceToString()}")
            emptyList()
        } catch (e: IllegalStateException) {
            LOG.severe("IllegalStateException in getIssues: ${e.message}")
            LOG.severe("Stack trace: ${e.stackTraceToString()}")
            emptyList()
        }
    }

    private suspend fun getIssues(
        teamId: String,
        query: String?,
        offset: Int,
        limit: Int,
    ): List<ShortIssueConnection.Node> {
        return if (query.isNullOrBlank()) {
            val pageInfo = if (offset > 0) getIssuesPageInfo(teamId, offset) else null
            getIssuesInternal(
                limit,
                pageInfo,
                createQuery = { numberOfItems, endCursor -> IssuesQuery(teamId, numberOfItems, endCursor) },
                getShortIssueConnection = { it.team?.issues?.shortIssueConnection },
            )
        } else {
            val pageInfo = if (offset > 0) getSearchIssuesPageInfo(teamId, query, offset) else null
            getIssuesInternal(
                limit,
                pageInfo,
                createQuery = { numberOfItems, endCursor ->
                    SearchIssuesQuery(
                        query,
                        teamId,
                        numberOfItems,
                        endCursor,
                    )
                },
                getShortIssueConnection = { it.team?.issues?.shortIssueConnection },
            )
        }
    }

    private suspend fun <D : Query.Data> getIssuesInternal(
        limit: Int,
        initialIssuePageInfo: PageInfoIssueConnection.PageInfo?,
        createQuery: (offset: Int, endCursor: Optional<String>) -> Query<D>,
        getShortIssueConnection: (data: D) -> ShortIssueConnection?,
    ) = withContext(Dispatchers.IO) {
        var pageInfo = initialIssuePageInfo ?: PageInfoIssueConnection.PageInfo(hasNextPage = true, endCursor = null)
        LOG.info("pageInfo: $pageInfo")

        var remainingIssues = limit
        val list: MutableList<ShortIssueConnection.Node> = ArrayList(limit)

        while (remainingIssues > 0 && pageInfo.hasNextPage) {
            LOG.info("remainingIssues: $remainingIssues")
            val numberOfItems = remainingIssues.coerceAtMost(BATCH_SIZE)
            val issuesQuery = createQuery(numberOfItems, Optional.presentIfNotNull(pageInfo.endCursor))
            LOG.info("Executing query: ${issuesQuery.name()}")
            val response = apolloClient.query(issuesQuery).execute()

            if (response.hasErrors()) {
                LOG.severe("GraphQL errors: ${response.errors?.joinToString { it.message }}")
            }

            val data = response.data
            val shortIssueConnection = data?.let { getShortIssueConnection(it) }

            if (data == null || shortIssueConnection == null) {
                LOG.warning("Response data or ShortIssueConnection is null, breaking")
                break
            }

            val nodes = shortIssueConnection.nodes
            LOG.info("Fetched ${nodes.size} nodes")

            list.addAll(nodes)
            pageInfo = shortIssueConnection.pageInfoIssueConnection.pageInfo
            remainingIssues -= nodes.size
            LOG.info("pageInfo: $pageInfo")
        }

        LOG.info("list: " + list.joinToString { it.identifier })
        list
    }

    private suspend fun getIssuesPageInfo(
        teamId: String,
        offset: Int,
    ): PageInfoIssueConnection.PageInfo? {
        return getPageInfoInternal(
            offset,
            createQuery = { pageOffset, endCursor -> GetPageInfoQuery(teamId, pageOffset, endCursor) },
            getPageInfoIssueConnection = { it.team?.issues?.pageInfoIssueConnection },
        )
    }

    private suspend fun getSearchIssuesPageInfo(
        teamId: String,
        query: String,
        offset: Int,
    ): PageInfoIssueConnection.PageInfo? {
        return getPageInfoInternal(
            offset,
            createQuery = { pageOffset, endCursor ->
                GetSearchIssuesPageInfoQuery(
                    query,
                    teamId,
                    pageOffset,
                    endCursor,
                )
            },
            getPageInfoIssueConnection = { it.team?.issues?.pageInfoIssueConnection },
        )
    }

    private suspend fun <D : Query.Data> getPageInfoInternal(
        startOffset: Int,
        createQuery: (offset: Int, endCursor: Optional<String>) -> Query<D>,
        getPageInfoIssueConnection: (data: D) -> PageInfoIssueConnection?,
    ): PageInfoIssueConnection.PageInfo? =
        withContext(Dispatchers.IO) {
            var pendingOffset = startOffset
            val firstPageInfo = PageInfoIssueConnection.PageInfo(hasNextPage = true, endCursor = null)
            var pageInfo = firstPageInfo

            while (pendingOffset > 0 && pageInfo.hasNextPage) {
                val pageOffset = pendingOffset.coerceAtMost(MAX_COUNT)
                val getPageInfoQuery = createQuery(pageOffset, Optional.presentIfNotNull(pageInfo.endCursor))
                val response = apolloClient.query(getPageInfoQuery).execute()
                val data = response.data
                val connection = data?.let { getPageInfoIssueConnection(it) }

                if (data == null || connection == null) break

                pageInfo = connection.pageInfo
                pendingOffset -= pageOffset
            }

            if (pageInfo === firstPageInfo) null else pageInfo
        }

    suspend fun testConnection(teamKey: String) =
        withContext(Dispatchers.IO) {
            val response = apolloClient.query(TestConnectionQuery()).execute()
            response.errors?.getOrNull(0)?.let {
                throw IllegalArgumentException(it.message)
            }
            val teams = response.data?.teams?.nodes
            require(!teams.isNullOrEmpty()) { "No teams found" }
            val team = teams.find { it.key == teamKey }
            require(team != null) { "Team with key '$teamKey' not found" }
        }

    suspend fun getAvailableTaskStates(task: Task): MutableSet<CustomTaskState> =
        withContext(Dispatchers.IO) {
            val response = apolloClient.query(GetIssueStatesQuery(task.id)).execute()
            response.errors?.getOrNull(0)?.let {
                throw IllegalArgumentException(it.message)
            }
            response.data?.issue?.team?.states?.nodes?.map { CustomTaskState(it.id, it.name) }?.toMutableSet()
                ?: mutableSetOf()
        }

    suspend fun setTaskState(
        task: Task,
        state: CustomTaskState,
    ): Unit =
        withContext(Dispatchers.IO) {
            val response = apolloClient.mutation(UpdateIssueStateMutation(task.id, state.id)).execute()
            response.errors?.getOrNull(0)?.let {
                throw IllegalArgumentException(it.message)
            }
            check(response.data?.issueUpdate?.success == true) {
                "State could not be updated for Task ${task.id} to ${state.presentableName}"
            }
        }

    companion object {
        private val LOG: Logger = Logger.getLogger(LinearRemoteDataSource::class.simpleName)
        private const val BATCH_SIZE = 50
        private const val MAX_COUNT = 250
    }
}
