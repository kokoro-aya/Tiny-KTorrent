package test

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.Channel.Factory.RENDEZVOUS
import kotlinx.coroutines.channels.ReceiveChannel

fun CoroutineScope.launchProcessor(id: Int, channel: ReceiveChannel<Int>) = launch {
    for (msg in channel) {
        println("Processor #$id received $msg")
    }
}

suspend fun main() {
    runBlocking {
        val channel = Channel<Int>(RENDEZVOUS)
        val ad = ArrayDeque<Int>()
        ad.addAll(List(10) { it })
        launch {
            while (ad.isNotEmpty()) {
                delay(125L)
                channel.send(ad.removeFirst())
            }
            channel.close()
        }

        repeat (5) {
            launchProcessor(it, channel)
            if (it % 2 == 0)
                ad.addLast(it * it)
        }
    }
    println("Done!")
}