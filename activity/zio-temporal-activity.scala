//> using scala "3.3.0-RC3" // We use Scala 3.3 to leverage SIP-44 (FewerBraces)

//> using lib "dev.zio::zio:2.0.13"
//> using lib "dev.vhonta::zio-temporal-core:0.2.0-M3"
//> using lib "dev.zio::zio-logging:2.1.12"
//> using lib "dev.zio::zio-logging-slf4j2-bridge:2.1.12"
//> using option "-source:future", "-Wunused:imports", "-Wvalue-discard"

import zio.*
import zio.temporal.*
import zio.temporal.worker.*
import zio.temporal.workflow.*
import zio.temporal.activity.*
import zio.logging.*

// This is our workflow interface
@workflowInterface
trait EchoWorkflow:
  @workflowMethod
  def echo(str: String): String

// Workflow implementation
class EchoWorkflowImpl extends EchoWorkflow:
  private val echoActivity: ZActivityStub.Of[EchoActivity] = ZWorkflow
    .newActivityStub[EchoActivity]
    .withStartToCloseTimeout(5.seconds)
    .withRetryOptions(ZRetryOptions.default.withMaximumAttempts(1))
    .build

  override def echo(msg: String): String =
    ZActivityStub.execute(echoActivity.echo(msg))

// Activity interface
@activityInterface
trait EchoActivity:
  @activityMethod
  def echo(msg: String): String

// Activity Implementation
class EchoActivityImpl(
  implicit options: ZActivityOptions[Any],
) extends EchoActivity:
  override def echo(msg: String): String =
    s"ACK: $msg"

val activityLayer: URLayer[ZActivityOptions[Any], EchoActivity] =
  ZLayer.fromFunction(new EchoActivityImpl()(_: ZActivityOptions[Any]))

val taskQueue = "echo-queue"

// Main Application
object Main extends ZIOAppDefault:
  val taskQueue = "echo-queue"

  // Worker implementation
  val worker = ZWorkerFactory.newWorker(taskQueue) @@
    ZWorker.addActivityImplementationService[EchoActivity] @@
    ZWorker.addWorkflow[EchoWorkflowImpl].fromClass

  // Client implementation
  def invokeWorkflow(msg: String) = ZIO.serviceWithZIO[ZWorkflowClient]: client =>
    for
      uuid      <- Random.nextUUID
      workflowID = s"echo-$uuid"
      echoWorkflow <- client
                        .newWorkflowStub[EchoWorkflow]
                        .withTaskQueue(taskQueue)
                        .withWorkflowId(workflowID)
                        .withWorkflowRunTimeout(2.seconds)
                        .withRetryOptions(ZRetryOptions.default.withMaximumAttempts(3))
                        .build
      _   <- ZIO.logInfo(s"Will submit message \"$msg\" with workflow ID $workflowID")
      res <- ZWorkflowStub.execute(echoWorkflow.echo(msg))
      _   <- ZIO.logInfo(s"Greeting received: $res")
    yield res

  // Logging configuration
  val logFilter: LogFilter[String] = LogFilter.logLevelByName(
    LogLevel.Debug,
    "io.grpc.netty" -> LogLevel.Warning,
    "io.netty"      -> LogLevel.Warning,
    "io.temporal"   -> LogLevel.Error,
  )
  override val bootstrap: ZLayer[ZIOAppArgs, Any, Any] =
    Runtime.removeDefaultLoggers >>> consoleLogger(ConsoleLoggerConfig(LogFormat.colored, logFilter))

  // ZIO Main Program
  def run: ZIO[ZIOAppArgs & Scope, Any, Any] =
    val program =
      for
        args           <- getArgs
        msg             = if args.isEmpty then "testMsg" else args.mkString(" ")
        _              <- worker
        _              <- ZWorkerFactory.setup
        _              <- ZWorkflowServiceStubs.setup()
        workflowResult <- invokeWorkflow(msg)
        _              <- ZIO.logInfo(s"The workflow result: $workflowResult")
      yield ExitCode.success

    program
      .provideSome[ZIOAppArgs & Scope](
        ZLayer.succeed(ZWorkflowServiceStubsOptions.default),
        ZLayer.succeed(ZWorkflowClientOptions.default),
        ZLayer.succeed(ZWorkerFactoryOptions.default),
        ZWorkflowClient.make,
        ZWorkflowServiceStubs.make,
        ZWorkerFactory.make,
        ZActivityOptions.default,
        activityLayer,
        slf4j.bridge.Slf4jBridge.initialize,
      )
