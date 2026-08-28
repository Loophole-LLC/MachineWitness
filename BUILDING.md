# Building & deploying Render

See [README.md](README.md) for what this project is and how a piece gets made. This doc is just
the how-to: layout, local development, configuration, and deploying to GCP.

## Layout

Two independent, minimal Java services - no framework, no database, plain JDK `HttpServer` /
`main()` + Maven + Docker + Cloud Run, matching the style used elsewhere in this workspace:

- **`site/`** - `render-site`, a public Cloud Run **service**. Almost entirely static; the
  only server-side logic is stamping the `GCS_BUCKET` env var into a `data-gcs-bucket` attribute
  on `<body>` so the page knows which public bucket to read (deliberately not an inline
  `<script>` - the server's own CSP blocks inline scripts). All gallery rendering happens
  client-side in `assets/site.js`, which fetches `manifest.json` straight from GCS.
- **`generator/`** - `render-generator`, a Cloud Run **Job** (not a web service, no port, never
  publicly reachable). Runs the pipeline above, then exits. Triggered on a schedule by Cloud
  Scheduler, and safe to run as often as you like - it's a no-op unless there's a new ISO week
  it hasn't generated for yet.

```
Render/
  site/          render-site: pom.xml, Dockerfile, cloudbuild.yaml, src/...
  generator/     render-generator: pom.xml, Dockerfile, cloudbuild.yaml, src/...
```

## Local development

Both services are ordinary Maven projects.

```bash
# Build + run the site locally
cd site && mvn -q package
PORT=8080 GCS_BUCKET=your-test-bucket java -jar target/render-site-1.0.0.jar
# -> http://localhost:8080
```

```bash
# Build the generator
cd generator && mvn -q package
```

**Check the feed pipeline without spending anything.** `FetchPreview` fetches the live OPML +
every RSS feed and prints what headlines would go into this week's prompt - no Gemini API key
needed:

```bash
java -cp generator/target/render-generator-1.0.0.jar \
  art.render.generator.tools.FetchPreview 7
```

**Do a real end-to-end run, no GCP required.** Get a Gemini API key from
[Google AI Studio](https://aistudio.google.com/apikey) (image generation needs a billing-enabled
key - the free tier has zero quota for image models) and point output at a local folder instead
of GCS:

```bash
GEMINI_API_KEY=your-key LOCAL_OUT=./out \
  java -jar generator/target/render-generator-1.0.0.jar
```

This pulls the current week's real AI news, generates one real piece (image + rationale), and
writes `./out/images/<week-id>.png` + `./out/manifest.json` (with a relative `imageUrl`, so it
also works as a web root - see next). Running it again the same week is a safe no-op once that
week is in the manifest.

**See it in the actual gallery page**, not just as a raw PNG: point the site at that same folder
with `LOCAL_GALLERY_DIR`. The site then serves `manifest.json`/`images/*` from disk instead of
GCS - this is dev/preview-only, production always reads from the public bucket.

```bash
cd site
LOCAL_GALLERY_DIR=../out PORT=8080 java -jar target/render-site-1.0.0.jar
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
| `TEXT_MODEL`      | generator | no (default `gemini-3.6-flash`)      | Writes the art prompt + rationale from this week's headlines. |
| `IMAGE_MODEL`     | generator | no (default `gemini-3-pro-image`)    | "Nano banana" pro tier. **Check this against Google's current model list before deploying** - image model IDs change over time and this default may lag. |
| `PORT`            | site      | no (default `8080`)                  | Cloud Run sets this automatically. |

## Deploying to GCP

This creates real, billable resources (Cloud Run, a Cloud Storage bucket, and paid Gemini API
calls once the scheduler starts firing for real).

### 1. Project + APIs

```bash
gcloud projects create render-$(date +%s) --name="Render"
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
BUCKET="${PROJECT_ID}-render-gallery"
REGION=us-central1

gsutil mb -l $REGION -b on gs://$BUCKET
gsutil iam ch allUsers:objectViewer gs://$BUCKET
# Required for the browser to fetch manifest.json cross-origin from GCS:
cat > /tmp/cors.json << 'EOF'
[{"origin": ["*"], "method": ["GET", "HEAD"], "responseHeader": ["Content-Type"], "maxAgeSeconds": 3600}]
EOF
gsutil cors set /tmp/cors.json gs://$BUCKET
```

### 3. Gemini API key as a secret

```bash
echo -n "your-gemini-api-key" | gcloud secrets create gemini-api-key --data-file=-
```

### 4. A dedicated, least-privilege service account for the generator

```bash
gcloud iam service-accounts create render-generator \
  --display-name="Render generator job"

gsutil iam ch \
  serviceAccount:render-generator@${PROJECT_ID}.iam.gserviceaccount.com:objectAdmin \
  gs://$BUCKET

gcloud secrets add-iam-policy-binding gemini-api-key \
  --member="serviceAccount:render-generator@${PROJECT_ID}.iam.gserviceaccount.com" \
  --role="roles/secretmanager.secretAccessor"
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
gcloud run services add-iam-policy-binding render-site \
  --region=$REGION --member=allUsers --role=roles/run.invoker
```

### 6. Build and deploy the generator (Cloud Run Job, not public)

```bash
cd ../generator
gcloud builds submit --config cloudbuild.yaml
# note the pushed image tag it prints, e.g. gcr.io/$PROJECT_ID/render-generator:<build-id>

gcloud run jobs deploy render-generator \
  --image gcr.io/$PROJECT_ID/render-generator:<build-id> \
  --region $REGION \
  --service-account render-generator@${PROJECT_ID}.iam.gserviceaccount.com \
  --set-secrets GEMINI_API_KEY=gemini-api-key:latest \
  --set-env-vars GCS_BUCKET=$BUCKET \
  --max-retries 1

# One manual test run before scheduling it:
gcloud run jobs execute render-generator --region $REGION --wait
```

### 7. Schedule it

A dedicated service account lets Cloud Scheduler invoke *only* this job:

```bash
gcloud iam service-accounts create render-scheduler \
  --display-name="Render scheduler invoker"

gcloud run jobs add-iam-policy-binding render-generator \
  --region $REGION \
  --member="serviceAccount:render-scheduler@${PROJECT_ID}.iam.gserviceaccount.com" \
  --role="roles/run.invoker"

gcloud scheduler jobs create http render-daily-check \
  --location $REGION \
  --schedule="0 13 * * *" \
  --uri="https://${REGION}-run.googleapis.com/apis/run.googleapis.com/v1/namespaces/${PROJECT_ID}/jobs/render-generator:run" \
  --http-method=POST \
  --oauth-service-account-email="render-scheduler@${PROJECT_ID}.iam.gserviceaccount.com"
```

Daily is cheap - it's a no-op on every day that isn't the start of a new ISO week's first run,
and only actually calls Gemini once a week. Adjust the cron schedule to taste.

### 8. Custom domain (optional)

```bash
gcloud beta run domain-mappings create \
  --service render-site --domain your-domain.example --region $REGION
```

Then create the DNS records Google Cloud prints.

## Redeploying after a code change

```bash
cd site && gcloud builds submit --config cloudbuild.yaml --substitutions=_REGION=$REGION,_GCS_BUCKET=$BUCKET
cd ../generator && gcloud builds submit --config cloudbuild.yaml
# then update the job to the new image tag it prints:
gcloud run jobs update render-generator --region $REGION \
  --image gcr.io/$PROJECT_ID/render-generator:<new-build-id>
```
