package moe.irony.bencode_decoder

import moe.irony.utils.fp.Result
import moe.irony.utils.fp.sequenceLeft

data class PeerResponse(val interval: Int, val peers: List<Peer>)

data class CompactPeerResponse(val interval: Int, val peerInfos: String)

/**
 * An representation of peers that is retrieved from the tracker.
 * It contains a string denoting the IP and a port number.
 */
data class Peer(val ip: String, val peerId: String, val port: Int)

fun Result<DictionaryLiteral>.getPeer(): Result<Peer> =
    this.flatMap { peer ->
        peer.getStringAttr("ip").flatMap { ip ->
            peer.getStringAttr("peer id").flatMap { peerid ->
                peer.getIntAttr("port").flatMap { port ->
                    Result(Peer(ip, peerid, port.toInt()))
                }
            }
        }
    }.mapFailure("Something went wrong while retrieving peer")

fun Result<ListLiteral>.getPeers(): Result<List<Peer>> =
    this.flatMap { list ->
        sequenceLeft(list.of.map { elem ->
            Result(elem as DictionaryLiteral).getPeer()
        })
    }

fun Result<DictionaryLiteral>.getPeerResponse(): Result<PeerResponse> =
    this.flatMap { dict ->
        dict.getIntAttr("interval").flatMap { interval ->
            dict.getListAttr("peers").getPeers().flatMap { peers ->
                Result(PeerResponse(
                    interval = interval.toInt(),
                    peers = peers
                ))
            }
        }
    }.mapFailure("Encountered error while trying to decode peers response from server [1]")

fun Result<Bencode>.convertToPeerResponse(): Result<PeerResponse> =
    this.flatMap {
        when (it) {
            is DictionaryLiteral -> Result(it).getPeerResponse()
            else -> Result.failure("Wrong type, not a peers response format")
        }
    }.mapFailure("Encountered error while trying to decode peers response from server [0]")

fun Result<DictionaryLiteral>.getCompactPeerResponse(): Result<CompactPeerResponse> =
    this.flatMap { dict ->
        dict.getIntAttr("interval").flatMap { interval ->
            dict.getStringAttr("peers").flatMap { peers ->
                Result(CompactPeerResponse(
                    interval = interval.toInt(),
                    peerInfos = peers
                ))
            }
        }
    }

fun Result<Bencode>.convertToCompactResponse(): Result<CompactPeerResponse> =
    this.flatMap {
        when (it) {
            is DictionaryLiteral -> Result(it).getCompactPeerResponse()
            else -> Result.failure("Wrong type, not a compact peer response format")
        }
    }.mapFailure("Encountered error while trying to decode compact peers response from server")