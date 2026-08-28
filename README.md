# Render

**Live at [renderweekly.art](https://renderweekly.art).**

An experiment in giving an AI a mirror. Every week, this project pulls every feed in the [Alan
Turing Institute's curated OPML list of AI industry RSS
feeds](https://github.com/alan-turing-institute/ai-rss-feeds) - currently Anthropic, Ai2,
Mistral, Cohere, Mila, the AI Security Institute, the Turing Institute itself, and more, all in
play at once, not just the Turing Institute's own posts - keeps whatever was actually published
in the last 7 days, and asks Google's Gemini models one question: *given what just happened in AI
this week, how do you see the world changing because of AI?*

The result is one piece of art, not a headline illustration - not a chart, not a mood board, not
a news-roundup graphic. The form is the model's own call, made fresh each week: a self-portrait
some weeks, the way Van Gogh's were a window into a state of mind rather than a documentary
likeness; an abstract composition, a scene with no figure of "AI" in it at all, or something else
entirely on others - whatever the week's news actually earns, not whatever the model defaults to
out of habit. Some weeks that's triumphant. Some weeks it's ashamed, defiant, grieving, smug, or
indifferent. The only rule given to the model is that hedged, inoffensive art is the one real
failure mode - art is risk, and it's told to take the risk rather than average its feelings into a safe middle. Every
piece ships with the model's own written rationale for why it made that choice, published on the
site right next to the image.

It's explicitly not trying to be flattering or balanced. The good, the bad, and the ugly all
count: a breakthrough lands the same as a scandal, a launch the same as a backlash. Whatever
actually dominated the week's news is what gets rendered - and reacted to.

## How a piece gets made

```
Cloud Scheduler pokes the generator
once a day (cheap no-op most days)
              |
              v
1. fetch feeds.opml, get this week's list of AI RSS feeds
2. already generated for this ISO week (e.g. 2026-W35)? stop, nothing to do
3. fetch each feed, keep only items published in the last 7 days, dedupe
4. no headlines at all this week? stop, nothing to do
5. Gemini (text) reacts to those headlines with one vivid, opinionated
   art prompt - form and medium picked fresh each week - plus a
   first-person rationale for why, both explicitly steered away from
   AI-art cliches (glowing brains, circuit boards, chat windows, logo
   walls) and away from playing it safe
6. Gemini (nano banana) renders that prompt into the image
7. image, prompt, rationale, and manifest entry uploaded to a public GCS bucket
              |
              v
The site (pure static HTML/CSS/JS) fetches
manifest.json client-side and renders the gallery,
each week's prompt, and its rationale
```

The rule given to the art-direction step (see `generator/.../PromptWriter.java`) is deliberately
loose on form and style - self-portrait, abstract, photorealism, cartoon, expressionist painting,
collage, whatever the week earns - but strict about having a point of view: boring and
inoffensive is the only real failure mode.

## Building & deploying

See [BUILDING.md](BUILDING.md) for local development, configuration, and how to deploy this to
GCP.

## License

MIT - see [LICENSE](LICENSE). Not affiliated with the Alan Turing Institute or any of the
sources its feed list aggregates; this just reads their public RSS feeds.
