// Run with scala-cli test .

//> using scala "3.3.0-RC3"
//> using repository "sonatype:snapshots"
//> using resourceDir "./resources"

//> using lib "dev.zio::zio:2.0.13"
//> using lib "dev.zio::zio-test:2.0.13"
//> using lib "dev.zio::zio-test-sbt:2.0.13"
//> using lib "dev.vhonta::zio-temporal-core:0.2.0-M2"
//> using lib "dev.vhonta::zio-temporal-testkit:0.2.0-M2"

//> using option "-source:future", "-Wunused:all", "-Wvalue-discard"

import java.util.UUID

import zio.*
import zio.temporal.*
import zio.temporal.testkit.ZTestEnvironmentOptions
import zio.temporal.testkit.ZTestWorkflowEnvironment
import zio.temporal.worker.ZWorkerOptions
import zio.temporal.workflow.*
import zio.test.*
import zio.test.TestAspect.*

object EchoWorkflowSpec extends ZIOSpecDefault:
  private def withWorkflow[R, E, A](
    f: ZIO[R, TemporalError[E], A],
  ): RIO[R, A] =
    f.mapError(e => new RuntimeException(s"IO failed with $e"))

  def spec = suite("Workflows")(
    test("runs echo workflow") {
      ZIO.serviceWithZIO[ZTestWorkflowEnvironment[Any]] { testEnv =>
        val taskQueue = "test-queue"
        val sampleIn  = "Msg"
        val sampleOut = s"ACK: $sampleIn"
        val client    = testEnv.workflowClient

        testEnv
          .newWorker(taskQueue, options = ZWorkerOptions.default)
          .addWorkflow[EchoWorkflowImpl]
          .fromClass

        withWorkflow {
          testEnv.use() {
            for
              echoWorkflow <- client
                                .newWorkflowStub[EchoWorkflow]
                                .withTaskQueue(taskQueue)
                                .withWorkflowId(UUID.randomUUID().toString)
                                .withWorkflowRunTimeout(10.seconds)
                                .build
              result <- ZWorkflowStub.execute(echoWorkflow.echo(sampleIn))
            yield assertTrue(result == sampleOut)
          }
        }
      }
    }.provide(
      ZLayer.succeed(ZTestEnvironmentOptions.default),
      ZTestWorkflowEnvironment.make[Any],
    ),
  )
