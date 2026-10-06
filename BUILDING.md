# Building & deploying Machine Witness

See [README.md](README.md) for what this project is and how a piece gets made. This doc is just
the how-to: layout, local development, configuration, and deploying to GCP.

## Layout

Two independent, minimal Java services - no framework, no database, plain JDK `HttpServer` /
`main()` + Maven + Docker + Cloud Run, matching the style used elsewhere in this workspace:

- **`site/`** - `machinewitness-site`, a public Cloud Run **service**. Almost entirely static; the
  only server-side logic is stamping the `GCS_BUCKET` env var into a `data-gcs-bucket` attribute
  on `<body>` so the page knows which public bucket to read (deliberately not an inline
  `<script>` - the server's own CSP blocks inline scripts). All gallery rendering happens
  client-side in `assets/site.js`, which fetches `manifest.json` straight from GCS.
- **`generator/`** - `machinewitness-generator`, a Cloud Run **Job** (not a web service, no port, never
  publicly reachable). Runs the pipeline above, then exits. Triggered on a schedule by Cloud
  Scheduler, and safe to run as often as you like - it's a no-op unless there's a new ISO week
  it hasn't generated for yet.

```
site/          machinewitness-site: pom.xml, Dockerfile, cloudbuild.yaml, src/...
generator/     machinewitness-generator: pom.xml, Dockerfile, cloudbuild.yaml, src/...
```

## Local development

Both services are ordinary Maven projects.

```bash
# Build + run the site locally
cd site && mvn -q package
PORT=8080 GCS_BUCKET=your-test-bucket java -jar target/machinewitness-site-1.0.0.jar
# -> http://localhost:8080
```

The master logo is `site/src/main/resources/public/assets/logo.svg`. After editing it, rebuild
the favicon, app icon, Instagram profile image, and social preview together (requires
`rsvg-convert` and Python with Pillow):

```bash
python3 site/tools/build-brand-assets.py
```

```bash
# Build the generator
cd generator && mvn -q package
```

**Check the feed pipeline without spending anything.** `FetchPreview` fetches the live OPML +
every RSS feed and prints what headlines would go into this week's prompt - no Gemini API key
needed:

```bash
java -cp generator/target/machinewitness-generator-1.0.0.jar \
  art.machinewitness.generator.tools.FetchPreview 7
```

**Do a real end-to-end run, no GCP required.** Get a Gemini API key from
[Google AI Studio](https://aistudio.google.com/apikey) (image generation needs a billing-enabled
key - the free tier has zero quota for image models) and point output at a local folder instead
of GCS:

```bash
GEMINI_API_KEY=your-key LOCAL_OUT=./out \
  java -jar generator/target/machinewitness-generator-1.0.0.jar
```

This pulls the current week's real AI news, generates one real piece (image + rationale) from
Gemini, and writes `./out/images/<week-id>-gemini.png` + `./out/manifest.json` (with a relative
`imageUrl`, so it also works as a web root - see next). Running it again the same week is a safe
no-op once that week is in the manifest.

**Add the other five models to the comparison** by setting their keys too - all optional, all
needing billing enabled on their own account the same way Gemini does:

| Model | Key | From |
|-------|-----|------|
| Claude | `ANTHROPIC_API_KEY` | [console.anthropic.com](https://console.anthropic.com/settings/keys) |
| ChatGPT | `OPENAI_API_KEY` | [platform.openai.com](https://platform.openai.com/api-keys) |
| Grok | `XAI_API_KEY` | [console.x.ai](https://console.x.ai/) |
| DeepSeek | `DEEPSEEK_API_KEY` + `TAVILY_API_KEY` | [platform.deepseek.com](https://platform.deepseek.com/api_keys), [tavily.com](https://app.tavily.com/) |
| Mistral | `MISTRAL_API_KEY` | [console.mistral.ai](https://console.mistral.ai/api-keys/) |

The generator only asks a model for a piece once its key is set, so it runs fine with just
Gemini while the rest are being provisioned, and a model can be dropped for a week by unsetting
one variable.

DeepSeek is the exception that needs two keys. Every other model researches the week with its
own lab's built-in web search; DeepSeek's API has no such tool, so its searches are run
client-side against [Tavily](https://tavily.com) and fed back to it. Without `TAVILY_API_KEY` it
would be reacting to bare headlines while the other five did the reading, so it's skipped rather
than run on a different brief.

```bash
GEMINI_API_KEY=your-key ANTHROPIC_API_KEY=your-key OPENAI_API_KEY=your-key \
  XAI_API_KEY=your-key DEEPSEEK_API_KEY=your-key TAVILY_API_KEY=your-key \
  MISTRAL_API_KEY=your-key LOCAL_OUT=./out \
  java -jar generator/target/machinewitness-generator-1.0.0.jar
```

**See it in the actual gallery page**, not just as a raw PNG: point the site at that same folder
with `LOCAL_GALLERY_DIR`. The site then serves `manifest.json`/`images/*` from disk instead of
GCS - this is dev/preview-only, production always reads from the public bucket.

```bash
cd site
LOCAL_GALLERY_DIR=../out PORT=8080 java -jar target/machinewitness-site-1.0.0.jar
# -> open http://localhost:8080
```

Re-run the generator (`LOCAL_OUT=./out ...`) whenever a new ISO week has started to add another
piece to the local archive - no need to restart the site in between, it reads the files fresh on
every request.

## Configuration

| Env var          | Used by   | Required?                          | Notes |
|-------------------|-----------|-------------------------------------|-------|
| `GEMINI_API_KEY`  | generator | yes                                  | Google AI Studio key, billing-enabled (image generation has zero free-tier quota). |
| `GCS_BUCKET`      | both      | yes in production                   | Generator writes here; site reads from here client-side. Not needed if `LOCAL_OUT` is set. |
| `LOCAL_OUT`       | generator | no                                   | Local dir instead of GCS - dev/test only. |
| `LOCAL_GALLERY_DIR` | site    | no                                   | Serves manifest.json/images from this local dir instead of GCS - pair with the generator's `LOCAL_OUT` to preview the real gallery page. Dev/test only. |
| `GEMINI_MODEL`    | generator | no (default `gemini-3.8-flash`)      | Writes Gemini's art prompt + rationale from this week's headlines. |
| `IMAGE_MODEL`     | generator | no (default `gemini-3-pro-image`)    | "Nano banana" pro tier - renders every piece's image, regardless of which model wrote its prompt. **Check this against Google's current model list before deploying** - image model IDs change over time and this default may lag. |
| `ANTHROPIC_API_KEY` | generator | no                                | Anthropic Console key, billing-enabled. Claude only joins the weekly comparison once this is set. |
| `ANTHROPIC_MODEL` | generator | no (default `claude-opus-5-5`)       | Writes Claude's art prompt + rationale, researched with Claude's native web search tool. |
| `OPENAI_API_KEY`  | generator | no                                    | OpenAI Platform key, billing-enabled (the API is prepaid - adding a card alone may not add usable credit, see the account's Billing page). ChatGPT only joins once this is set. |
| `OPENAI_MODEL`    | generator | no (default `gpt-6-astra`)           | Writes ChatGPT's art prompt + rationale, researched via the Responses API's web search tool. |
| `XAI_API_KEY`     | generator | no                                    | xAI console key, billing-enabled. Grok only joins once this is set. |
| `XAI_MODEL`       | generator | no (default `grok-4.7`)              | Writes Grok's art prompt + rationale. xAI serves an OpenAI-Responses-compatible API, so this reuses the ChatGPT writer's SDK against `api.x.ai` with a `web_search` tool - Grok's search reads X alongside the open web. |
| `DEEPSEEK_API_KEY` | generator | no                                   | DeepSeek platform key. DeepSeek only joins once this **and** `TAVILY_API_KEY` are set. |
| `DEEPSEEK_MODEL`  | generator | no (default `deepseek-v4-pro`)       | Writes DeepSeek's art prompt + rationale over OpenAI-compatible chat completions, researched through a client-side `web_search` tool loop. |
| `TAVILY_API_KEY`  | generator | no (required for DeepSeek)           | Search backend for DeepSeek only - the one model here with no web search of its own. Disclosed on the site, since it's the single place the weekly comparison isn't like-for-like. |
| `MISTRAL_API_KEY` | generator | no                                    | Mistral console key. Mistral only joins once this is set. |
| `MISTRAL_MODEL`   | generator | no (default `mistral-medium-latest`) | Writes Mistral's art prompt + rationale via the agent-less Conversations API (`/v1/conversations`, `store: false`), the only endpoint where Mistral's built-in `web_search` tool is supported. The concrete version the alias resolves to is read back off the response and published as the label. |
| `PORT`            | site      | no (default `8080`)                  | Cloud Run sets this automatically. |

## Deploying to GCP

This creates real, billable resources (Cloud Run, a Cloud Storage bucket, and paid Gemini,
Claude, ChatGPT, Grok, DeepSeek, Mistral and Tavily API calls once the scheduler starts firing
for real - six models' worth of research and six image renders a week, where this started at
one). Every model also runs live web search as part of its brief, which is the expensive part of
a run, not the image.

### 1. Project + APIs

```bash
gcloud projects create machinewitness-$(date +%s) --name="Machine Witness"
gcloud config set project <the-project-id-you-just-created>
gcloud services enable run.googleapis.com cloudbuild.googleapis.com \
  secretmanager.googleapis.com cloudscheduler.googleapis.com \
  storage.googleapis.com iam.googleapis.com generativelanguage.googleapis.com
```

(Billing must be linked to the project before Cloud Run/Cloud Build will work -
`gcloud billing projects link` if it isn't already.)

### 2. Public gallery bucket

```bash
PROJECT_ID=$(gcloud config get-value project)
BUCKET="${PROJECT_ID}-machinewitness-gallery"
REGION=us-central1

gsutil mb -l $REGION -b on gs://$BUCKET
gsutil iam ch allUsers:objectViewer gs://$BUCKET
# Required for the browser to fetch manifest.json cross-origin from GCS:
cat > /tmp/cors.json << 'EOF'
[{"origin": ["*"], "method": ["GET", "HEAD"], "responseHeader": ["Content-Type"], "maxAgeSeconds": 3600}]
EOF
gsutil cors set /tmp/cors.json gs://$BUCKET
```

### 3. API keys as secrets

Gemini is required; every other model is optional - skip its secret (and its binding in step 4,
and its `--set-secrets` entry in step 6) to run the comparison with fewer than six models.
`tavily-api-key` is DeepSeek's web search and is only needed alongside `deepseek-api-key`.

```bash
echo -n "your-gemini-api-key" | gcloud secrets create gemini-api-key --data-file=-
echo -n "your-anthropic-api-key" | gcloud secrets create anthropic-api-key --data-file=-
echo -n "your-openai-api-key" | gcloud secrets create openai-api-key --data-file=-
echo -n "your-xai-api-key" | gcloud secrets create xai-api-key --data-file=-
echo -n "your-deepseek-api-key" | gcloud secrets create deepseek-api-key --data-file=-
echo -n "your-tavily-api-key" | gcloud secrets create tavily-api-key --data-file=-
echo -n "your-mistral-api-key" | gcloud secrets create mistral-api-key --data-file=-
```

### 4. A dedicated, least-privilege service account for the generator

```bash
gcloud iam service-accounts create machinewitness-generator \
  --display-name="Machine Witness generator job"

gsutil iam ch \
  serviceAccount:machinewitness-generator@${PROJECT_ID}.iam.gserviceaccount.com:objectAdmin \
  gs://$BUCKET

for secret in gemini-api-key anthropic-api-key openai-api-key xai-api-key \
  deepseek-api-key tavily-api-key mistral-api-key; do
  gcloud secrets add-iam-policy-binding $secret \
    --member="serviceAccount:machinewitness-generator@${PROJECT_ID}.iam.gserviceaccount.com" \
    --role="roles/secretmanager.secretAccessor"
done
```

### 5. Build and deploy the site (public service)

```bash
cd site
gcloud builds submit --config cloudbuild.yaml --substitutions=_REGION=$REGION,_GCS_BUCKET=$BUCKET
```

`cloudbuild.yaml` deploys with `--min-instances 1`, so one instance stays warm at all times -
this avoids cold-start latency on the first request after idle, at the cost of that one instance
always running. Drop the flag (or set it to `0`) if you'd rather scale to zero and accept
occasional cold starts.

If the build finishes with `Setting IAM policy failed`, Cloud Build's own service account
usually lacks permission to grant public access on a freshly-created project - grant it directly:

```bash
gcloud run services add-iam-policy-binding machinewitness-site \
  --region=$REGION --member=allUsers --role=roles/run.invoker
```

### 6. Build and deploy the generator (Cloud Run Job, not public)

```bash
cd ../generator
gcloud builds submit --config cloudbuild.yaml
# note the pushed image tag it prints, e.g. gcr.io/$PROJECT_ID/machinewitness-generator:<build-id>

gcloud run jobs deploy machinewitness-generator \
  --image gcr.io/$PROJECT_ID/machinewitness-generator:<build-id> \
  --region $REGION \
  --service-account machinewitness-generator@${PROJECT_ID}.iam.gserviceaccount.com \
  --set-secrets GEMINI_API_KEY=gemini-api-key:latest,ANTHROPIC_API_KEY=anthropic-api-key:latest,OPENAI_API_KEY=openai-api-key:latest,XAI_API_KEY=xai-api-key:latest,DEEPSEEK_API_KEY=deepseek-api-key:latest,TAVILY_API_KEY=tavily-api-key:latest,MISTRAL_API_KEY=mistral-api-key:latest \
  --set-env-vars GCS_BUCKET=$BUCKET \
  --max-retries 1

# One manual test run before scheduling it:
gcloud run jobs execute machinewitness-generator --region $REGION --wait
```

### 7. Schedule it

A dedicated service account lets Cloud Scheduler invoke *only* this job:

```bash
gcloud iam service-accounts create machinewitness-scheduler \
  --display-name="Machine Witness scheduler invoker"

gcloud run jobs add-iam-policy-binding machinewitness-generator \
  --region $REGION \
  --member="serviceAccount:machinewitness-scheduler@${PROJECT_ID}.iam.gserviceaccount.com" \
  --role="roles/run.invoker"

gcloud scheduler jobs create http machinewitness-daily-check \
  --location $REGION \
  --schedule="0 13 * * *" \
  --uri="https://${REGION}-run.googleapis.com/apis/run.googleapis.com/v1/namespaces/${PROJECT_ID}/jobs/machinewitness-generator:run" \
  --http-method=POST \
  --oauth-service-account-email="machinewitness-scheduler@${PROJECT_ID}.iam.gserviceaccount.com"
```

Daily is cheap - it's a no-op on every day that isn't the start of a new ISO week's first run,
and only actually calls the models once a week. Adjust the cron schedule to taste.

### 8. Custom domain (optional)

```bash
gcloud beta run domain-mappings create \
  --service machinewitness-site --domain your-domain.example --region $REGION
```

Then create the DNS records Google Cloud prints.

## Redeploying after a code change

Run this from a fresh shell at the repo root. It doesn't rely on variables from the first-time
setup above, and every command names its project, so it can't land in whichever project
`gcloud config` happens to have active (otherwise the site step deploys a second, public
`machinewitness-site` into that project and leaves the real one untouched).

```bash
PROJECT_ID=$(gcloud projects list --filter='name="Machine Witness"' --format='value(projectId)')
REGION=us-central1
BUCKET="${PROJECT_ID}-machinewitness-gallery"
```

`${PROJECT_ID:?}` in the commands below stops them if that lookup came back empty.

**Site** (anything under `site/`):

```bash
cd site
gcloud builds submit --project=${PROJECT_ID:?} --config cloudbuild.yaml \
  --substitutions=_REGION=$REGION,_GCS_BUCKET=$BUCKET
```

Always pass `_GCS_BUCKET`: `cloudbuild.yaml` writes it to the service's `GCS_BUCKET` env var, and
an empty value blanks the gallery.

**Generator** (anything under `generator/`):

```bash
cd generator
gcloud builds submit --project=${PROJECT_ID:?} --config cloudbuild.yaml
# then point the job at the new image tag it prints:
gcloud run jobs update machinewitness-generator --project=${PROJECT_ID:?} --region $REGION \
  --image gcr.io/$PROJECT_ID/machinewitness-generator:<new-build-id>
```

**Check it took effect.** `index.html` stamps the serving Cloud Run revision (`K_REVISION`) into
its asset URLs, so these two commands must print the same revision name:

```bash
gcloud run services describe machinewitness-site --project=${PROJECT_ID:?} --region $REGION \
  --format='value(status.latestReadyRevisionName)'
curl -s "https://machinewitness.art/?cb=$RANDOM" | grep -o 'styles.css?v=[^"]*'
```

**Rolling back.** Send traffic to the previous revision (`gcloud run revisions list --service
machinewitness-site --project=${PROJECT_ID:?} --region $REGION` lists them):

```bash
gcloud run services update-traffic machinewitness-site --project=${PROJECT_ID:?} --region $REGION \
  --to-revisions=<previous-revision>=100
```
