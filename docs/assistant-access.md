# Giving your own assistant access

Taiga Sleep does not ship an assistant, recommend a model or send anything anywhere by
itself. If you want an assistant or a script of your own to look at your nights, you can
give it a **read-only access token**: it can read your recordings, analyses and results,
and it cannot change anything.

## Make a token

In the app: **Settings → Server → Access tokens**. Give the token a name, tap **Create
token**, and copy it when it appears. It is shown once; the server keeps only a hash of it.
The list shows each token by its name, its last characters and when it was made, and a
token you no longer want is revoked there and stops working at once.

With `curl` and your account's login, from a terminal on your own machine:

```sh
BASE=https://sleep.example.com
LOGIN_TOKEN=$(curl -s -X POST "$BASE/v1/auth/login" \
  -H 'Content-Type: application/json' \
  -d '{"email":"you@example.com","password":"your password"}' \
  | python3 -c 'import json,sys; print(json.load(sys.stdin)["token"])')

curl -s -X POST "$BASE/v1/tokens" \
  -H "Authorization: Bearer $LOGIN_TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"name":"my assistant"}'
```

The answer's `token` field is the access token. Keep it as `TOKEN` in your shell (the
examples below use it); the login token was only needed to make one.

```sh
TOKEN=ts_...
```

## Point the assistant at the API

Give the assistant these three things:

- **Base URL**: your server, for example `https://sleep.example.com`.
- **Header on every request**: `Authorization: Bearer <access token>`.
- **The contract**: `GET /v1/openapi.json`, browsable at `GET /v1/docs`. The OpenAPI
  document lists every endpoint, its parameters and its schemas, so the assistant does not
  have to guess. Only the endpoints marked for a `token` may be called; everything else is
  refused.

## Example requests

The recordings, newest first, without their raw files:

```sh
curl -s "$BASE/v1/recordings?limit=20" -H "Authorization: Bearer $TOKEN"
```

What a recording is: its metadata (`meta` is the phone's `meta.json`), its state, when it
started and ended, and its size:

```sh
curl -s "$BASE/v1/recordings/20261023_223000-k3f9" -H "Authorization: Bearer $TOKEN"
```

The analyses of that recording, each with its summary and its full result:

```sh
curl -s "$BASE/v1/recordings/20261023_223000-k3f9/analyses?expand=result" \
  -H "Authorization: Bearer $TOKEN"
```

Every analysis of the account, newest first, with its result. This is the one request a
first look usually needs:

```sh
curl -s "$BASE/v1/analyses?limit=200&expand=result" -H "Authorization: Bearer $TOKEN"
```

One analysis by its id:

```sh
curl -s "$BASE/v1/analyses/a1b2c3d4e5f6g7h" -H "Authorization: Bearer $TOKEN"
```

What a token cannot do: write anything, download the raw recording files, see another
account's nights, or manage tokens. Those requests are refused, and an account's data
stays theirs.

## Reading the data

The text below is written for the assistant. Copy it into its instructions.

> The data you can read comes from a wrist or chest sensor worn overnight and from models
> that estimate what happened. Read it like this.
>
> - **The sleep stages are model estimates, not measurements.** `wake`, `rem`, `light`,
>   `deep` and `unknown` are produced by an algorithm from heart rate and movement. They
>   are not checked against polysomnography or any sleep-lab measurement. Say "the model
>   estimates", not "you were in deep sleep", and do not present a stage as a fact about
>   the person.
> - **Check the data quality before you interpret anything.** Look at how long the
>   recording is, which streams it has, whether the samples cover the night without gaps,
>   and at the result's `quality` items where they exist. If the coverage is poor, a
>   recording is short or the signal is missing, say the night cannot be read and stop
>   there. Poor data is not a health finding.
> - **Night-to-night change matters more than a single value.** One score or one stage
>   share varies with the sensor position, the night's length and the model's uncertainty.
>   Compare several nights, say which nights you compared and how much they differ, and
>   describe a trend rather than a verdict from one night.
> - **Scores are combinations, not independent evidence.** A score is a weighted mix of
>   other figures from the same data, so it cannot confirm them. Explain which components
>   moved when a score changes.
> - **Render only what a result contains.** Every figure in a result is an item with a
>   `label`, a `unit`, an `explanation` and its `sources`. Do not invent a metric or a
>   number that is not there, and do not translate a missing figure into "normal".
> - **Do not give medical advice.** Describe what the data shows and how uncertain it is.
>   For a health concern, point to a clinician.
