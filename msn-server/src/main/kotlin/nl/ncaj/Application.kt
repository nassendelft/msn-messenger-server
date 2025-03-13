package nl.ncaj

import kotlinx.coroutines.*


fun main(args: Array<String>) {
    println("Application started")
    runBlocking {
        joinAll(
            launch(Dispatchers.Default + SupervisorJob()) {
                dispatchServer(port = args[0].toInt(), nsConnectionString = args[1])
            },
            launch(Dispatchers.Default + SupervisorJob()) {
                notificationServer(port = args[2].toInt(), sbConnectionString = args[3])
            },
            launch(Dispatchers.Default + SupervisorJob()) {
                switchBoardServer(port = args[4].toInt())
            }
        )
    }
    println("Application stopped")
}