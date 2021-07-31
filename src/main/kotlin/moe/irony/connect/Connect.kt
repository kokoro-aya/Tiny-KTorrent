package moe.irony.connect

import io.ktor.network.selector.ActorSelectorManager
import io.ktor.network.sockets.Socket
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.utils.io.*
import io.ktor.utils.io.core.ByteOrder
import kotlinx.coroutines.Dispatchers
import moe.irony.utils.Log
import java.net.InetSocketAddress

const val CONNECT_TIMEOUT = 3_000
const val READ_TIMEOUT = 3_000


suspend fun createConnection(ip: String, port: Int): Socket {
    return aSocket(ActorSelectorManager(Dispatchers.IO)).tcp().connect(InetSocketAddress(ip, port))
}

fun openChannels(socket: Socket): Pair<ByteWriteChannel, ByteReadChannel> {
    return socket.openWriteChannel(autoFlush = true) to socket.openReadChannel()
}

suspend fun sendData(outputChannel: ByteWriteChannel, data: String) {
    val dt = data.map { it.code.toByte() }.toByteArray()
    outputChannel.writeAvailable(dt)
}

suspend fun recvData(inputChannel: ByteReadChannel, bufferSize: Int): String {
    return if (bufferSize != 0) {
        val buffer = ByteArray(bufferSize)
        inputChannel.readFully(buffer)
        buffer.slice(0 until bufferSize).map { it.toInt().toChar() }.joinToString("")
    } else {
        val len = inputChannel.readInt(ByteOrder.BIG_ENDIAN)
        when {
            len > 0 -> {
                val buffer = ByteArray(len)
                Log.debug { "Receiving data from channel with length = $len" }
                inputChannel.readFully(buffer) // 如果不readFully而是readAvailable的话会出问题，大概会把上一个内容读下来？
                buffer.map { it.toInt().toChar() }.joinToString("")
            }
            len == 0 -> {
                Log.debug { "Receiving data from channel with length = $len" }
                ""
            }
            else -> {
                throw IllegalStateException("Received a message which has a negative length $len")
            }
        }
    }
}

fun closeSocket(socket: Socket) {
    socket.close()
}