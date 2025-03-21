package nl.ncaj

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import java.net.InetSocketAddress
import java.nio.channels.AsynchronousSocketChannel
import kotlin.reflect.KFunction1
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

private class SwitchBoardSession(
    val hash: String,
    private val principals: (email: String) -> Principal?
) {
    val participants = mutableSetOf<Participant>()

    suspend fun handleCommand(participant: Participant, command: List<String>) =
        (commandHandlers[command[0]] ?: error("Unknown command ${command[0]}")).invoke(participant, command)

    private val commandHandlers = mapOf(
        "USR" to ::handleUSR,
        "CAL" to ::handleCAL,
        "ANS" to ::handleANS,
        "MSG" to ::handleMSG,
        "OUT" to ::handleOUT,
    )

    private suspend fun handleUSR(participant: Participant, args: List<String>) {
        val (cmd, trId, email) = args
        val principal = principals(email)!!
        participant.sendCommand("$cmd $trId OK $email ${principal.displayName}\r\n")
    }

    @OptIn(ExperimentalUuidApi::class)
    private suspend fun handleCAL(participant: Participant, args: List<String>) {
        val (cmd, trId, email) = args
        val sessionId = Uuid.random().toString()
        participant.sendCommand("$cmd $trId RINGING $sessionId\r\n")
        val principal = participant.principal
        sessions(email)?.sendRNG(sessionId, hash, principal.email, principal.displayName)
    }

    private suspend fun handleANS(participant: Participant, args: List<String>) {
        val (cmd, trId, email) = args
        val principal = principals(email)!!
        val participants = participants.filterNot { it == participant }
        participants.forEachIndexed { i, it ->
            participant.sendCommand("IRO $trId ${i + 1} ${participants.size} ${it.principal.email} ${it.principal.displayName}\r\n")
        }
        participant.sendCommand("$cmd OK\r\n")
        participants.forEach { it.sendCommand("JOI ${principal.email} ${principal.displayName}\r\n") }
    }

    private suspend fun handleMSG(participant: Participant, args: List<String>) {
        val (cmd, trId, type, length) = args
        if (length.toInt() >= 1664) error("to large message type")
        val message = participant.readMessage(length.toInt())

        val participants = participants.filterNot { it == participant }
        if (participants.isEmpty()) {
            if (type != "U") participant.sendCommand("NACK $trId\r\n")
        } else {
            participants.forEach { p ->
                p.sendCommand("$cmd ${participant.principal.email} ${participant.principal.displayName} ${message.length}\r\n$message")
            }
            if (type != "U") participant.sendCommand("ACK $trId\r\n")
        }
    }

    @Suppress("unused")
    private suspend fun handleOUT(participant: Participant, args: List<String>) {
        participants.filterNot { it == participant }.forEach {
            it.sendCommand("BYE ${participant.principal.email}\r\n")
        }
    }
}

internal suspend fun switchBoardServer(
    db: Database,
    port: Int = 1865
): Unit = coroutineScope {
    val sessions = mutableMapOf<String, SwitchBoardSession>()
    val sessionsByParticipant = mutableMapOf<Participant, SwitchBoardSession>()
    val principals = db::getPrincipal

    openSocket(port)
        .onStart { println("SwitchBoard listening on port $port") }
        .onCompletion { println("Switchboard stopped") }
        .onEach { (_, client, clientAddress) ->
            launch { handleClient(client, clientAddress, principals, sessions, sessionsByParticipant) }
        }
        .collect()
}

private suspend fun CoroutineScope.handleClient(
    client: AsynchronousSocketChannel,
    clientAddress: InetSocketAddress,
    principals: KFunction1<String, Principal?>,
    sessions: MutableMap<String, SwitchBoardSession>,
    sessionsByParticipant: MutableMap<Participant, SwitchBoardSession>
) {
    val connectionId = "${clientAddress.address}:${clientAddress.port}"
    val participant = Participant.Participant(client, connectionId)
    println("Client connected to sb: $connectionId")

    try {
        while (isActive) {
            val command = participant.readCommand().split(" ")

            if (command[0] == "USR") {
                val hash = command[3]
                participant.principal = principals(command[2])!!
                SwitchBoardSession(hash, principals).also {
                    sessions[hash] = it
                    sessionsByParticipant[participant] = it
                }.apply { participants.add(participant) }

            } else if (command[0] == "ANS") {
                val hash = command[3]
                participant.principal = principals(command[2])!!
                val session = sessions[hash]!!
                session.participants += participant
                sessionsByParticipant[participant] = session

            }

            sessionsByParticipant[participant]!!.handleCommand(participant, command)

            if (command[0] == "OUT") {
                this.cancel()
            }
        }
    } catch (e: Exception) {
        if (e !is CancellationException) {
            println("Error handling client: ${e.message}")
            e.printStackTrace()
            this.cancel()
        }
    } finally {
        val session = sessionsByParticipant[participant]!!
        session.participants -= participant
        if (session.participants.isEmpty()) {
            sessions.remove(session.hash)
            sessionsByParticipant.remove(participant)
            println("Session ${session.hash} closed")
        }
        client.close()
        println("Client disconnected from sb: $connectionId")
    }
}