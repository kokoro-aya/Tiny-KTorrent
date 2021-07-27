package moe.irony.bencode_decoder

import moe.irony.utils.fp.Result

data class PeerResponse(val interval: Int, val peers: List<UByte>)

fun Result<DictionaryLiteral>.getPeerResponse(): Result<PeerResponse> =
    this.flatMap { dict ->
        dict.getIntAttr("interval").flatMap { interval ->
            dict.getStringAttr("peers").flatMap { peers ->
                Result(PeerResponse(
                    interval = interval.toInt(),
                    peers = peers.toByteArray().map { it.toUByte() }
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