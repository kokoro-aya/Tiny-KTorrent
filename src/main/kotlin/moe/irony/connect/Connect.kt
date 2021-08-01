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

/**
 * Creates a TCP connection with the given IP address and port number.
 * @param ip IP address of the remote host
 * @param port port number of the remote host
 * @return a socket that holds the created connection
 */
suspend fun createConnection(ip: String, port: Int): Socket {
    return aSocket(ActorSelectorManager(Dispatchers.IO)).tcp().connect(InetSocketAddress(ip, port))
}

/**
 * Open the input and output channels of the given socket
 * @param socket the socket to be open
 * @return a pair of output channel (ByteWriteChannel) and input channel (ByteReadChannel)
 */
fun openChannels(socket: Socket): Pair<ByteWriteChannel, ByteReadChannel> {
    return socket.openWriteChannel(autoFlush = true) to socket.openReadChannel()
}

/**
 * Writes data to the given channel
 * @param outputChannel the channel to be written into
 * @param data data to be written into the channel, in string form
 */
suspend fun sendData(outputChannel: ByteWriteChannel, data: String) {
    val dt = data.map { it.code.toByte() }.toByteArray()
    outputChannel.writeAvailable(dt)
}

/**
 * Receives data from the host. If the buffer size is 0 (default), the first 4 bytes of the received message
 * will be read as int to get the total length of the message.
 * @param inputChannel the channel to be read
 * @param bufferSize size of the receiving buffer
 * @return received data in the form of string
 */
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