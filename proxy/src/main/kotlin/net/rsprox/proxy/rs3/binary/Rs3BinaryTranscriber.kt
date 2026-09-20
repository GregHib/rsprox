package net.rsprox.proxy.rs3.binary

import net.rsprot.buffer.extensions.toJagByteBuf
import net.rsprot.crypto.cipher.NopStreamCipher
import net.rsprot.protocol.ClientProt
import net.rsprot.protocol.message.IncomingMessage
import net.rsprox.cache.clientscript.RSProxArchiveClientScriptIndex
import net.rsprox.cache.rs3.Rs3LiveCacheResolver
import net.rsprox.protocol.rs3.cache.rs3PacketDefinitions
import net.rsprox.protocol.rs3.game.incoming.model.unknown.RawUnknownClientPacket
import net.rsprox.protocol.rs3.game.outgoing.model.info.playerinfo.rs3AppearanceDefinitions
import net.rsprox.protocol.rs3.game.outgoing.model.info.playerinfo.rs3PlayerInfoInitPending
import net.rsprox.protocol.rs3.game.outgoing.model.unknown.RawUnknownServerPacket
import net.rsprox.protocol.session.AttributeMap
import net.rsprox.protocol.session.Session
import net.rsprox.proxy.binary.BinaryBlob
import net.rsprox.proxy.binary.BinaryStream
import net.rsprox.proxy.cli.TranscribeCommand
import net.rsprox.proxy.huffman.HuffmanProvider
import net.rsprox.proxy.rs3.Rs3DecoderLoader
import net.rsprox.proxy.rs3.gameval.Rs3GamevalLookup
import net.rsprox.proxy.util.TranscribeCallback
import net.rsprox.shared.StreamDirection
import net.rsprox.shared.filters.PropertyFilterSetStore
import net.rsprox.shared.property.ChildProperty
import net.rsprox.shared.property.RootProperty
import net.rsprox.shared.property.boolean
import net.rsprox.shared.property.int
import net.rsprox.shared.settings.SettingSetStore
import net.rsprox.transcriber.rs3.text.Rs3PropertyFormatter
import net.rsprox.transcriber.rs3.text.TextRs3TranscriberProvider
import net.rsprox.transcriber.text.TextMessageConsumerContainer
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.FileTime
import kotlin.io.path.bufferedWriter
import kotlin.io.path.nameWithoutExtension

internal object Rs3BinaryTranscriber {
    /** A single decoded RS3 event, in recording order. */
    internal sealed interface Event {
        data class Packet(
            val direction: StreamDirection,
            val prot: ClientProt,
            val message: IncomingMessage,
        ) : Event

        data class LobbyTransfer(
            val world: Int,
            val playerIndex: Int,
            val copiedLobby: Boolean,
        ) : Event
    }

    /**
     * Decodes a raw RS3 recording into a sequence of [Event]s, handling the recording-only
     * login/lobby-transfer preamble internally. Shared by [transcribe] (text output) and
     * by callers that want to process the decoded packets directly.
     */
    internal fun decode(
        binaryPath: Path,
        binary: BinaryBlob,
        callback: TranscribeCallback?,
    ): Sequence<Event> =
        sequence {
            callback?.indeterminate("Resolving recorded RS3 cache definitions (local cache / OpenRS2)...")
            val header = binary.header
            val definitions = Rs3LiveCacheResolver.loadRecordedPacketDefinitions(header.revision, header.js5MasterIndex)
            if (callback?.isCancelled() == true) return@sequence
            HuffmanProvider.load()
            // ISAAC-dependent payload bytes were already normalized when the recording was written.
            val decoder = Rs3DecoderLoader.load(header.revision, HuffmanProvider.get()) { NopStreamCipher }
            val data = binary.stream.copy()
            try {
                fun session(index: Int): Session =
                    Session(index, AttributeMap()).apply {
                        rs3PacketDefinitions = definitions
                        rs3AppearanceDefinitions = definitions
                    }
                var clientSession = session(-1)
                var serverSession = session(-1)
                val initialization = Rs3RecordingInitialization(header)
                val stream = BinaryStream(data)
                var lastPercent = -1
                for (packet in stream.toBinaryPacketSequence(
                    header,
                    decoder.gameClientProtProvider,
                    Rs3RecordingProt.provider(decoder.gameServerProtProvider),
                )) {
                    if (callback?.isCancelled() == true) return@sequence
                    val percent = stream.readPercentage()
                    if (percent != lastPercent) {
                        callback?.report(percent, "Transcribing RS3 packets...")
                        lastPercent = percent
                    }
                    val prot = packet.prot as ClientProt
                    when (prot) {
                        Rs3RecordingProt.LOGIN_INITIALIZATION -> {
                            initialization.initialization(packet.payload)
                            continue
                        }
                        Rs3RecordingProt.LOBBY_TRANSFER -> {
                            val transfer = initialization.transfer(packet.payload, packet.epochTimeMillis)
                            // Live lobby and game sockets each get independent protocol and transcript state.
                            clientSession = session(transfer.playerIndex)
                            serverSession = session(transfer.playerIndex).apply { rs3PlayerInfoInitPending = true }
                            yield(Event.LobbyTransfer(transfer.world, transfer.playerIndex, transfer.copiedLobby))
                            continue
                        }
                    }
                    initialization.packet()
                    val payload = packet.payload
                    val server = packet.direction == StreamDirection.SERVER_TO_CLIENT
                    val start = payload.readerIndex()
                    val bytes = payload.readableBytes()
                    val message: IncomingMessage =
                        try {
                            if (server) {
                                decoder.serverPacketDecoder.decode(prot.opcode, payload.toJagByteBuf(), serverSession)
                            } else {
                                decoder.clientPacketDecoder.decode(prot.opcode, payload.toJagByteBuf(), clientSession)
                            }
                        } catch (error: Exception) {
                            val raw = ByteArray(bytes).also { payload.getBytes(start, it) }
                            val reason = "${error.javaClass.simpleName}: ${error.message.orEmpty()}"
                            if (server) {
                                RawUnknownServerPacket(prot.opcode, prot.toString(), raw, reason)
                            } else {
                                RawUnknownClientPacket(prot.opcode, prot.toString(), raw, reason)
                            }
                        }
                    yield(Event.Packet(packet.direction, prot, message))
                }
                initialization.finish()
            } finally {
                data.release()
            }
        }

    fun transcribe(
        binaryPath: Path,
        binary: BinaryBlob,
        filters: PropertyFilterSetStore,
        settings: SettingSetStore,
        callback: TranscribeCallback?,
        onEvent: ((Event) -> Unit)? = null,
    ) {
        val header = binary.header
        val clientScripts =
            RSProxArchiveClientScriptIndex
                .forRuneScape(header.revision, header.js5MasterIndex)
                .also { it.preload() }
        if (callback?.isCancelled() == true) return
        val textPath = binaryPath.resolveSibling(binaryPath.nameWithoutExtension + ".txt")
        val oldTime =
            if (Files.exists(textPath)) {
                Files.getLastModifiedTime(textPath)
            } else {
                Files.getLastModifiedTime(binaryPath)
            }
        val temporary = Files.createTempFile(textPath.parent, ".rs3-transcribe-", ".tmp")
        try {
            temporary.bufferedWriter().use { writer ->
                writer.appendLine("------------------")
                writer.appendLine("Header information")
                writer.appendLine("game: RuneScape 3")
                writer.appendLine("version: ${header.revision}.${header.subRevision}")
                writer.appendLine("world: ${header.worldId}, host: ${header.worldHost}")
                writer.appendLine("local player index: ${header.localPlayerIndex}")
                writer.appendLine("-------------------")
                val consumer = TranscribeCommand.createBufferedWriterConsumer(writer)
                val container = TextMessageConsumerContainer(listOf(consumer))
                val formatter = Rs3PropertyFormatter.create(settings, Rs3GamevalLookup, clientScripts)
                val provider = TextRs3TranscriberProvider(Rs3GamevalLookup)
                var transcriber = provider.provide(container, filters, settings, clientScripts, isLobby = true)
                for (event in decode(binaryPath, binary, callback)) {
                    onEvent?.invoke(event)
                    when (event) {
                        is Event.LobbyTransfer -> {
                            // Live lobby and game sockets each get independent protocol and transcript state.
                            transcriber = provider.provide(container, filters, settings, clientScripts, isLobby = false)
                            transcriber.sessionState.localPlayerIndex = event.playerIndex
                            val marker =
                                object : RootProperty {
                                    override val prot = "LOBBY_TRANSFER"
                                    override val children: MutableList<ChildProperty<*>> = mutableListOf()
                                }
                            marker.int("world", event.world)
                            marker.int("playerindex", event.playerIndex)
                            marker.boolean("copiedlobby", event.copiedLobby)
                            consumer.consume(formatter, 0, marker)
                        }
                        is Event.Packet -> {
                            if (event.direction == StreamDirection.SERVER_TO_CLIENT) {
                                transcriber.onServerPacket(event.prot, event.message)
                            } else {
                                transcriber.onClientProt(event.prot, event.message)
                            }
                        }
                    }
                }
            }
            Files.move(temporary, textPath, StandardCopyOption.REPLACE_EXISTING)
            Files.setLastModifiedTime(textPath, FileTime.fromMillis(oldTime.toMillis() + 1))
        } finally {
            Files.deleteIfExists(temporary)
        }
    }
}
