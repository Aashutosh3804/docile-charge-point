package chargepoint.docile

import scala.concurrent.Await
import scala.concurrent.duration._
import java.net.URI
import akka.actor.ActorSystem
import com.thenewmotion.ocpp.Version
import org.rogach.scallop._
import slogging.{LogLevel, LoggerConfig, PrintLoggerFactory, StrictLogging}

import test.{Runner, RunnerConfig}
import interpreter.{ExpectationFailed, ExecutionError}

object Main extends App with StrictLogging {

  object conf extends ScallopConf(args) {
    implicit val versionConverter =
      singleArgConverter(Version.withName(_).get, {
        case _: NoSuchElementException => Left("Invalid OCPP version provided")
      })

    val version = opt[Version](
      default = Some(Version.V16),
      descr = "OCPP version"
    )

    val authKey = opt[String](
      descr = "Authorization key to use for Basic Auth (hex-encoded, 40 characters)"
    )

    val chargePointId = opt[String](
      default = Some("03000001"),
      descr="ChargePointIdentity to identify ourselves to the Central System"
    )

    val verbose = opt[Int](
      default = Some(3),
      descr="Verbosity (0-5)"
    )

    val uri = trailArg[URI](
      descr = "URI of the Central System"
    )

    val files = trailArg[List[String]](
      descr = "files with test cases to load"
    )

    verify()
  }

  LoggerConfig.factory = PrintLoggerFactory()
  LoggerConfig.level = conf.verbose() match {
    case 0 => LogLevel.OFF
    case 1 => LogLevel.ERROR
    case 2 => LogLevel.WARN
    case 3 => LogLevel.INFO
    case 4 => LogLevel.DEBUG
    case 5 => LogLevel.TRACE
    case _ => sys.error("Invalid verbosity, should be 0, 1, 2, 3, 4 or 5")
  }

  val system = ActorSystem()

  implicit val ec = system.dispatcher

  val runnerCfg = RunnerConfig(
    system = system,
    chargePointId = conf.chargePointId(),
    uri = conf.uri(),
    ocppVersion = conf.version(),
    authKey = conf.authKey.toOption
  )

  val programs = conf.files().map(Runner.loadFile)
  val runner = new Runner(runnerCfg, programs)

  val outcomes = runner.run() map { testResult =>
    logger.debug(s"Awaiting test ${testResult._1}")
    val res = Await.result(testResult._2, 45.seconds)

    val outcomeDescription = res match {
      case Left(ExpectationFailed(msg)) => s"❌  $msg"
      case Left(ExecutionError(e))      => s"💥  ${e.getClass.getSimpleName} ${e.getMessage}"
      case Right(())                     => s"✅"
    }

    println(s"${testResult._1}: $outcomeDescription")

    res
  }

  logger.debug("End of main body reached, terminating Akka...")
  system.terminate() foreach { _ =>
    sys.exit(if (!outcomes.exists(_.isLeft)) 0 else 1)
  }
}
