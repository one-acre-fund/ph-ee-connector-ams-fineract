# Fineract AMS Connector

An Account Management System (AMS) connector for Fineract. This connector gets invoked after a
collection
has been made on a Fineract client's M-PESA wallet. Its primary role is to notify the Fineract service
about a successful
payment made by a client.

This AMS connector is a Zeebe project that is part of the OAF Payment Hub EE setup. See
the [Payment Hub EE documentation](https://mifos.gitbook.io/docs/payment-hub-ee/overview)
for more information about Zeebe projects and Payment Hub in general.

## Badges

[![Build Status](https://dev.azure.com/OAFDev/prd-pipelines/_apis/build/status/one-acre-fund.ph-ee-connector-ams-fineract?branchName=main)](https://dev.azure.com/OAFDev/prd-pipelines/_build/latest?definitionId=171&branchName=main)

## Tech Stack

- Java 17
- Spring Boot
- Apache Camel
- Zeebe Java Client

## Getting Started

Clone the project

  ```bash
    git clone https://github.com/one-acre-fund/ph-ee-connector-ams-fineract.git
    cd ph-ee-connector-ams-fineract
  ```

This connector is expected to be run alongside other connectors/services. It depends on some of
those services being up
and healthy. For local development, the services that are most critical for running this project
have been included in
the `docker-compose.yml` file. The following components are included:

- Zeebe: A workflow engine for microservices orchestration. This must be running in a healthy state
  otherwise errors
  will occur when the services below attempt to connect to it.
- Zeebe-ops: Provides APIs for carrying out certain operations on zeebe such as uploading a bpmn
  file
- Channel-connector: Provides APIs for initiating collection requests
- Mpesa-connector: Contains workers that handle the connection to M-PESA for making the actual
  collection from a
  client's wallet

A lot more services can be added to the above based on your needs, but to run the Fineract ams
connector locally,
the ones listed above are the required minimum.
Please note that the `docker-compose.yml` file in this repository should NOT be used in a production
environment.

## Running with Docker

Some images listed in the `docker-compose.yml` are available on OAF's Azure Container Registry (
ACR). To be able to pull
them, certain permissions must be granted to your azure account. Follow the steps below to
successfully run the project:

- Ensure [Docker](https://docs.docker.com/get-docker/) is installed on your machine

- Authenticate with
  azure. [Install the Azure CLI](https://learn.microsoft.com/en-us/cli/azure/install-azure-cli)
  on your machine if it's not already available, and then run the command below

  ```bash
      az acr login -n oaftech # Log in to OAF's ACR through the Docker CLI.
   ```

- Run the project:

  Update `src/main/resources/application.yml` with the appropriate values where necessary, or
  provide the
  values as environment variables in the `services.fineract-connector.environment` section of
  the `docker-compose.yml`
  file, and run the command below:

  ```bash
      docker compose up -d
   ```

## Usage

To initiate a collection request, and have the Fineract connector notify the Fineract AMS about the
payment, follow the
steps below:

- Upload the Fineract bpmn (found in `src/main/resources/mpesa_flow_fineract-oaf.bpmn`) through *
  *zeebe-ops** by sending a
  POST
  request to `http://localhost:5001/zeebe/upload` with the file attached.

- Send a collection request through the **channel-connector** by sending a POST request
  to `http://localhost:5002/channel/collection`
  with a sample body as shown below:
  ```json
  {
    "payer": [
        {
            "key": "MSISDN",
            "value": "254113888031"
        },
        {
            "key": "fineractAccountID",
            "value": "60649568"
        }
    ],
    "amount": {
        "amount": "1",
        "currency": "KES"
    },
    "transactionType": {
        "scenario": "MPESA",
        "subScenario": "BUYGOODS",
        "initiator": "PAYEE",
        "initiatorType": "BUSINESS"
    }
  }
  ```
- Check the logs in the **fineract-connector** container to see that settlement has been handled, and
  see the result of
  calling the Fineract service

## Calls to Fineract: timeouts, retries and circuit breaker

Every call to Fineract (verification, client details, confirmation, payments) has its own timeouts,
retries and circuit breaker, one per endpoint and host (for example
`fineract-confirmation-loans.qa.oneacrefund.org`).

**Retries** are bounded, with exponential backoff and jitter. A call is retried when:

- the connection fails (the request never reached Fineract);
- Fineract answers with a status in the endpoint's `retry-status-codes`;
- no response comes back (read timeout), only if the endpoint's `retry-when-outcome-unknown` is true.

**Reads and writes differ.** The verification and the client details lookup move no money, so they
are retried on timeouts too. The confirmation and the payments call post a settlement into the
ledger: only 502 and 503 (the gateway did not hand the request on) are retried, and a request that
got no answer is not sent again, because Fineract has not confirmed that a second confirmation
with the same `remoteTransactionId` is ignored. The settlement is failed with `UNKNOWN_OUTCOME` so
an operator checks Fineract before settling it again. Once Fineract confirms it deduplicates, set
`FINERACT_RESILIENCE_CONFIRMATION_RETRY_RETRYWHENOUTCOMEUNKNOWN=true` (and the same for `PAYMENTS`)
and such calls are retried.

**Retries stay within the Zeebe job.** An attempt (first call or retry) is only made if it can
finish, at its connect and read timeouts, `job-deadline-margin` (10s) before the job's deadline.
Otherwise the call ends with the previous attempt's result, or as not sent when no attempt was
made. A job therefore never times out while a call is running, so Zeebe cannot hand it to another
worker that would post the confirmation again.

**Circuit breaker.** When `failure-rate-threshold` percent of the last `sliding-window-size` calls
fail (connection failures, timeouts, 5xx; 4xx do not count), the circuit opens and calls fail at
once without reaching Fineract, with `CIRCUIT_OPEN`. After `wait-duration-in-open-state`,
`permitted-calls-in-half-open-state` probe calls are let through; if they succeed the circuit
closes, otherwise it opens again.

**Error codes** on a failed step (`errorCode` in the workflow, with the reason in
`errorInformation` and `errorDescription`):

| `errorCode` | Meaning | Safe to run again? |
|---|---|---|
| HTTP status (e.g. `400`, `500`) | Fineract answered with that status; body in `errorInformation` | depends on the answer |
| `CONNECTION_ERROR` | the request never reached Fineract, even after retries | yes |
| `CIRCUIT_OPEN` | not sent, Fineract is being failed fast | yes, after recovery |
| `JOB_DEADLINE` | not sent, no time left before the Zeebe job deadline | yes |
| `UNKNOWN_OUTCOME` | sent, but no response came back | reads: yes; writes: check Fineract first |

**Defaults** (`fineract.resilience.<endpoint>` in `application.yml`; `<endpoint>` is
`verification`, `client-details`, `confirmation` or `payments`):

| Setting | verification | client-details | confirmation | payments |
|---|---|---|---|---|
| `connect-timeout` | 5s | 5s | 5s | 5s |
| `read-timeout` | `ams.timeout` (60s) | `ams.timeout` (60s) | `ams.timeout` (60s) | `ams.timeout` (60s) |
| `retry.max-attempts` (including the first call) | 3 | 3 | 3 | 3 |
| `retry.initial-backoff` / `backoff-multiplier` / `jitter` | 500ms / 2.0 / 0.5 | 500ms / 2.0 / 0.5 | 500ms / 2.0 / 0.5 | 500ms / 2.0 / 0.5 |
| `retry.retry-status-codes` | 502,503,504 | 500,502,503,504 | 502,503 | 502,503 |
| `retry.retry-when-outcome-unknown` | true | true | false | false |
| `circuit-breaker.failure-rate-threshold` (%) | 50 | 50 | 50 | 50 |
| `circuit-breaker.sliding-window-size` / `minimum-number-of-calls` | 20 / 10 | 20 / 10 | 20 / 10 | 20 / 10 |
| `circuit-breaker.wait-duration-in-open-state` | 30s | 30s | 30s | 30s |
| `circuit-breaker.permitted-calls-in-half-open-state` | 3 | 3 | 3 | 3 |

`fineract.resilience.job-deadline-margin` is 10s. Override any value with an environment variable,
for example `FINERACT_RESILIENCE_CONFIRMATION_READTIMEOUT=20s`,
`FINERACT_RESILIENCE_VERIFICATION_RETRY_MAXATTEMPTS=2` or
`FINERACT_RESILIENCE_CONFIRMATION_CIRCUITBREAKER_WAITDURATIONINOPENSTATE=1m`.

**Monitoring.**

- Metrics on `/actuator/prometheus`: `resilience4j_circuitbreaker_state{name,state}`,
  `resilience4j_circuitbreaker_calls_seconds_*{name,kind}` and
  `resilience4j_retry_calls_total{name,kind}` (`kind="failed_with_retry"` counts calls that failed
  after all retries).
- Log events, for alerting:
  - `event=fineract_circuit_state_changed circuit=<name> from=<state> to=<state>`
  - `event=fineract_call_rejected circuit=<name>` (failed fast while open)
  - `event=fineract_call_retry call=<name> attempt=<n> wait_ms=<ms> reason=<reason>`
  - `event=fineract_retry_exhausted call=<name> attempts=<n> reason=<reason>`
  - `event=fineract_call_skipped call=<name> reason=job_deadline after=<reason>` (no time left
    before the job deadline)
- Reasons name the status code or exception class only, never URLs, headers or bodies.

## Troubleshooting

If an error occurs while carrying out any of the steps above, check if the zeebe container is in a
healthy state by
either viewing its state through `docker ps` or sending a GET request
to `http://localhost:9600/health`.
If the zeebe container shows state as unhealthy or the health endpoint doesn't return a 204 status
response, restart the
zeebe container.

## Contributing

See `CONTRIBUTING.md` for ways to get started.
