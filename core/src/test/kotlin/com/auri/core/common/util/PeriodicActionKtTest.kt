package com.auri.core.common.util

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.core.test.TestScope
import io.kotest.core.test.testCoroutineScheduler
import io.kotest.matchers.equals.shouldBeEqual
import io.kotest.matchers.ints.shouldBeExactly
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.time.shouldHaveSeconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class PeriodicActionKtTest : BehaviorSpec({
    coroutineTestScope = true

    Given("A periodic action configuration") {
        val config = PeriodicActionConfig(
            performEvery = 5.seconds,
            maxRetriesPerPerform = 3,
            skipPerformIfFailed = false,
            retryEvery = 1.seconds
        )

        When("The action always succeeds") {
            Then("The action should be executed until cancelled and delays should always be [performEvery]") {
                val expectedValues = listOf(
                    0.right() to Duration.ZERO,
                    1.right() to config.performEvery,
                    2.right() to config.performEvery,
                    3.right() to config.performEvery,
                    4.right() to config.performEvery,
                    5.right() to config.performEvery,
                )

                var counter = 0
                val resultFlow = config.perform<Unit, Int> {
                    counter.also { counter++ }
                }

                resultFlow shouldEmitAtLeast expectedValues
            }
        }

        When("The action fails") {
            Then("The action should be executed [maxRetriesPerPerform] times and delays should always be [retryEvery]") {
                val expectedValues = listOf(
                    Unit.left() to Duration.ZERO,
                    Unit.left() to config.retryEvery,
                    Unit.left() to config.retryEvery,
                    Unit.left() to config.retryEvery,
                )

                var counter = 0
                val resultFlow = config.perform {
                    counter.also { counter++ }
                    raise(Unit)
                }

                resultFlow shouldEmitExactly expectedValues
            }
        }
    }
})

context(TestScope)
@OptIn(ExperimentalStdlibApi::class, ExperimentalCoroutinesApi::class)
suspend infix fun Flow<Either<Unit, Int>>.shouldEmitAtLeast(expected: List<Pair<Either<Unit, Int>, Duration>>) {
    val timeSource = testCoroutineScheduler.timeSource
    var lastEmissionTime = timeSource.markNow()
    val lastIndexEmitted = withIndex()
        .takeWhile { it.index < expected.size }
        .map { it to expected[it.index] }
        .onEach { (result, expected) ->
            val resultValue = result.value
            val resultTime = lastEmissionTime.elapsedNow()
            lastEmissionTime = timeSource.markNow()
            withClue("Value was not emitted at the expected time") {
                resultTime shouldHaveSeconds expected.second.inWholeSeconds
            }
            resultValue shouldBeEqual expected.first
        }.last().first.index
    withClue("Flow did not emit all expected values") {
        lastIndexEmitted shouldBeExactly expected.size - 1
    }
}

context(TestScope)
suspend infix fun Flow<Either<Unit, Int>>.shouldEmitExactly(expected: List<Pair<Either<Unit, Int>, Duration>>) =
    withIndex()
        .onEach {
            withClue("Flow emitted more values tha expected") {
                it.index shouldBeLessThan expected.size
            }
        }.map { it.value } shouldEmitAtLeast expected