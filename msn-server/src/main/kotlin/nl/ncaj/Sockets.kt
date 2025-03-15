package nl.ncaj

import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.AsynchronousServerSocketChannel
import java.nio.channels.AsynchronousSocketChannel
import java.nio.channels.CompletionHandler
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

internal fun openSocket(port: Int) = callbackFlow<SocketConnection> {
    val hostSocketAddress = InetSocketAddress(port)
    val socketChannel = AsynchronousServerSocketChannel.open()
        .bind(hostSocketAddress)

    socketChannel.accept(null, object : CompletionHandler<AsynchronousSocketChannel, Nothing> {
        override fun completed(result: AsynchronousSocketChannel, attachment: Nothing?) {
            val clientSocketAddress = result.remoteAddress as InetSocketAddress
            trySend(SocketConnection(hostSocketAddress, result, clientSocketAddress))
            socketChannel.accept(null, this)
        }

        override fun failed(exc: Throwable, attachment: Nothing?) {
            close(exc)
        }
    })

    awaitClose { socketChannel.close() }
}

internal data class SocketConnection(
    val hostAddress: InetSocketAddress,
    val client: AsynchronousSocketChannel,
    val clientAddress: InetSocketAddress
)

internal suspend fun AsynchronousSocketChannel.suspendRead(buffer: ByteBuffer) = suspendCoroutine<Int> { continuation ->
    read(buffer, null, object : CompletionHandler<Int, Nothing> {
        override fun completed(result: Int, attachment: Nothing?) {
            continuation.resume(result)
        }

        override fun failed(exc: Throwable, attachment: Nothing?) {
            continuation.resumeWithException(exc)
        }
    })
}

suspend fun AsynchronousSocketChannel.readUntil(
    buffer: ByteBuffer,
    predicate: (ByteArray, offset: Int, size: Int) -> Boolean,
    maxSize: Int = 1664
): ByteArray {
    val accumulatedData = ByteArray(maxSize)
    var bytesAccumulated = 0
    var found = false

    do {
        if (buffer.position() == 0) { //only read if the buffer is empty
            val bytesRead = suspendRead(buffer)

            if (bytesRead == -1 && accumulatedData.isEmpty()) {
                return ByteArray(0) // End of stream and no data
            } else if (bytesRead == -1) {
                break //End of stream, but we have some data.
            }

            buffer.flip()
        }

        while (buffer.hasRemaining()) {
            if (bytesAccumulated >= maxSize) error("Max size exceeded")
            accumulatedData[bytesAccumulated++] = buffer.get()
            if (predicate(accumulatedData, 0, bytesAccumulated)) {
                found = true
                break
            }
        }
        if (found) continue
        buffer.compact() // Move remaining data to the beginning
    } while (!found)

    return accumulatedData.copyOf(bytesAccumulated)
}
