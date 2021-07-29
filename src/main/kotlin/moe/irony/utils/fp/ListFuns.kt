package moe.irony.utils.fp

import moe.irony.utils.fp.Result
import kotlin.math.min

fun <A> sequenceLeft(list: List<Result<A>>): Result<List<A>> =
    list.fold<Result<A>, Result<List<A>>>(
        Result(listOf())
    ) { x, y ->
        map2(y, x) { a: A -> { b: List<A> -> b + a }}
    }.map { it.reversed() }

fun <A, S> unfold(init: S, getNext: (S) -> Pair<A, S>?): List<A> {
    tailrec fun unfold_(acc: List<A>, z: S): List<A> {
        val next = getNext(z)
        return when (next) {
            null -> acc
            else -> unfold_(acc + next.first, next.second)
        }
    }
    return unfold_(listOf(), init)
}
