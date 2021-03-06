package mutex

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineIntrinsics

class Mutex {
    /*
       Note: this is a non-optimized implementation designed for understandability, so it just
       uses AtomicInteger and ConcurrentLinkedQueue instead of of embedding these data structures right here
       to optimize object count per mutex.
    */

    // -1 == unlocked, >= 0 -> number of active waiters
    private val state = AtomicInteger(-1)
    // can have more waiters than registered in state (we add waiter first)
    private val waiters = ConcurrentLinkedQueue<Waiter>()

    suspend fun lock() {
        // fast path -- try lock uncontended
        if (state.compareAndSet(-1, 0)) return
        // slow path -- other cases
        return CoroutineIntrinsics.suspendCoroutineOrReturn sc@ { c ->
            // tentatively add a waiter before locking (and we can get resumed because of that!)
            val waiter = Waiter(c)
            waiters.add(waiter)
            loop@ while (true) { // lock-free loop on state
                val curState = state.get()
                if (curState == -1) {
                    if (state.compareAndSet(-1, 0)) {
                        // locked successfully this time, there were no _other_ waiter -- mark us as resumed,
                        // but check we were already resummed in bettween waiters.add(...) and state.cas(...) by
                        // somebody else
                        if (waiter.resumed)
                            break@loop // was already resumed by some other thread -> indicate suspend
                        waiter.resumed = true // mark ourselves as already resumed in queue
                        // for simplicity, don't attempt to unlink the  Waiter object from the queue
                        return@sc Unit // don't suspend, but continue execution with lock

                    }
                } else { // state >= 0 -- already locked --> increase waiters count and sleep peacefully until resumed
                    check(curState >= 0)
                    if (state.compareAndSet(curState, curState + 1)) {
                        break@loop
                    }
                }
            }
            CoroutineIntrinsics.SUSPENDED // suspend
        }
    }

    fun unlock() {
        while (true) { // look-free loop on state
            // see if can unlock
            val curState = state.get()
            if (curState == 0) {
                // cannot have any waiters in this state, because we are holding a mutex and only mutex-holder
                // can reduce the number of waiters
                if (state.compareAndSet(0, -1))
                    return // successfully unlocked, no waiters were there to resume
            } else {
                check(curState >= 1)
                // now decrease waiters count and resume waiter
                if (state.compareAndSet(curState, curState - 1)) {
                    // must have a waiter!!
                    retrieveWaiter()!!.c.resume(Unit)
                    return
                }
            }
        }
    }

    private fun retrieveWaiter(): Waiter? {
        while (true) {
            val waiter = waiters.poll() ?: return null
            // see if this is an _actual_ waiter (not resumed yet by some previous mutex holder)
            if (!waiter.resumed)
                return waiter
            // otherwise it is an artefact, just look for the next one
        }
    }

    private class Waiter(val c: Continuation<Unit>) {
        var resumed = false
    }
}
