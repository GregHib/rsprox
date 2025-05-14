package net.rsprox.proxy.cli

import com.github.ajalt.clikt.core.CliktCommand
import net.rsprot.protocol.message.IncomingMessage
import net.rsprox.cache.Js5MasterIndex
import net.rsprox.cache.resolver.HistoricCacheResolver
import net.rsprox.protocol.game.outgoing.model.misc.client.ServerTickEnd
import net.rsprox.proxy.binary.BinaryBlob
import net.rsprox.proxy.cache.StatefulCacheProvider
import net.rsprox.proxy.config.BINARY_PATH
import net.rsprox.proxy.config.FILTERS_DIRECTORY
import net.rsprox.proxy.config.SETTINGS_DIRECTORY
import net.rsprox.proxy.downloader.cpp.Metafile
import net.rsprox.proxy.filters.DefaultPropertyFilterSetStore
import net.rsprox.proxy.huffman.HuffmanProvider
import net.rsprox.proxy.plugin.DecoderLoader
import net.rsprox.proxy.plugin.DecodingSession
import net.rsprox.proxy.settings.DefaultSettingSetStore
import net.rsprox.proxy.util.NopSessionMonitor
import net.rsprox.shared.StreamDirection
import net.rsprox.transcriber.state.SessionState
import net.rsprox.transcriber.state.SessionTracker
import java.io.File
import java.nio.file.Path
import kotlin.io.path.nameWithoutExtension

public abstract class Transcriber(name: String) : CliktCommand(name), Runnable {

    override fun run() {
        val decoderLoader = DecoderLoader()
        HuffmanProvider.load()
        val provider = StatefulCacheProvider(HistoricCacheResolver())
        val filters = DefaultPropertyFilterSetStore.load(FILTERS_DIRECTORY)
        val settings = DefaultSettingSetStore.load(SETTINGS_DIRECTORY)
        val fileTreeWalk =
            BINARY_PATH
                .toFile()
                .walkTopDown()
                .filter { it.extension == "bin" && filter(it) }
                .map { it.toPath() }
                .map { it to BinaryBlob.decode(it, filters, settings) }
                .sortedBy { it.second.header.revision }
        for ((path, blob) in fileTreeWalk) {
            simpleTranscribe(path, blob, decoderLoader, provider)
        }
    }

    public open fun filter(path: File): Boolean {
        return true
    }

    private fun simpleTranscribe(
        binaryPath: Path,
        binary: BinaryBlob,
        decoderLoader: DecoderLoader,
        statefulCacheProvider: StatefulCacheProvider,
    ) {
        statefulCacheProvider.update(
            Js5MasterIndex.trimmed(
                binary.header.revision,
                binary.header.js5MasterIndex,
            ),
        )
        decoderLoader.load(statefulCacheProvider)
        val latestPlugin = decoderLoader.getDecoder(binary.header.revision)
        val session = DecodingSession(binary, latestPlugin)
        val sessionState = SessionState(binary.header.revision, DefaultSettingSetStore(binaryPath))
        val sessionTracker =
            SessionTracker(
                sessionState,
                statefulCacheProvider.get(),
                NopSessionMonitor,
            )
        var tick = 0
        for ((direction, prot, packet) in session.sequence()) {
            if (tick != 0) {
                processPacket(tick, sessionState, packet)
            }
            when (direction) {
                StreamDirection.CLIENT_TO_SERVER -> {
                    sessionTracker.onClientPacket(packet, prot)
                    sessionTracker.beforeTranscribe(packet)
                    sessionTracker.afterTranscribe(packet)
                }
                StreamDirection.SERVER_TO_CLIENT -> {
                    sessionTracker.onServerPacket(packet, prot)
                    sessionTracker.beforeTranscribe(packet)
                    sessionTracker.afterTranscribe(packet)
                }
            }
            if (packet is ServerTickEnd) {
                tick++
            }
        }
        echo("Binary file decoded ${binaryPath.nameWithoutExtension}")
    }

    public abstract fun processPacket(tick: Int, sessionState: SessionState, packet: IncomingMessage)
}
