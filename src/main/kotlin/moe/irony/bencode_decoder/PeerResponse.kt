package moe.irony.bencode_decoder

import moe.irony.utils.fp.Result

data class PeerResponse(val interval: Int, val peers: InfoHash)

fun Result<DictionaryLiteral>.getPeerResponse(): Result<PeerResponse> =
    this.flatMap { dict ->
        dict.getIntAttr("interval").flatMap { interval ->
            dict.getStringAttr("peers").flatMap { peers ->
                Result(PeerResponse(
                    interval = interval.toInt(),
                    peers = peers.toByteArray().toList()
                ))
            }
        }
    }.mapFailure("Encountered error while trying to decode peer response from server")