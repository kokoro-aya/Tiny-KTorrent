package moe.irony.utils.fp

import kotlin.math.pow

sealed class Option<out A> {

    abstract fun isEmpty(): Boolean

    abstract fun <B> map(f: (A) -> B): Option<B>

    fun <B> flatMap(f: (A) -> Option<B>): Option<B> = map(f).getOrElse(None)

    fun filter(p: (A) -> Boolean): Option<A> =
        flatMap { x -> if (p(x)) this else None }

    fun orElse(default: () -> Option<@UnsafeVariance A>): Option<A> = map { this }.getOrElse(default)

    fun getOrElse(default: @UnsafeVariance A): A = when (this) {
        None -> default
        is Some -> value
    }

    fun getOrElse(default: () -> @UnsafeVariance A): A = when (this) {
        None -> default()
        is Some -> value
    }

    // Added for convenience in class PieceManager
    fun mapNone(other: () -> Option<@UnsafeVariance A>): Option<A> = when (this) {
        None -> other.invoke()
        is Some -> this
    }

    fun toNullable(): A? = when (this) {
        None -> null
        is Some -> value
    }

    internal object None: Option<Nothing>() {

        override fun <B> map(f: (Nothing) -> B): Option<B> = None

        override fun isEmpty() = true

        override fun toString(): String = "None"

        override fun equals(other: Any?): Boolean = other === None

        override fun hashCode(): Int = 0
    }

    internal data class Some<out A>(internal val value: A) : Option<A>() {

        override fun <B> map(f: (A) -> B): Option<B> = Some(f(value))

        override fun isEmpty() = false
    }

    companion object {

        fun <A> getOrElse(option: Option<A>, default: A): A = when (option) {
            None -> default
            is Some -> option.value
        }

        fun <A> getOrElse(option: Option<A>, default: () -> A): A = when (option) {
            None -> default()
            is Some -> option.value
        }

        fun <A> toOption(nullable: A?) = when (nullable) {
            null -> None
            else -> Some(nullable)
        }

        operator fun <A> invoke(a: A? = null): Option<A> = when (a) {
            null -> None
            else -> Some(a)
        }
    }
}

fun <A, B> lift(f: (A) -> B): (Option<A>) -> Option<B> = {
    try {
        it.map(f)
    } catch (e: Exception) {
        Option()
    }
}

fun <A, B> hLift(f: (A) -> B): (A) -> Option<B> = {
    try {
        Option(it).map(f)
    } catch (e: Exception) {
        Option()
    }
}

fun <A, B, C> map2(oa: Option<A>,
                   ob: Option<B>,
                   f: (A) -> (B) -> C): Option<C> =
    oa.flatMap { a -> ob.map { b -> f(a)(b) } }

fun <A, B, C, D> map3(oa: Option<A>,
                      ob: Option<B>,
                      oc: Option<C>,
                      f: (A) -> (B) -> (C) -> D): Option<D> =
    oa.flatMap { a -> ob.flatMap { b -> oc.map { c -> f(a)(b)(c) } } }