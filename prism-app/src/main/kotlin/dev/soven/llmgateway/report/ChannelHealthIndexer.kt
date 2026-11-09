package dev.soven.llmgateway.report

import dev.soven.llmgateway.repo.RequestLogRepository
import dev.soven.llmgateway.requestlog.domain.healthWindows
import kotlinx.coroutines.*
import org.slf4j.LoggerFactory

/** One bounded background pass after upgrade; persistent flags make restarts harmless. */
fun startChannelHealthIndexer(repository: RequestLogRepository, scope: CoroutineScope): Job = scope.launch {
    val logger = LoggerFactory.getLogger("ChannelHealthIndexer")
    while (isActive) {
        try {
            val count = repository.indexHealthBatch(System.currentTimeMillis() - healthWindows.getValue("30d"))
            if (count == 0) break
            delay(250)
        } catch (cause: CancellationException) {
            throw cause
        } catch (cause: Exception) {
            logger.error("channel health historical indexing failed; retrying in 30s", cause)
            delay(30_000)
        }
    }
}
