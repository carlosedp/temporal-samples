//> using scala "3.3.0-RC3" // We use Scala 3.3 to leverage SIP-44 (FewerBraces)

//> using lib "dev.zio::zio:2.0.13"
//> using lib "dev.vhonta::zio-temporal-core:0.2.0-M3"
//> using lib "dev.zio::zio-logging:2.1.12"
//> using lib "dev.zio::zio-logging-slf4j2-bridge:2.1.12"
//> using option "-source:future", "-Wunused:imports", "-Wvalue-discard"
//> using resourceDir "./resources"

import zio.*
import zio.temporal.*
import zio.temporal.worker.*
import zio.temporal.workflow.*
import zio.temporal.activity.*
import zio.logging.*
import io.temporal.client.WorkflowException

// Activity Interface
@activityInterface
trait EchoActivity:
  @activityMethod
  def echo(msg: String): String

object EchoActivityImpl:
  val activityLayer: URLayer[ZActivityOptions[Any], EchoActivity] =
    ZLayer.fromFunction(new EchoActivityImpl()(_: ZActivityOptions[Any]))

// Activity Implementation
class EchoActivityImpl(
  implicit options: ZActivityOptions[Any]
) extends EchoActivity:

  /**
   * Echo the message back to the caller
   *
   * @param msg
   *   The message to be echoed
   * @return
   *   The message or an exception
   */
  override def echo(msg: String) =
    ZIO.logInfo("Worker: Will process message")
    ZActivity.run:
      for
        _      <- ZIO.logInfo(s"Worker: Activity received message: $msg")
        newMsg <- eventuallyFail(s"ACK: $msg", 30)
        _      <- ZIO.logDebug(s"Worker: Activity reply with: $newMsg")
      yield newMsg

  /**
   * Simulate a failure in the activity
   *
   * @param msg
   *   The message to be returned
   * @param successPercent
   *   The percentage of success
   * @return
   *   The message or an exception
   */
  def eventuallyFail(
    msg:            String,
    successPercent: Int = 50,
  ): ZIO[Any, Exception, String] =
    require(successPercent >= 0 && successPercent <= 100)
    for
      percent <- Random.nextIntBetween(0, 100)
      _       <- ZIO.logDebug(s"Worker: Generated percent is $percent")
      _ <- ZIO.when(percent > successPercent):
             ZIO.logError("Worker: eventuallyFail - Failed to process message") *> ZIO.fail(
               Exception(s"Worker: ERROR: $msg")
             )
      _ <- ZIO.logInfo("Worker: Success processing message")
      r  = msg
    yield r

// Workflow interface
@workflowInterface
trait EchoWorkflow:
  @workflowMethod
  def echo(msg: String): String

// Workflow implementation
class EchoWorkflowImpl extends EchoWorkflow:
  private val echoActivity: ZActivityStub.Of[EchoActivity] = ZWorkflow
    .newActivityStub[EchoActivity]
    .withStartToCloseTimeout(60.seconds)
    .withRetryOptions(
      ZRetryOptions.default
        .withMaximumAttempts(3)
        .withBackoffCoefficient(2)
    )
    .build

  override def echo(msg: String): String =
    ZActivityStub.execute(echoActivity.echo(msg))

val taskQueue = "echo-queue"

// Worker implementation
object WorkerModule:
  val stubOptions = ZWorkflowServiceStubsOptions.default.withServiceUrl(
    scala.util.Properties.envOrElse("TEMPORAL_SERVER", "127.0.0.1:7233")
  )

  val worker = ZWorkerFactory.newWorker(taskQueue) @@
    ZWorker.addActivityImplementationService[EchoActivity] @@
    ZWorker.addWorkflow[EchoWorkflowImpl].fromClass

// Client implementation
object Client:
  // Client implementation
  def invokeWorkflow(msg: String) = ZIO.serviceWithZIO[ZWorkflowClient]: client =>
    for
      uuid      <- Random.nextUUID
      workflowID = s"echo-$uuid"
      echoWorkflow <- client
                        .newWorkflowStub[EchoWorkflow]
                        .withTaskQueue(taskQueue)
                        .withWorkflowId(workflowID)
                        .withWorkflowRunTimeout(60.seconds)
                        .build
      _ <- ZIO.logInfo(s"Will submit message \"$msg\" with workflow ID $workflowID")
      // Here we execute the workflow and catch any error returning a success to the caller with
      // the processed message or an error message
      res <- ZWorkflowStub.execute(echoWorkflow.echo(msg)).catchAll:
               case e: WorkflowException =>
                 ZIO.logError(s"Client: Exceeded retries, error: $e") *> ZIO.succeed(
                   "Exceeded retries"
                 )
      _ <- ZIO.logInfo(s"Greeting received: $res")
    yield res

// Main Application
object Main extends ZIOAppDefault:
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
        _              <- WorkerModule.worker
        _              <- ZWorkerFactory.setup
        _              <- ZWorkflowServiceStubs.setup()
        workflowResult <- Client.invokeWorkflow(msg)
        _              <- ZIO.logInfo(s"The workflow result: $workflowResult")
      yield ExitCode.success

    program
      .provideSome[ZIOAppArgs & Scope](
        ZLayer.succeed(WorkerModule.stubOptions),
        ZLayer.succeed(ZWorkflowClientOptions.default),
        ZLayer.succeed(ZWorkerFactoryOptions.default),
        ZWorkflowClient.make,
        ZWorkflowServiceStubs.make,
        ZWorkerFactory.make,
        ZActivityOptions.default,
        EchoActivityImpl.activityLayer,
        slf4j.bridge.Slf4jBridge.initialize,
      )
