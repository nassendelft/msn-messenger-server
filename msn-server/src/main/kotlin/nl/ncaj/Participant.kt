package nl.ncaj

import java.nio.ByteBuffer
import java.nio.channels.AsynchronousSocketChannel

internal class Participant private constructor(
    private val write: (ByteBuffer) -> Unit,
    private val readUntil: suspend (ByteBuffer, (ByteArray, offset: Int, size: Int) -> Boolean) -> ByteArray,
    private val clientConnectionDetails: String,
) {
    private val buffer = ByteBuffer.allocate(4096)

    lateinit var principal: Principal

    val isInitialized get() = ::principal.isInitialized

    fun sendCommand(command: String) {
        if (!command.endsWith("\r\n") && !command.startsWith("MSG")) {
            error("Command: '$command' does not end with newline")
        }
        if (command.startsWith("MSG")) {
            // redact user content
            val redactedLog = command.substringAfter("\r") + "<< redacted >>"
            println("$clientConnectionDetails <<< $redactedLog")
        } else {
            println("$clientConnectionDetails <<< ${command.trim()}")
        }
        write(ByteBuffer.wrap(command.toByteArray()))
    }

    fun sendError(trId: String, code: String = "500") {
        sendCommand("$code $trId\r\n")
    }
    suspend fun readCommand(): String {
        val command = readUntil(buffer) { bytes, _, size ->
            size > 2 && bytes[size - 2] == '\r'.code.toByte() && bytes[size - 1] == '\n'.code.toByte()
        }.decodeToString().trim()
        println("$clientConnectionDetails >>> $command")
        return command
    }

    suspend fun readMessage(length: Int): String {
        // MESSAGE IS NOT LOGGED HERE as this is user content
        val result = readUntil(buffer) { _, _, size -> size == length }
        return result.decodeToString().also { println("$clientConnectionDetails >>> $it") }
    }

    companion object {
        fun Participant(socket: AsynchronousSocketChannel, connectionId: String) =
            Participant(socket::write, socket::readUntil, connectionId)
    }
}
