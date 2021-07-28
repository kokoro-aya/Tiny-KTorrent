package moe.irony.utils.fp

import moe.irony.utils.fp.Result

fun <A> sequenceLeft(list: List<Result<A>>): Result<List<A>> =
    list.fold<Result<A>, Result<List<A>>>(
        Result(listOf())
    ) { x, y ->
        map2(y, x) { a: A -> { b: List<A> -> b + a }}
    }.map { it.reversed() }
