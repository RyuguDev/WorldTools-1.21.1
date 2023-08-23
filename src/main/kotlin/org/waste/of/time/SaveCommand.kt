package org.waste.of.time

import com.mojang.brigadier.Command
import com.mojang.brigadier.context.CommandContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource
import net.minecraft.client.toast.SystemToast
import net.minecraft.nbt.NbtCompound
import net.minecraft.nbt.NbtIntArray
import net.minecraft.nbt.NbtList
import net.minecraft.text.Text
import net.minecraft.util.WorldSavePath
import org.waste.of.time.WorldTools.mc
import java.io.File
import java.net.InetSocketAddress


object SaveCommand : Command<FabricClientCommandSource> {
    override fun run(context: CommandContext<FabricClientCommandSource>?): Int {
        val player = context?.source?.player ?: return 0
        val levelName = (mc.networkHandler?.connection?.address as? InetSocketAddress)?.hostName.toString()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val session = mc.levelStorage.createSession(levelName)

                WorldTools.cachedChunks.groupBy { it.world }.forEach { chunkGroup ->
                    LevelPropertySerializer.backupLevelDataFile(session, levelName, player, chunkGroup.key)

                    val folder = dimensionFolder(chunkGroup.key.registryKey.value.path) + "region"
                    val path = session.getDirectory(WorldSavePath.ROOT).resolve(folder)

                    chunkGroup.value.forEach { chunk ->
                        CustomRegionBasedStorage(
                            path, false
                        ).write(chunk.pos, ClientChunkSerializer.serialize(chunk))
                    }
                }
                WorldTools.cachedEntities.groupBy { it.world }.forEach { worldGroup ->
                    val folder = dimensionFolder(worldGroup.key.registryKey.value.path) + "entities"

                    val path = session.getDirectory(WorldSavePath.ROOT).resolve(folder)

                    worldGroup.value.groupBy { it.chunkPos }.forEach { entityGroup ->
                        val entityMain = NbtCompound()
                        val entityList = NbtList()

                        entityGroup.value.forEach {
                            val entityNbt = NbtCompound()
                            it.writeNbt(entityNbt)
                            entityList.add(entityNbt)
                        }

                        entityMain.put("Entities", entityList)

                        entityMain.putInt("DataVersion", 3465)

                        val pos = NbtIntArray(intArrayOf(entityGroup.key.x, entityGroup.key.z))
                        entityMain.put("Position", pos)

                        CustomRegionBasedStorage(path, false).write(entityGroup.key, entityMain)
                    }
                }

                session.close()
            } catch (exception: Exception) {
                val message = Text.of("Save failed: ${exception.localizedMessage}")

                mc.toastManager.add(
                    SystemToast.create(
                        mc,
                        SystemToast.Type.WORLD_ACCESS_FAILURE,
                        WorldTools.NAME,
                        message
                    )
                )
                mc.inGameHud.chatHud.addMessage(message)
                return@launch
            }

            val successMessage = "Saved ${WorldTools.cachedChunks.size} chunks to world $levelName"

            mc.toastManager.add(
                SystemToast.create(
                    mc,
                    SystemToast.Type.WORLD_BACKUP,
                    WorldTools.NAME,
                    Text.of(successMessage)
                )
            )
            mc.inGameHud.chatHud.addMessage(Text.of(successMessage))
        }
        return 0
    }

    private fun dimensionFolder(dimension: String) = when (dimension) {
        "overworld" -> ""
        "the_nether" -> "DIM-1/"
        "the_end" -> "DIM1/"
        else -> "$dimension/"
    }
}
