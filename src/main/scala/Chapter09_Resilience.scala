package Chapter09_Resilience

import zio.*
import zio.direct.*
import zio.Console.*

import zio.{ZIO, ZLayer}
import zio.cache.{Cache, Lookup}

import java.nio.file.{Path, Paths}

case class FSLive(requests: Ref[Int])
    extends CloudStorage:
  def retrieve(
      name: Path
  ): ZIO[Any, Nothing, FileContents] =
    defer:
      requests.update(_ + 1).run
      ZIO.sleep(10.millis).run
      FSLive.hardcodedContents

  val invoice: ZIO[Any, Nothing, String] =
    defer:
      val count = requests.get.run

      "Amount owed: $" + count

object FSLive:
  val hardcodedContents =
    FileContents(
      List("viralImage1", "viralImage2")
    )

case class FileContents(
    contents: List[String]
)

trait CloudStorage:
  def retrieve(
      name: Path
  ): ZIO[Any, Nothing, FileContents]
  val invoice: ZIO[Any, Nothing, String]

object CloudStorage:
  val live =
    ZLayer.fromZIO:
      defer:
        FSLive(Ref.make(0).run)

case class PopularService(
    retrieveContents: Path => ZIO[
      Any,
      Nothing,
      FileContents,
    ]
):
  def retrieve(name: Path) =
    retrieveContents(name)

val thunderingHerds =
  defer:
    val popularService =
      ZIO.service[PopularService].run

    // All requests arrive at once
    ZIO
      .collectAllPar:
        List.fill(100):
          popularService.retrieve:
            Paths.get("awesomeMemes")
      .run

    val cloudStorage =
      ZIO.service[CloudStorage].run

    cloudStorage.invoice.run

val makePopularService =
  defer:
    val cloudStorage =
      ZIO.service[CloudStorage].run
    PopularService(cloudStorage.retrieve)

object App0 extends helpers.ZIOAppDebug:
  def run =
    thunderingHerds.provide(
      CloudStorage.live,
      ZLayer.fromZIO(makePopularService),
    )
  // Result: Amount owed: $100


val makeCachedPopularService =
  defer:
    val cloudStorage =
      ZIO.service[CloudStorage].run
    val cache =
      Cache
        .make(
          capacity = 100,
          timeToLive = Duration.Infinity,
          lookup =
            Lookup(cloudStorage.retrieve),
        )
        .run

    PopularService(cache.get)

object App1 extends helpers.ZIOAppDebug:
  def run =
    thunderingHerds.provide(
      CloudStorage.live,
      ZLayer.fromZIO(makeCachedPopularService),
    )
  // Result: Amount owed: $1


val expensiveApiCall = ZIO.unit

extension [R, E, A](z: ZIO[R, E, A])
  def timedSecondsDebug(
      message: String
  ): ZIO[R, E, A] =
    z.timed
      .tap:
        (duration, _) =>
          printLine(
            message + " [took " +
              duration.getSeconds + "s]"
          ).orDie
      .map(_._2)

import nl.vroste.rezilience.RateLimiter

val makeRateLimiter =
  RateLimiter
    .make(max = 1, interval = 1.second)

object App2 extends helpers.ZIOAppDebug:
  def run =
    defer:
      val rateLimiter = makeRateLimiter.run
  
      rateLimiter:
        expensiveApiCall
      .timedSecondsDebug:
        s"called API"
        // Repeats as fast as allowed
      .repeatN:
        2
      .timedSecondsDebug:
        "Result"
      .run
  // called API [took 0s]
  // called API [took 1s]
  // called API [took 1s]
  // Result [took 2s]


object App3 extends helpers.ZIOAppDebug:
  def run =
    defer:
      val rateLimiter = makeRateLimiter.run
      val people =
        List("Bill", "Bruce", "James")
  
      ZIO
        .foreachPar(people):
          person =>
            rateLimiter:
              expensiveApiCall
            .timedSecondsDebug:
              s"$person called API"
            .repeatN(
              2
            ) // Repeats as fast as allowed
        .timedSecondsDebug:
          "Total time"
        .unit // ignores the list of unit
        .run
  // Bill called API [took 0s]
  // Bruce called API [took 1s]
  // James called API [took 2s]
  // Bill called API [took 3s]
  // Bruce called API [took 3s]
  // James called API [took 3s]
  // Bill called API [took 3s]
  // Bruce called API [took 3s]
  // James called API [took 3s]
  // Total time [took 8s]


trait DelicateResource:
  val request: ZIO[Any, String, Int]

// It can represent any service outside of our control
// that has usage constraints
case class Live(
    currentRequests: Ref[List[Int]],
    alive: Ref[Boolean],
) extends DelicateResource:
  val request =
    defer:
      val res =
        Random.nextIntBounded(1_000).run

      if currentRequests.get.run.length > 3
      then
        alive.set(false).run
        ZIO.fail("Crashed the server!!").run

      // Add request to current requests
      currentRequests
        .updateAndGet(res :: _)
        .debug("Current requests")
        .run

      // Simulate a long-running request
      ZIO.sleep(1.second).run
      removeRequest(res).run

      if alive.get.run then
        res
      else
        ZIO
          .fail(
            "Server crashed from requests!!"
          )
          .run

  private def removeRequest(i: Int) =
    currentRequests.update(_ diff List(i))
end Live

object DelicateResource:
  val live =
    ZLayer.fromZIO:
      defer:
        printLine:
          "Delicate Resource constructed."
        .run
        printLine:
          "Do not make more than 3 concurrent requests!"
        .run
        Live(
          Ref
            .make[List[Int]](List.empty)
            .run,
          Ref.make(true).run,
        )

object App4 extends helpers.ZIOAppDebug:
  def run =
    defer:
      val delicateResource =
        ZIO.service[DelicateResource].run
      ZIO
        .foreachPar(1 to 10):
          _ => delicateResource.request
        .as("All Requests Succeeded!")
        .run
    .provide(DelicateResource.live)
  // Delicate Resource constructed.
  // Do not make more than 3 concurrent requests!
  // Current requests: List(397)
  // Current requests: List(361, 397)
  // Current requests: List(534, 361, 397)
  // Current requests: List(305, 534, 361, 397)
  // Result: Crashed the server!!


import nl.vroste.rezilience.Bulkhead
val makeOurBulkhead =
  Bulkhead.make(maxInFlightCalls = 3)

object App5 extends helpers.ZIOAppDebug:
  def run =
    defer:
      val bulkhead = makeOurBulkhead.run
  
      val delicateResource =
        ZIO.service[DelicateResource].run
      ZIO
        .foreachPar(1 to 10):
          _ =>
            bulkhead:
              delicateResource.request
        .as("All Requests Succeeded")
        .run
    .provide(
      DelicateResource.live,
      Scope.default,
    )
  // Delicate Resource constructed.
  // Do not make more than 3 concurrent requests!
  // Current requests: List(283)
  // Current requests: List(722, 283)
  // Current requests: List(745, 722, 283)
  // Current requests: List(466, 745, 722)
  // Current requests: List(755, 466, 745)
  // Current requests: List(456, 755, 466)
  // Current requests: List(597, 456)
  // Current requests: List(702, 597, 456)
  // Current requests: List(78, 702, 597)
  // Current requests: List(412, 78)
  // Result: All Requests Succeeded


import zio.Ref

import java.time.Instant
import scala.concurrent.TimeoutException

// Invisible mdoc fencess
import zio.Runtime.default.unsafe
val timeSensitiveValue =
  Unsafe.unsafe(
    (u: Unsafe) =>
      given Unsafe =
        u
      unsafe
        .run(
          scheduledValues(
            (1_100.millis, true),
            (4_100.millis, false),
            (5_000.millis, true),
          )
        )
        .getOrThrowFiberFailure()
  )

def externalSystem(numCalls: Ref[Int]) =
  defer:
    numCalls.update(_ + 1).run
    val b = timeSensitiveValue.run
    if b then
      ZIO.unit.run
    else
      ZIO.fail(()).run

object InstantOps:
  extension (i: Instant)
    def plusZ(
        duration: zio.Duration
    ): Instant =
      i.plus(duration.asJava)

import InstantOps.*

/* Goal: If I accessed this from:
 * 0-1 seconds, I would get "First Value" 1-4
 * seconds, I would get "Second Value" 4-14
 * seconds, I would get "Third Value" 14+
 * seconds, it would fail */

def scheduledValues[A](
    value: (Duration, A),
    values: (Duration, A)*
): ZIO[
  Any, // construction time
  Nothing,
  ZIO[
    Any, // access time
    TimeoutException,
    A,
  ],
] =
  defer:
    val startTime = Clock.instant.run
    val timeTable =
      createTimeTableX(
        startTime,
        value,
        values* // Yay Scala3 :)
      )
    accessX(timeTable)

// make this function more obvious
private def createTimeTableX[A](
    startTime: Instant,
    value: (Duration, A),
    values: (Duration, A)*
): Seq[ExpiringValue[A]] =
  values.scanLeft(
    ExpiringValue(
      startTime.plusZ(value._1),
      value._2,
    )
  ):
    case (
          ExpiringValue(elapsed, _),
          (duration, value),
        ) =>
      ExpiringValue(
        elapsed.plusZ(duration),
        value,
      )

/** Input: (1 minute, "value1") (2 minute,
  * "value2")
  *
  * Runtime: Zero value: (8:00 + 1 minute,
  * "value1")
  *
  * case ((8:01, _) , (2.minutes, "value2"))
  * \=> (8:01 + 2.minutes, "value2")
  *
  * Output: ( ("8:01", "value1"), ("8:03",
  * "value2") )
  */
private def accessX[A](
    timeTable: Seq[ExpiringValue[A]]
): ZIO[Any, TimeoutException, A] =
  defer:
    val now = Clock.instant.run
    ZIO
      .getOrFailWith(
        new TimeoutException("TOO LATE")
      ):
        timeTable
          .find(
            _.expirationTime.isAfter(now)
          )
          .map(_.value)
      .run

private case class ExpiringValue[A](
    expirationTime: Instant,
    value: A,
)

val repeatSchedule =
  Schedule.recurs(140) &&
    Schedule.spaced(50.millis)

object App6 extends helpers.ZIOAppDebug:
  def run =
    defer:
      val numCalls = Ref.make[Int](0).run
  
      externalSystem(numCalls)
        .ignore
        .repeat(repeatSchedule)
        .run
  
      val made = numCalls.get.run
  
      s"Calls made: $made"
  // Result: Calls made: 141


import nl.vroste.rezilience.{
  CircuitBreaker,
  TrippingStrategy,
  Retry,
}

val makeCircuitBreaker =
  CircuitBreaker.make(
    trippingStrategy =
      TrippingStrategy
        .failureCount(maxFailures = 2),
    resetPolicy = Retry.Schedules.common(),
  )

object App7 extends helpers.ZIOAppDebug:
  import CircuitBreaker.CircuitBreakerOpen
  
  def run =
    defer:
      val cb = makeCircuitBreaker.run
  
      val numCalls     = Ref.make[Int](0).run
      val numPrevented = Ref.make[Int](0).run
  
      val protectedCall =
        cb(externalSystem(numCalls)).catchAll:
          case CircuitBreakerOpen =>
            numPrevented.update(_ + 1)
          case other =>
            ZIO.unit
  
      protectedCall
        .ignore
        .repeat(repeatSchedule)
        .run
  
      val prevented = numPrevented.get.run
  
      val made = numCalls.get.run
      s"Prevented: $prevented Made: $made"
  // Result: Prevented: 74 Made: 67


val sometimesSlowRequest =
  defer:
    if Random.nextIntBounded(1_000).run == 0
    then
      ZIO.sleep(3.second).run

case class Scenario(
    effect: ZIO[Any, Nothing, Unit]
)

def makeLotsOfRequests(scenario: Scenario) =
  defer:
    val totalRequests = 50_000

    val failOnBreach =
      scenario
        .effect
        .timeoutFail("took too long"):
          1.second

    val successes =
      ZIO
        .collectAllSuccessesPar:
          List.fill(totalRequests):
            failOnBreach
        .run

    val contractBreaches =
      totalRequests - successes.length

    s"Contract Breaches: $contractBreaches"

object App8 extends helpers.ZIOAppDebug:
  def run =
    makeLotsOfRequests:
      Scenario(sometimesSlowRequest)
  // Result: Contract Breaches: 48


val hedged =
  sometimesSlowRequest.race:
    sometimesSlowRequest.delay:
      25.millis

object App9 extends helpers.ZIOAppDebug:
  def run =
    makeLotsOfRequests:
      Scenario(hedged)
  // Result: Contract Breaches: 0


val attemptsR =
  Unsafe.unsafe:
    implicit unsafe =>
      Runtime
        .default
        .unsafe
        .run(Ref.make(0))
        .getOrThrowFiberFailure()

def spottyLogic =
  defer:
    val attemptsCur =
      attemptsR.getAndUpdate(_ + 1).run
    if ZIO.attempt(attemptsCur).run == 3 then
      printLine("Success!").run
      ZIO.succeed(1).run
    else
      printLine("Failed!").run
      ZIO.fail("Failed").run