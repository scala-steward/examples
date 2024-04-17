import zio.*
import zio.direct.*

object HelloWorld extends zio.ZIOAppDefault:
  def run =
    ZIO.debug:
      "hello, world"

// NOTE We cannot execute invoke main on this
// because it crashes mdoc in the CI process
object RunningZIOs extends ZIOAppDefault:
  def run =
    //  TODO Console/debug don't work
    ZIO.attempt:
      println:
        "Hello World!"

val logic =
  defer:
    val username =
      Console
        .readLine:
          "Enter your name\n"
        .run
    Console
      .printLine:
        s"Hello $username"
      .run
  .orDie

val out =
  Unsafe.unsafe:
    implicit u: Unsafe =>
      Runtime
        .default
        .unsafe
        .run:
          ZIO.debug:
            "hello, world"
        .getOrThrowFiberFailure()

object Example10_Appendix_RunningEffects_0 extends ZIOAppDefault:
  def run =
    ZIO.debug:
      "hello, world"
  // hello, world
  // Result: ()