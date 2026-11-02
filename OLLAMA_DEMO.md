# Running the copilot on Ollama for the demo

Both copilots (dashboard and review page) answer from a model running on this laptop through Ollama.
Customer data stays on the laptop, and no API key is needed.

**Important: Ollama only works when the back office runs on this laptop.** Open `http://localhost:8080`, not
`https://nexus.jbax.axhub.ai`. The hosted app cannot reach a model on your laptop, so it keeps using its own engine.
See "WhatsApp during the demo" below.

## Start everything (about 2 minutes, before the audience arrives)

Terminal 1, the model server. Leave it open:

```bash
brew services stop ollama
ollama serve
```

Do not use `brew services start ollama` for the demo. The Homebrew service sets `OLLAMA_KV_CACHE_TYPE=q8_0`, which makes
`exaone3.5` fail to load with the error `K cache type q8_0 ... does not divide`.

Terminal 2, load the model so the first question is not slow:

```bash
curl -s localhost:11434/api/generate -d '{"model":"exaone3.5:2.4b","keep_alive":"60m"}' > /dev/null
ollama ps
```

`ollama ps` must list the model.

Terminal 3, the app:

```bash
./gradlew bootRun
```

Then check that the copilot really answers through Ollama:

```bash
curl -s -X POST -H 'Content-Type: application/json' -d '{"question":"심사 대기 큐에서 먼저 볼 신청은?"}' \
  http://localhost:8080/admin/dashboard/copilot | python3 -c "import json,sys;d=json.load(sys.stdin);print(d['mode'],d['model'],d['durationMs'],'ms')"
```

| Result | Meaning |
| --- | --- |
| `ollama <model name> ...` | Ready. |
| `rules None ...` | Ollama did not answer. The page still works, using rule-based answers. See "If something goes wrong". |

## Settings in `wa.env`

```
COPILOT_ENGINE=ollama
OLLAMA_MODEL=exaone3.5:2.4b
```

Restart the app after changing them. Remove `COPILOT_ENGINE` to go back to Claude or the rules.

For rehearsal, add `COPILOT_SHOW_ENGINE=true` to show the model name next to each answer. Leave it out on stage, so the
answers carry the `NEXUS AI` label.

## What to expect on stage

Two models are installed. Both were measured on this laptop through the app, with about 240 applications:

| Model | Time per answer | Accuracy in testing | Memory |
| --- | --- | --- | --- |
| `exaone3.5:2.4b` (set now) | 1.5 to 23 s | Main figures right, but it can mislabel a figure or add its own explanation | 2.3 GB |
| `gemma4:12b` | 56 to 89 s | Every figure right, in the tests run | 13 GB |

`exaone3.5:2.4b` is the one to use on stage, because `gemma4:12b` runs close to the 90-second limit. To use `gemma4:12b`, set
`OLLAMA_MODEL=gemma4:12b` and `OLLAMA_TIMEOUT=120s`, then restart the app. Check the license of `exaone3.5` before any commercial use.

- **Time:** the page shows its thinking animation while the model works.
- **Language:** an English question can get a Korean answer, so ask in Korean on stage.
- **Accuracy:** for each question the app hands the model the exact figures from the rule engine, so the figures in
  answers to the suggested questions should match the screen. A question outside those topics relies on the model
  reading the whole file, and it can get details wrong. Rehearse the questions you plan to ask.
- **Failure:** if Ollama stops or takes longer than 90 seconds, the copilot answers from the rules instead of
  showing an error. Change the limit with `OLLAMA_TIMEOUT=120s`.

## WhatsApp during the demo

The local app and the hosted app have separate databases. If Meta still sends webhooks to the hosted app, a live WhatsApp
application will not appear on `localhost:8080`. You have two ways to run the demo:

1. **Everything local:** run `ngrok http 8080`, then set the Meta webhook callback to `https://<ngrok address>/webhook`. Live
   applications then reach the laptop and the copilot uses Ollama. Set `APP_PUBLIC_BASE_URL` in `wa.env` to the ngrok address.
2. **No live WhatsApp:** present from `localhost:8080` with the demo data only.

## If something goes wrong

| Symptom | Fix |
| --- | --- |
| Answers come from `rules` | Run `ollama ps` and `curl localhost:11434/api/version`. Start `ollama serve` if there is no reply. |
| `does not divide` in `build/bootrun.log` or the Ollama output | The Homebrew service is running. Run `brew services stop ollama`, then `ollama serve`. |
| `address already in use` from `ollama serve` | Ollama is already running. Run `brew services stop ollama`, or quit the other copy. |
| `model not found` | Run `ollama pull <model name>`. Do this at home, because the download is large. |
| Every answer is very slow | Close heavy apps, then run `ollama ps` and check that it says `100% GPU`. |
| `Database may be already in use` when starting the app | Another copy of the app is running. Stop it first. |
