package Chapter03_Superpowers

import zio.*
import zio.direct.*

enum Scenario:
  case HappyPath
  case NeverWorks
  case Slow
  case WorksOnTry(attempts: Int, ref: Ref[Int])

// This configuration is used by effects to get the scenario that
// may have been passed in via `bootstrap`
// The configuration is optional and the default of `Config.fail`
// sets the Option to None.
val scenarioConfig: Config[Option[Scenario]] =
  Config.Optional[Scenario](
    Config.fail("no default scenario")
  )

class StaticConfigProvider(scenario: Scenario)
    extends ConfigProvider:
  override def load[A](config: Config[A])(
      implicit trace: Trace
  ): IO[Config.Error, A] =
    ZIO.succeed(Some(scenario).asInstanceOf[A])

val happyPath =
  Runtime.setConfigProvider(
    StaticConfigProvider(Scenario.HappyPath)
  )

val neverWorks =
  Runtime.setConfigProvider(
    StaticConfigProvider(Scenario.NeverWorks)
  )

val slow =
  Runtime.setConfigProvider(
    StaticConfigProvider(Scenario.Slow)
  )

val doesNotWorkInitially =
  val scenario =
    Unsafe.unsafe {
      implicit unsafe =>
        Scenario.WorksOnTry(
          3,
          Runtime
            .default
            .unsafe
            .run(Ref.make(1))
            .getOrThrow()
        )
    }
  Runtime.setConfigProvider(
    StaticConfigProvider(scenario)
  )

def saveUser(username: String) =
  val succeed =
    ZIO.succeed:
      "User saved"
  val fail =
    ZIO
      .fail:
        "**Database crashed!!**"
      .tapError:
        error =>
          Console.printLine:
            "Log: " + error
  defer:
    val maybeScenario =
      ZIO.config(scenarioConfig).run
    maybeScenario
      .getOrElse(Scenario.HappyPath) match
      case Scenario.HappyPath =>
        succeed.run

      case Scenario.NeverWorks =>
        fail.run

      case Scenario.Slow =>
        ZIO
          .sleep(1.minute)
          .onInterrupt:
            ZIO.debug(
              "Log: Interrupting slow request"
            )
          .run
        succeed.run

      case Scenario.WorksOnTry(attempts, ref) =>
        val numCalls =
          ref.getAndUpdate(_ + 1).run
        if numCalls == attempts then
          succeed.run
        else
          fail.run
    end match
end saveUser

def sendToManualQueue(username: String) =
  ZIO.attempt(
    s"Please manually provision $username"
  )

val logUserSignup =
  Console
    .printLine:
      s"Log: Signup initiated for $userName"
    .orDie

val userName =
  "Morty"

val effect0 =
  saveUser:
    userName

object MyApp extends ZIOAppDefault:
  def run =
    effect0

object Chapter03_Superpowers_0 extends helpers.ZIOAppDebug:
  def run =
    effect0
  // Result: User saved


object Chapter03_Superpowers_1 extends helpers.ZIOAppDebug:
  override val bootstrap =
    happyPath
  
  def run =
    effect0
  // Result: User saved


object Chapter03_Superpowers_2 extends helpers.ZIOAppDebug:
  override val bootstrap =
    neverWorks
  
  def run =
    effect0
  // Log: **Database crashed!!**
  // Result: **Database crashed!!**


val effect1 =
  effect0.retryN(2)

object Chapter03_Superpowers_3 extends helpers.ZIOAppDebug:
  override val bootstrap =
    doesNotWorkInitially
  
  def run =
    effect1
  // Log: **Database crashed!!**
  // Log: **Database crashed!!**
  // Result: User saved


object Chapter03_Superpowers_4 extends helpers.ZIOAppDebug:
  override val bootstrap =
    neverWorks
  
  def run =
    effect1
  // Log: **Database crashed!!**
  // Log: **Database crashed!!**
  // Log: **Database crashed!!**
  // Result: **Database crashed!!**


val effect2 =
  effect1.orElseFail:
    "ERROR: User could not be saved"

object Chapter03_Superpowers_5 extends helpers.ZIOAppDebug:
  override val bootstrap =
    neverWorks
  
  def run =
    effect2
  // Log: **Database crashed!!**
  // Log: **Database crashed!!**
  // Log: **Database crashed!!**
  // Result: ERROR: User could not be saved


val effect3 =
  effect2.timeoutFail("*** Save timed out ***"):
    5.seconds

object Chapter03_Superpowers_6 extends helpers.ZIOAppDebug:
  override val bootstrap =
    slow
  
  def run =
    effect3
  // Result: *** Save timed out ***


val effect4 =
  effect3.orElse:
    sendToManualQueue:
      userName

object Chapter03_Superpowers_7 extends helpers.ZIOAppDebug:
  override val bootstrap =
    neverWorks
  
  def run =
    effect4
  // Log: **Database crashed!!**
  // Result: Please manually provision Morty


val effect5 =
  effect4.withFinalizer:
    _ => logUserSignup

object Chapter03_Superpowers_8 extends helpers.ZIOAppDebug:
  override val bootstrap =
    happyPath
  
  def run =
    effect5
  // Result: User saved


val effect6 =
  effect5.timed

object Chapter03_Superpowers_9 extends helpers.ZIOAppDebug:
  override val bootstrap =
    happyPath
  
  def run =
    effect6
  // Result: (PT5.039673886S,User saved)


val effect7 =
  effect6.when(userName != "Morty")

object Chapter03_Superpowers_10 extends helpers.ZIOAppDebug:
  override val bootstrap =
    happyPath
  
  def run =
    effect7
  // Result: None


val program =
  defer:
    Console.printLine("Hello").run
    val subject =
      "world"
    Console.printLine(subject).run

val programManipulatingBeforeRun =
  defer:
    Console.printLine("Hello").repeatN(3).run

val surroundedProgram =
  defer:
    Console.printLine("**Before**").run
    program.run
    Console.printLine("**After**").run

object Chapter03_Superpowers_11 extends helpers.ZIOAppDebug:
  def run =
    surroundedProgram
  // **Before**
  // Hello
  // world
  // **After**
