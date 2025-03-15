package nl.ncaj

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking


fun main(args: Array<String>) {
    try {
        println("Application started")
        runBlocking(Dispatchers.Default.limitedParallelism(3)) {
            val db = Database(args[5])
            joinAll(
                launch { dispatchServer(db, port = args[0].toInt(), nsConnectionString = args[1]) },
                launch { notificationServer(db, port = args[2].toInt(), sbConnectionString = args[3]) },
                launch { switchBoardServer(db, port = args[4].toInt()) }
            )
        }
    } finally {
        println("Application stopped")
    }
}