# ZIO Temporal Sample

This repository contains some ZIO Temporal samples, mostly based on the original Temporal Java SDK getting-started.

The samples uses [scala-cli](https://scala-cli.virtuslab.org/) for ease-of-use.

Download [Temporal cli](https://github.com/temporalio/cli) (recommended for development), an "all-in-one" binary for Temporal development and testing which is much lighter on resources:

```sh
# Install with:
curl -sSf https://temporal.download/cli.sh | sh

# and run the dev server in another shell listening on all IPs (by default, it listens on localhost only)
$HOME/.temporalio/bin/temporal server start-dev --ip 0.0.0.0
```

Run the samples like: `scala-cli workflow/zio-temporal.scala`.

Watch the workflow result with the [Temporal Web UI](http://localhost:8233/) or using the [tctl](https://github.com/temporalio/tctl) command line tool:

```sh
❯ tctl workflow observe --workflow_id echo-81ef73da-d54d-492a-8f91-78e888dcebc8
Progress:
  1, 2023-04-18T20:34:00Z, WorkflowExecutionStarted
  2, 2023-04-18T20:34:00Z, WorkflowTaskScheduled
  3, 2023-04-18T20:34:00Z, WorkflowTaskStarted
  4, 2023-04-18T20:34:00Z, WorkflowTaskCompleted
  5, 2023-04-18T20:34:00Z, WorkflowExecutionCompleted

Result:
  Run Time: 1 seconds
  Status: COMPLETED
  Output: ["ACK: testMsg"]
```

To generate a GraalVM native-image, run: `scala-cli package --native-image -f activity-retry/zio-temporal-activity-retry.scala -o zio-temporal-retry`.

To use GraalVM native-image agent to generate reflection and proxy configs, run the demo with:

```sh
❯ scli activity-retry/zio-temporal-activity-retry.scala -J -agentlib:native-image-agent=config-output-dir=activity-retry/resources/META-INF/native-image
```
