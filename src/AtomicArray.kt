import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.loop

/**
 * @author Belousov Timofey
 */

interface Descriptor {
    fun complete()
}

class Ref<T>(initial: T) {
    internal val backing = atomic<Any?>(initial)

    @Suppress("UNCHECKED_CAST")
    var value: T
        get() {
            backing.loop {
                when (it) {
                    is Descriptor -> it.complete()
                    else -> return it as T
                }
            }
        }
        set(newVal) {
            backing.loop {
                when (it) {
                    is Descriptor -> it.complete()
                    else -> {
                        if (backing.compareAndSet(it, newVal)) {
                            return
                        }
                    }
                }
            }
        }

    fun compareAndSet(expected: Any?, update: Any?): Boolean {
        while (true) {
            if (!backing.compareAndSet(expected, update)) {
                val curVal = backing.value
                if (curVal == expected) {
                    continue
                }
                if (curVal is Descriptor) {
                    value
                    continue
                }

                return false
            }
            return true
        }
    }

    fun doubleCompareSingleSet(expected1: Any?, update1: Any?, value2: Ref<out Any?>, expected2: Any?): Boolean {
        val descriptor = DCSSDescriptor(this, expected1, update1, value2, expected2)

        if (expected1 !== update1 && backing.value === update1) return false

        if (!compareAndSet(expected1, descriptor)) {
            return false
        }

        descriptor.complete()
        return when (descriptor.outcome.value) {
            Outcome.UNDECIDED -> throw IllegalStateException("WTF?")
            Outcome.FAIL -> false
            Outcome.SUCCESS -> true
        }
    }
}

enum class Outcome {
    UNDECIDED,
    FAIL,
    SUCCESS,
}

class DCSSDescriptor<A>(
    private val a: Ref<A>, private val expectedA: Any?, private val updateA: Any?,
    private val b: Ref<out Any?>, private val expectedB: Any?
) : Descriptor {
    var outcome = Ref(Outcome.UNDECIDED)

    override fun complete() {
        if (b.value === expectedB) {
            outcome.compareAndSet(Outcome.UNDECIDED, Outcome.SUCCESS)
        } else {
            outcome.compareAndSet(Outcome.UNDECIDED, Outcome.FAIL)
        }

        val update = when (outcome.value) {
            Outcome.UNDECIDED -> throw IllegalStateException("WTF?")
            Outcome.FAIL -> expectedA
            Outcome.SUCCESS -> updateA
        }

        a.backing.compareAndSet(this, update)
    }
}

class CAS2Descriptor<A, B>(
    private val a: Ref<A>, private val expectedA: A, private val updateA: A,
    private val b: Ref<B>, private val expectedB: B, private val updateB: B
) : Descriptor {
    var outcome = Ref(Outcome.UNDECIDED)
    override fun complete() {
        while (true) {
            if (!b.doubleCompareSingleSet(expectedB, this, outcome, Outcome.UNDECIDED)) {
                when (outcome.value) {
                    Outcome.FAIL -> {
                        a.backing.compareAndSet(this, expectedA)
                        b.backing.compareAndSet(this, expectedB)
                        return
                    }

                    Outcome.SUCCESS -> {
                        a.backing.compareAndSet(this, updateA)
                        b.backing.compareAndSet(this, updateB)
                        return
                    }

                    Outcome.UNDECIDED -> {
                        val backingB = b.backing.value
                        if (backingB === this) {
                            break
                        }
                        if (backingB is Descriptor) {
                            backingB.complete()
                            continue
                        }
                        outcome.compareAndSet(Outcome.UNDECIDED, Outcome.FAIL)
                        a.backing.compareAndSet(this, expectedA)
                        return
                    }
                }
            }
            break
        }

        if (!outcome.compareAndSet(Outcome.UNDECIDED, Outcome.SUCCESS)) {
            val update = when (outcome.value) {
                Outcome.UNDECIDED -> throw IllegalStateException("WTF?")
                Outcome.FAIL -> expectedA
                Outcome.SUCCESS -> updateA
            }

            a.backing.compareAndSet(this, update)
            return
        }

        when (outcome.value) {
            Outcome.UNDECIDED -> throw IllegalStateException("WTF?")
            Outcome.FAIL -> {
                a.backing.compareAndSet(this, expectedA)
                b.backing.compareAndSet(this, expectedB)
            }

            Outcome.SUCCESS -> {
                a.backing.compareAndSet(this, updateA)
                b.backing.compareAndSet(this, updateB)
            }
        }
    }
}

class AtomicArray<E>(size: Int, initialValue: E) {
    private val a = Array(size) {
        Ref(initialValue)
    }

    fun get(index: Int) =
        a[index].value

    fun set(index: Int, value: E) {
        a[index].value = value
    }

    fun cas(index: Int, expected: E, update: E): Boolean = a[index].compareAndSet(expected, update)

    fun cas2(
        index1: Int, expected1: E, update1: E,
        index2: Int, expected2: E, update2: E
    ): Boolean { // lol, no difference
        if (index1 == index2 && expected1 !== expected2) return false
        if (index1 == index2) return cas(index2, expected2, update2) // this should be banned in the first place but
        // the sequential model in tests allow this.
        if (index1 > index2) return cas2(index2, expected2, update2, index1, expected1, update1)

        val descriptor = CAS2Descriptor(a[index1], expected1, update1, a[index2], expected2, update2)

        if (!a[index1].compareAndSet(expected1, descriptor)) {
            return false
        }

        descriptor.complete()
        return when (descriptor.outcome.value) {
            Outcome.UNDECIDED -> throw IllegalStateException("WTF?")
            Outcome.FAIL -> false
            Outcome.SUCCESS -> true
        }
    }
}
