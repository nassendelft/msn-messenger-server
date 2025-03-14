package nl.ncaj

import kotlinx.coroutines.*


fun main(args: Array<String>) {
    println("Application started")
    runBlocking {
        val db = Database(args[5])
        joinAll(
            launch(Dispatchers.Default + SupervisorJob()) {
                dispatchServer(db, port = args[0].toInt(), nsConnectionString = args[1])
            },
            launch(Dispatchers.Default + SupervisorJob()) {
                notificationServer(db, port = args[2].toInt(), sbConnectionString = args[3])
            },
            launch(Dispatchers.Default + SupervisorJob()) {
                switchBoardServer(db, port = args[4].toInt())
            }
        )
    }
    println("Application stopped")
}