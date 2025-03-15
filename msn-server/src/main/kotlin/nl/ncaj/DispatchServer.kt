package nl.ncaj

import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import nl.ncaj.Participant.Companion.Participant

internal suspend fun dispatchServer(
    db: Database,
    port: Int = 1863,
    nsConnectionString: String = "127.0.0.1:1864",
): Unit = coroutineScope {
    openSocket(port)
        .onStart { println("DispatchServer listening on port $port") }
        .onCompletion { println("Dispatch server stopped") }
        .onEach { (hostAddress, client, clientAddress) ->
            launch {
                val serverConnectionString = "${hostAddress.address}:${hostAddress.port}"
                val connectionId = "${clientAddress.address}:${clientAddress.port}"
                val participant = Participant(client, connectionId)

                println("Client connected to ds: $connectionId")

                try {
                    participant.handleInitClient(serverConnectionString, nsConnectionString, db::getPrincipal)
                } catch (e: Throwable) {
                    println("Error handling client: ${e.message}")
                    this@launch.cancel()
                } finally {
                    client.close()
                    println("Client disconnected from ds: $connectionId")
                }
            }
        }
        .collect()
}

private suspend fun Participant.handleInitClient(
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