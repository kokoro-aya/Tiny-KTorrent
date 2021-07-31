package moe.irony.utils.fp

import moe.irony.utils.fp.Result
import kotlin.math.min
import kotlin.random.Random

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

fun <T> iterate(seed: T, f: (T) -> T, n: Int): List<T> {
    tailrec fun iterate_(acc: List<T>, sd: T): List<T> =
        if (acc.size < n)
            iterate_(acc + sd, f(sd))
        else
            acc
    return iterate_(listOf(), seed)
}
