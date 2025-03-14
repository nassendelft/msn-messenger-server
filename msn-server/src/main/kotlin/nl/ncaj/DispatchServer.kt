package nl.ncaj

import kotlinx.coroutines.*
import nl.ncaj.Participant.Companion.Participant
import java.net.ServerSocket

internal suspend fun dispatchServer(
    db: Database,
    port: Int = 1863,
    nsConnectionString: String = "127.0.0.1:1864",
): Unit = coroutineScope {
    val serverSocket = ServerSocket(port)
    val serverConnectionString = "${serverSocket.inetAddress.hostAddress}:$port"
    println("DispatchServer listening on port $port")

    while (isActive) {
        val clientSocket = serverSocket.accept()

        launch(Dispatchers.Default) {
            val connectionId = "${clientSocket.inetAddress.hostAddress}:${clientSocket.port}"
            val participant = Participant(clientSocket, connectionId)

            println("Client connected to ds: $connectionId")

            try {
                participant.handleInitClient(serverConnectionString, nsConnectionString, db::getPrincipal)
            } catch (e: Throwable) {
                println("Error handling client: ${e.message}")
                this@launch.cancel()
            } finally {
                clientSocket.close()
                println("Client disconnected from ds: $connectionId")
            }
        }
    }

    error("Dispatch server stopped")
}

private fun Participant.handleInitClient(
    serverConnectionString: String,
    nsConnectionString: String,
    principals: (email: String) -> Principal?,
) {
    var command = readCommand().split(" ")
    check(command[0] == "VER") { "Expected 'VER' but received '${command[0]}'" }
    if (!command.contains("MSNP2")) {
        sendCommand("VER ${command[1]} 0\r\n")
        error("Expected version 'MSNP2' present but received '${command.drop(2)}'")
    }
    sendCommand("VER ${command[1]} MSNP2\r\n")

    command = readCommand().split(" ")
    check(command[0] == "INF") { "Expected 'INF' but received '${command[0]}'" }
    sendCommand("INF ${command[1]} MD5\r\n")

    command = readCommand().split(" ")
    check(command[0] == "USR") { "Expected 'USR' but received '${command[0]}'" }

    val principal = principals(command[4])
    if (principal == null) {
        sendError(command[1], code = "911")
        throw Exception("User not found")
    } else {
        sendCommand("XFR ${command[1]} NS $nsConnectionString 0 $serverConnectionString\r\n")
    }
}