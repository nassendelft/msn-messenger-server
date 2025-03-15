package nl.ncaj

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import nl.ncaj.Participant.Companion.Participant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

internal class NotificationSession(
    private val participant: Participant,
    private val sbConnectionString: String,
    private val principals: (email: String) -> Principal?,
    private val updatePrincipal: (Principal) -> Unit,
) {
    private var initialCHG = true

    fun handleCommand(command: List<String>) =
        (commandHandlers[command[0]] ?: error("Unknown command ${command[0]}")).invoke(command)

    private val commandHandlers = mapOf(
        "SYN" to ::handleSYN,
        "CHG" to ::handleCHG,
        "CVR" to ::handleCVR,
        "ADD" to ::handleADD,
        "REM" to ::handleREM,
        "SND" to ::handleSND,
        "URL" to ::handleURL,
        "REA" to ::handleREA,
        "FND" to ::handleFND,
        "OUT" to ::handleOUT,
        "PNG" to ::handlePNG,
        "LST" to ::handleLST,
        "XFR" to ::handleXRF,
    )

    @OptIn(ExperimentalStdlibApi::class)
    suspend fun authenticate() {
        var command = participant.readCommand().split(" ")
        check(command[0] == "VER") { "Expected 'VER' but received '${command[0]}'" }
        if (!command.contains("MSNP2")) {
            participant.sendCommand("VER ${command[1]} 0\r\n")
            error("Expected version 'MSNP2' present but received '${command.drop(2)}'")
        }
        participant.sendCommand("VER ${command[1]} MSNP2\r\n")

        command = participant.readCommand().split(" ")
        check(command[0] == "INF") { "Expected 'INF' but received '${command[0]}'" }
        participant.sendCommand("INF ${command[1]} MD5\r\n")

        command = participant.readCommand().split(" ")
        check(command[0] == "USR") { "Expected 'USR' but received '${command[0]}'" }

        val principal = principals(command[4])
        if (principal == null) {
            participant.sendError(command[1], code = "911")
            throw Exception("User not found")
        } else {
            sessions(principal.email)?.disconnect("OTH")
            participant.sendCommand("USR ${command[1]} MD5 S ${principal.salt}\r\n")

            command = participant.readCommand().split(" ")
            check(command[0] == "USR") { "Expected 'USR' but received '${command[0]}'" }
            check(command[4] == principal.password) { "Incorrect password" }

            participant.sendCommand("USR ${command[1]} OK ${principal.email} ${principal.displayName}\r\n")
            participant.principal = principal
        }
    }

    // Synchronise
    private fun handleSYN(args: List<String>) {
        fun sendContactList(trId: String, type: String, syncVersion: Int, list: Set<Contact>) {
            if (list.isEmpty()) {
                participant.sendCommand("LST $trId $type $syncVersion 0 0\r\n")
            } else {
                list.forEachIndexed { i, it ->
                    participant.sendCommand("LST $trId $type $syncVersion ${i + 1} ${list.size} ${it.email} ${it.displayName}\r\n")
                }
            }
        }

        val (cmd, trId, syncVersion) = args

        val principal = participant.principal
        val principalSyncVersion = principal.syncVersion

        participant.sendCommand("$cmd $trId $principalSyncVersion\r\n")

        if (syncVersion != "0" && principalSyncVersion.toString() == syncVersion) return

        participant.sendCommand("GTC $trId $principalSyncVersion ${principal.privacyAdd}\r\n")

        participant.sendCommand("BLP $trId $principalSyncVersion ${principal.privacy}\r\n")

        sendContactList(trId, "FL", principalSyncVersion, principal.forwardList.list)
        sendContactList(trId, "AL", principalSyncVersion, principal.allowList.list)
        sendContactList(trId, "BL", principalSyncVersion, principal.blockList.list)
        sendContactList(trId, "RL", principalSyncVersion, principal.reverseList.list)
    }

    // Change status
    private fun handleCHG(args: List<String>) {
        val (cmd, trId, status) = args
        if (status == "FLN" || !availableStatus.contains(status)) participant.sendError(trId, code = "201")

        val principal = participant.principal
        principal.status = status
        principal.syncVersion++

        updatePrincipal(principal)

        participant.sendCommand("$cmd $trId $status\r\n")

        if (initialCHG) {
            initialCHG = false
            principal.forwardList.list
                .mapNotNull { principals(it.email) }
                .filter { it.allowList.contains(principal.email) }
                .forEach { participant.sendCommand("ILN $trId ${it.status} ${it.email} ${it.displayName}\r\n") }
        }

        getNotifySessions().forEach { it.sendNLN(principal) }
    }

    // Receive client info
    private fun handleCVR(args: List<String>) {
//    val (cmd, trId, localeId, osType, osVersion, arch, libName, clientVersion) = args
        val clientVersion = args[7]
        participant.sendCommand("${args[0]} ${args[1]} $clientVersion $clientVersion $clientVersion x x\r\n")
    }

    // Adds a principal to contact list.
    private fun handleADD(args: List<String>) {
        val (cmd, trId, type, email, displayName) = args

        val principal = participant.principal

        if (!email.contains("@")) {
            participant.sendError(trId, code = "201")
            return
        }

        val otherPrincipal = principals(email)

        if (otherPrincipal == null) {
            participant.sendError(trId, code = "205")
            return
        }

        if (principal.forwardList.list.size == 300) {
            participant.sendError(trId, code = "210")
            return
        }

        if (
            principal.allowList.contains(email) &&
            principal.blockList.contains(email) &&
            (type == "AL" || type == "BL")
        ) {
            participant.sendError(trId, code = "215")
            return
        }

        val list = when (type) {
            "AL" -> principal.allowList
            "BL" -> principal.blockList
            "FL" -> principal.forwardList
            else -> {
                participant.sendError(trId, code = "224")
                return
            }
        }

        list.add(email, displayName)

        principal.syncVersion++
        updatePrincipal(principal)

        participant.sendCommand("$cmd $trId $type ${list.version} $email $displayName\r\n")

        sessions(otherPrincipal.email)?.let {
            it.sendADD("RL", principal.email, principal.displayName)
            it.sendNLN(principal)
        }
        sessions(otherPrincipal.email)?.sendADD("RL", principal.email, principal.displayName)

        if (type == "FL") {
            participant.sendCommand("ILN $trId ${otherPrincipal.status} ${otherPrincipal.email} ${otherPrincipal.displayName}\r\n")
        }
    }

    // Remove a principal to contact list.
    private fun handleREM(args: List<String>) {
        val (cmd, trId, type, email) = args

        val principal = participant.principal

        val otherPrincipal = principals(email)
        if (otherPrincipal == null) {
            participant.sendError(trId, code = "216")
            return
        }

        val list = when (type) {
            "AL" -> principal.allowList
            "BL" -> principal.blockList
            "FL" -> principal.forwardList
            else -> {
                participant.sendError(trId, code = "224")
                return
            }
        }
        list.remove(email)

        principal.syncVersion++

        updatePrincipal(principal)

        participant.sendCommand("$cmd $trId $type ${list.version} $email\r\n")

        sessions(otherPrincipal.email)?.let {
            it.sendREM("RL", principal.email)
            it.sendFLN(principal.email)
        }
    }

    // Send invite email
    private fun handleSND(args: List<String>) {
        val (cmd, trId, email) = args
        participant.sendCommand("$cmd $trId $email\r\n")
    }

    // Get url for given type
    private fun handleURL(args: List<String>) {
        val (cmd, trId, type) = args
        participant.sendCommand("$cmd $trId $type x\r\n")
    }

    // Change display name
    private fun handleREA(args: List<String>) {
        val (cmd, trId, email, displayName) = args

        val principal = participant.principal

        if (displayName.length >= 130) {
            participant.sendError(trId, code = "209")
            return
        }

        principal.displayName = displayName
        principal.syncVersion++

        updatePrincipal(principal)

        participant.sendCommand("$cmd $trId ${principal.syncVersion} $email $displayName\r\n")

        getNotifySessions().forEach { it.sendNLN(principal) }
    }

    // Find principal
    private fun handleFND(args: List<String>) {
//    val (cmd, trId, firstName, lastName, city, state, country) = args
        participant.sendCommand("${args[0]} ${args[1]} 0 0\r\n") // 0 0 = no results
    }

    // Logout
    private fun handleOUT(args: List<String>) {
        val (cmd) = args
        val principal = participant.principal
        participant.sendCommand("$cmd\r\n")
        getNotifySessions().forEach { it.sendFLN(principal.email) }
        throw Exception("LOGOFF") // TODO handle proper disconnect
    }

    // Ping
    @Suppress("unused")
    private fun handlePNG(args: List<String>) {
        participant.sendCommand("QNG\r\n")
    }

    // List contacts
    private fun handleLST(args: List<String>) {
        val (cmd, trId, type) = args
        val principal = participant.principal
        val list = when (type) {
            "AL" -> principal.allowList
            "BL" -> principal.blockList
            "FL" -> principal.forwardList
            else -> {
                participant.sendError(trId, code = "224")
                return
            }
        }
        val listVersion = list.version
        if (list.list.isEmpty()) {
            participant.sendCommand("$cmd $trId $type 0 0\r\n")
        } else {
            list.list.forEachIndexed { i, it ->
                participant.sendCommand("$cmd $trId $type $listVersion ${i + 1} ${list.list.size} ${it.email} ${it.displayName}\r\n")
            }
        }
    }

    // Transfer to SwitchBoard
    @OptIn(ExperimentalUuidApi::class)
    private fun handleXRF(args: List<String>) {
        val (cmd, trId, type) = args
        if (type != "SB") error("unknown transfer type: $type")

        val hash = Uuid.random().toString()
        participant.sendCommand("$cmd $trId $type $sbConnectionString CKI $hash\r\n")
    }

    // Ring principal
    fun sendRNG(
        sessionId: String,
        hash: String,
        email: String,
        displayName: String,
    ) = participant.sendCommand("RNG $sessionId $sbConnectionString CKI $hash $email $displayName\r\n")

    // Notify principal status
    private fun sendNLN(principal: Principal) =
        participant.sendCommand("NLN ${principal.status} ${principal.email} ${principal.displayName}\r\n")

    // Notify principal offline status
    private fun sendFLN(email: String) = participant.sendCommand("FLN $email\r\n")

    private fun sendADD(type: String, email: String, displayName: String) {
        val principal = participant.principal
        val list = when (type) {
            "AL" -> principal.allowList
            "BL" -> principal.blockList
            "FL" -> principal.forwardList
            "RL" -> principal.reverseList
            else -> return
        }
        list.add(principal.email, principal.displayName)
        principal.syncVersion++

        updatePrincipal(principal)

        participant.sendCommand("ADD 0 $type ${list.version} $email $displayName\r\n")
    }

    private fun sendREM(type: String, email: String) {
        val principal = participant.principal
        val list = when (type) {
            "AL" -> principal.allowList
            "BL" -> principal.blockList
            "FL" -> principal.forwardList
            else -> return
        }
        list.remove(principal.email)
        principal.syncVersion++

        updatePrincipal(principal)

        participant.sendCommand("REM 0 $type ${list.version} $email\r\n")
    }

    private fun disconnect(reason: String) {
        participant.sendCommand("OUT $reason\r\n")
        val principal = participant.principal
        getNotifySessions().forEach { it.sendFLN(principal.email) }
        throw Exception("DISCONNECT") // TODO handle proper disconnect
    }

    private fun getNotifySessions(): List<NotificationSession> {
        val principal = participant.principal
        return principal.forwardList.list
            .filter { principals(it.email)?.allowList?.contains(principal.email) == true }
            .mapNotNull { sessions(it.email) }
    }
}

internal suspend fun notificationServer(
    db: Database,
    port: Int = 1864,
    sbConnectionString: String = "127.0.0.1:1865",
): Unit = coroutineScope {
    openSocket(port)
        .onStart { println("NotificationServer listening on port $port") }
        .onCompletion { println("Notification server stopped") }
        .onEach { (_, client, clientAddress) ->
            launch {
                val connectionId = "${clientAddress.address}:${clientAddress.port}"
                val participant = Participant(client, connectionId)
                val session = NotificationSession(participant, sbConnectionString, db::getPrincipal, db::updatePrincipal)

                println("Client connected to ns: $connectionId")

                try {
                    session.authenticate()
                    sessions[participant.principal.email] = session
                    while (isActive) { session.handleCommand(participant.readCommand().split(" ")) }
                } catch (e: Throwable) {
                    println("Error handling client: ${e.message}")
                    this@launch.cancel()
                } finally {
                    if (participant.isInitialized) sessions.remove(participant.principal.email)
                    client.close()
                    println("Client disconnected from ns: $connectionId")
                }
            }
        }
        .collect()
}

private val availableStatus = setOf(
    "NLN", // online
    "FLN", // offline
    "HDN", // appearOffline
    "IDL", // idle
    "AWY", // away
    "BSY", // busy,
    "BRB", // beRightBack,
    "PHN", // onThePhone,
    "LUN", // outToLunch
)

private val sessions = mutableMapOf<String, NotificationSession>()
internal fun sessions(email: String) = sessions[email]