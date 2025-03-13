package nl.ncaj

import java.io.BufferedWriter
import java.io.InputStream
import java.net.Socket
import java.nio.charset.Charset

internal class Participant private constructor(
    private val writer: BufferedWriter,
    private val reader: InputStream,
    private val clientConnectionDetails: String,
) {

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
        writer.write(command)
        writer.flush()
    }

    fun sendError(trId: String, code: String = "500") {
        sendCommand("$code $trId\r\n")
    }

    fun readCommand(): String {
        val command = readLineOrThrow()
        println("$clientConnectionDetails >>> $command")
        return command
    }

    fun readMessage(length: Int): String {
        // MESSAGE IS NOT LOGGED HERE as this is user content
        val array = reader.readNBytes(length)
        return String(array).also { println("$clientConnectionDetails >>> $it") }
    }

    fun readLineOrThrow(charset: Charset = Charsets.UTF_8, maxLineLength: Int = 1664): String {
        val buffer = ByteArray(maxLineLength + 2)
        var bufferIndex = 0

        do {
            val byte = reader.read()

            if (byte == -1) {
                if (bufferIndex > 0) {
                    error("Unexpected end of stream before line terminator")
                } else {
                    error("End of stream")
                }
            }

            buffer[bufferIndex++] = byte.toByte()

            if (bufferIndex >= 2 && buffer[bufferIndex - 2] == '\r'.code.toByte() && buffer[bufferIndex - 1] == '\n'.code.toByte()) {
                return String(buffer, 0, bufferIndex - 2, charset)
            } else if (bufferIndex >= maxLineLength + 2) {
                error("Line exceeds maximum length of $maxLineLength bytes")
            }
        } while(true)
    }

    companion object {
        fun Participant(socket: Socket, connectionId: String) = Participant(
            socket.outputStream.bufferedWriter(),
            socket.inputStream,
            connectionId,
        )
    }
}
