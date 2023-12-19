package org.waste.of.time.event

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import net.minecraft.util.path.SymlinkValidationException
import org.waste.of.time.MessageManager
import org.waste.of.time.StatisticManager
import org.waste.of.time.WorldTools.LOG
import org.waste.of.time.WorldTools.mc
import org.waste.of.time.event.serializable.MetadataStoreable
import org.waste.of.time.storage.CustomRegionBasedStorage
import java.io.IOException
import java.util.concurrent.CancellationException
import kotlin.time.Duration
import kotlin.time.measureTime

object StorageFlow {
    private const val MAX_BUFFER_SIZE = 1000
    var lastStoredTimestamp: Long = 0
    var lastStored: Storeable? = null
    var lastStoredTimeNeeded: Duration = Duration.ZERO

    private val sharedFlow = MutableSharedFlow<Storeable>(
        extraBufferCapacity = MAX_BUFFER_SIZE,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    fun emit(storeable: Storeable) {
        sharedFlow.tryEmit(storeable)
    }

    fun launch(levelName: String) = CoroutineScope(Dispatchers.IO).launch {
        StatisticManager.reset()
        val cachedStorages = mutableMapOf<String, CustomRegionBasedStorage>()

        try {
            LOG.info("Started caching")
            mc.levelStorage.createSession(levelName).use { openSession ->
                sharedFlow.collect { storeable ->
                    val time = measureTime {
                        storeable.store(openSession, cachedStorages)
                    }

                    lastStored = storeable
                    lastStoredTimestamp = System.currentTimeMillis()
                    lastStoredTimeNeeded = time

                    if (storeable is MetadataStoreable) {
                        throw StopCollectingException()
                    }
                }
            }
        } catch (e: StopCollectingException) {
            LOG.info("Canceled caching flow")
        } catch (e: IOException) {
            MessageManager.sendError("error.failed_to_create_session", levelName)
            LOG.error("Failed to create session for $levelName", e)
        } catch (e: SymlinkValidationException) {
            MessageManager.sendError("error.failed_to_create_session", levelName)
            LOG.error("Failed to create session for $levelName", e)
        } catch (e: CancellationException) {
            LOG.info("Canceled caching thread")
        }

        cachedStorages.values.forEach { it.close() }
        LOG.info("Finished caching")
    }

    class StopCollectingException : Exception()
}
