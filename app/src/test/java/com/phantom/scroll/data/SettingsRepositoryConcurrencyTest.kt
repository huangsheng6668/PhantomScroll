package com.phantom.scroll.data

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SettingsRepositoryConcurrencyTest {

    private fun TestScope.repoWith(store: FakeProfileStore): SettingsRepository {
        val repoScope = CoroutineScope(coroutineContext + Job())
        val testDispatcher = checkNotNull(coroutineContext[CoroutineDispatcher])
        return SettingsRepository(store, repoScope, ioDispatcher = testDispatcher)
            .also { advanceUntilIdle() }
    }

    // Race 1 (commit 6d95e25): defaults written back over real disk values during load.
    @Test
    fun load_completes_before_persistence_starts_no_default_overwrite() = runTest {
        val realDiskGlobal = ScrollSettings(duration = 800L, interval = 3000L, distanceRatio = 0.6f)
        val store = FakeProfileStore().apply { global = realDiskGlobal }
        val repo = repoWith(store)
        // After load, disk value must be intact (not overwritten by the DEFAULT seed).
        repo.flush()
        assertEquals(realDiskGlobal, store.global)
    }

    // Race 2 (commit 349feea): rapid per-app toggle leaves inconsistent profile/flag.
    @Test
    fun rapid_perApp_toggle_ends_consistent() = runTest {
        val store = FakeProfileStore()
        val repo = repoWith(store)
        repo.apply(SettingsIntent.PackageSwitched("com.a"))
        repeat(5) {
            repo.apply(SettingsIntent.PerAppToggled(true))
            repo.apply(SettingsIntent.PerAppToggled(false))
        }
        repo.apply(SettingsIntent.PerAppToggled(true))
        advanceUntilIdle()
        assertTrue(repo.perAppEnabled.value)
        assertEquals("com.a", repo.profiles.value.keys.singleOrNull())
    }

    // Race 3 (commit 6d95e25): edit during a package switch writes to the wrong (old) target.
    @Test
    fun edit_during_package_switch_writes_to_current_not_old() = runTest {
        val repo = repoWith(FakeProfileStore())
        repo.apply(SettingsIntent.PackageSwitched("com.old"))
        repo.apply(SettingsIntent.PerAppToggled(true)) // per-app on, profile for com.old
        repo.apply(SettingsIntent.PackageSwitched("com.new"))
        repo.apply(SettingsIntent.PerAppToggled(true)) // profile for com.new
        // Edit now targets com.new, not com.old.
        val edited = ScrollSettings(duration = 600L, interval = 2000L, distanceRatio = 0.6f)
        repo.apply(SettingsIntent.SettingEdited { edited })
        advanceUntilIdle()
        assertEquals(edited, repo.profiles.value["com.new"]?.settings)
        assertNotEquals(edited, repo.profiles.value["com.old"]?.settings)
    }

    // Race 4 (T2 ANR, fixed in PhantomScrollService.onDestroy): the historical ANR was
    // caused by `runBlocking` executing on the main thread during service shutdown. The
    // fix removed runBlocking from production and made `flush()` a cooperative `suspend`
    // fun invoked under `withTimeout` on Dispatchers.IO.
    //
    // The pure "no ANR / no main-thread blocking" guarantee cannot be pinned by a unit
    // test under runTest (there is no real main thread and no real dispatcher blocking
    // in virtual time — the old test asserting `flush()` merely completes was
    // near-tautological: it passed even if a regression reintroduced a blocking call).
    //
    // Option A (assert saveGlobal runs on the IO dispatcher, not the caller's) is NOT
    // feasible WITHOUT changing production code: `flush()` deliberately runs
    // `savePersistable` inline on the caller's dispatcher — its contract (documented on
    // `flush()` and honored by PhantomScrollService.onDestroy, which launches it on
    // Dispatchers.IO) is that the CALLER offloads to IO. That is the intended design,
    // not a bug, so Option A would test a property the code does not have.
    //
    // This test therefore pins the ACTUAL regression mechanism (Option B, strengthened):
    //   (a) `flush()` is declared `suspend` — so it can be cancelled cooperatively by
    //       `withTimeout` on the caller side (the runtime property that bounds shutdown
    //       and lets the Service avoid a hang). A non-suspend `flush()` would make the
    //       `withTimeout(1500L) { repository.flush() }` in PhantomScrollService a
    //       compile error, so this guards the contract at the language level.
    //   (b) No UNBOUNDED `runBlocking` exists in production. The single legitimate use is
    //       PhantomScrollService.onDestroy, which wraps a Dispatchers.IO flush in
    //       withTimeout(1500L) so it cannot ANR. Any runBlocking elsewhere, or an onDestroy
    //       runBlocking without a nearby withTimeout, is the literal ANR root cause.
    @Test
    fun flush_is_cooperative_and_no_unbounded_runBlocking_in_production() {
        // (a) flush() must be suspend — a blocking (non-suspend) flush would defeat the
        // withTimeout-based shutdown path in PhantomScrollService.onDestroy. Kotlin
        // compiles a suspend fun to a JVM method taking a trailing
        // kotlin.coroutines.Continuation parameter (and the no-arg lookup throws
        // NoSuchMethodException for a suspend fun). So we locate the overload WITH the
        // Continuation parameter and assert it exists; a non-suspend flush would only
        // have the no-arg overload, which we prove absent.
        val noArgFlush = runCatching {
            SettingsRepository::class.java.getMethod("flush")
        }.isFailure
        val suspendingFlush = runCatching {
            SettingsRepository::class.java.getMethod(
                "flush",
                kotlin.coroutines.Continuation::class.java
            )
        }.isSuccess
        assertTrue(
            "flush() must be suspend (cooperative): a non-suspend flush would block the " +
                "caller and reintroduce the T2 ANR (PhantomScrollService bounds shutdown via " +
                "withTimeout { repository.flush() }). noArgFlushExists=$noArgFlush, " +
                "suspendFlushExists=$suspendingFlush.",
            noArgFlush && suspendingFlush
        )

        // (b) The ANR root cause — an UNBOUNDED `runBlocking` on the main thread — must not
        // recur. The single legitimate use is PhantomScrollService.onDestroy, which wraps a
        // Dispatchers.IO flush inside a withTimeout(1500L) hard cap so it cannot ANR. Any
        // other runBlocking in production, or an onDestroy runBlocking WITHOUT a nearby
        // withTimeout, is a regression.
        val productionRoot = listOf(
            java.io.File("src/main/java"),          // cwd == app module dir
            java.io.File("app/src/main/java")       // cwd == project root
        ).firstOrNull { it.exists() }
        checkNotNull(productionRoot) {
            "production source root not found (tried src/main/java and app/src/main/java " +
                "relative to cwd '${java.io.File(".").absolutePath}')"
        }
        data class Hit(val file: String, val offset: Int)
        val allHits = productionRoot.walkTopDown()
            .filter { it.isFile && it.extension.equals("kt", ignoreCase = true) }
            .flatMap { file ->
                Regex("\\brunBlocking\\b").findAll(file.readText())
                    .map { Hit(file.path, it.range.first) }
                    .toList()
            }
            .toList()
        // Each runBlocking hit must either be inside PhantomScrollService.kt AND have a
        // withTimeout within the same file (the bounded onDestroy flush). Anything else is
        // an unbounded main-thread block = ANR vector.
        val offenders = allHits.filter { hit ->
            val isBoundedOnDestroyFlush =
                hit.file.endsWith("PhantomScrollService.kt") &&
                    java.io.File(hit.file).readText().contains("\\bwithTimeout\\b".toRegex())
            !isBoundedOnDestroyFlush
        }
        assertTrue(
            "runBlocking is only permitted in PhantomScrollService.onDestroy wrapped in " +
                "withTimeout (bounded IO flush). Any other runBlocking, or an onDestroy " +
                "runBlocking without withTimeout, is an unbounded main-thread block (ANR). " +
                "Offenders:\n${offenders.joinToString("\n") { "${it.file} @ ${it.offset}" }}",
            offenders.isEmpty()
        )
    }

    // Race 5 (commit ededb56): high-frequency writes collapse to a bounded number of
    // persisted writes per debounce window — NOT one disk write per apply(). Asserting
    // only the final value (last-write-wins) would pass even with debounce REMOVED, so
    // this test additionally asserts the global save COUNT is small and bounded. Without
    // the debounce operator, N rapid apply() calls would yield ~N saveGlobal invocations
    // (one per combine() emission reaching the collector); with debounce they collapse
    // to one emission after the 500ms quiet window.
    @Test
    fun debounce_collapses_high_frequency_writes() = runTest {
        val store = FakeProfileStore()
        val repo = repoWith(store)
        val n = 20
        // Baseline after init/drain: reconcile may have produced a save or two on some
        // flows; capture it so the burst delta is measured cleanly.
        advanceUntilIdle()
        val savesBeforeWrites = store.globalSaveCount.get()

        // Fire N rapid writes with NO virtual time advancing in between, so they all
        // land inside one 500ms debounce window.
        repeat(n) { i ->
            repo.apply(SettingsIntent.PresetApplied(ScrollSettings(duration = (500L + i), interval = 2000L, distanceRatio = 0.7f)))
        }

        // DELAY assertion: with debounce present, NONE of the in-flight writes have
        // reached disk yet — the collector's emission is parked behind the 500ms timer.
        // (runTest's StandardTestDispatcher only advances virtual time on
        // advanceTimeBy/advanceUntilIdle; the repeat() loop above did not advance time.)
        assertEquals(
            "no save should occur while writes are still arriving inside the debounce window",
            savesBeforeWrites, store.globalSaveCount.get()
        )

        // Now let the 500ms debounce window elapse so the single collapsed emission fires.
        advanceTimeBy(600)
        advanceUntilIdle()

        // COLLAPSE assertion: the save delta must be SMALL and BOUNDED, NOT == n.
        // The clean expectation is exactly 1 (one collapsed post-debounce emission).
        // Tolerance: allow 1..2 to absorb an off-by-one in combine().debounce() under
        // virtual time (a leading emission at the window boundary could yield one extra).
        // This still FAILS if debounce were removed: the collector would emit once per
        // apply() and the delta would be ~n=20.
        val delta = store.globalSaveCount.get() - savesBeforeWrites
        assertTrue(
            "debounce must collapse $n writes to a bounded count; got $delta saves " +
                "(expected 1..2). Without debounce this would be ~$n.",
            delta in 1..2
        )

        // Last-write-wins still holds: the persisted value is the final preset (500 + (n-1) = 519L).
        assertEquals(519L, store.global.duration)
    }

    // Race 6: flush on shutdown does not lose the last change.
    @Test
    fun flush_before_scope_cancel_persists_last_change() = runTest {
        val store = FakeProfileStore()
        val repoScope = CoroutineScope(coroutineContext + Job())
        val testDispatcher = checkNotNull(coroutineContext[CoroutineDispatcher])
        val repo = SettingsRepository(store, repoScope, ioDispatcher = testDispatcher)
        advanceUntilIdle()
        val last = ScrollSettings(duration = 750L, interval = 2500L, distanceRatio = 0.72f)
        repo.apply(SettingsIntent.PresetApplied(last))
        repo.flush() // explicit flush before cancel
        repoScope.cancel()
        assertEquals(last, store.global)
    }

    // Race 7 (commit f430d93): forgetActiveApp is atomic (profile delete + perApp off together).
    @Test
    fun forgetActiveApp_is_atomic() = runTest {
        val store = FakeProfileStore().apply {
            profiles["com.a"] = AppProfile("com.a", ScrollSettings.DEFAULT)
        }
        val repo = repoWith(store)
        repo.apply(SettingsIntent.PackageSwitched("com.a"))
        repo.apply(SettingsIntent.PerAppToggled(true))
        advanceUntilIdle()
        repo.apply(SettingsIntent.ForgetActiveApp)
        // After one apply(), both effects visible together (no intermediate inconsistent state).
        assertNull(repo.profiles.value["com.a"])
        assertFalse(repo.perAppEnabled.value)
    }
}
