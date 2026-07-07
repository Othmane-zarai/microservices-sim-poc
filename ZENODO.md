# ZENODO.md — How to archive the CloudSim Plus-K8s artifacts

Step-by-step for depositing the artifacts behind the paper **"CloudSim Plus-K8s:
A Contract-Driven Extension for Kubernetes Container Orchestration Simulation"**
(Zarai, Ettazi & Driss, *Software: Practice and Experience*) on
[Zenodo](https://zenodo.org).

> **Status check (2026-07-07):** No Zenodo record / DOI exists yet — this is the
> first deposit, and the paper still carries two `TODO` placeholders for the
> minted DOIs (see [Part D](#part-d--wire-the-dois-back-into-the-paper)).
> **Done so far:** the fork was renamed to `cloudsimplus-k8s`, its WIP committed
> to `master` (`80843ea`) and tagged **`v1.0.1`** (canonical) with `.zenodo.json`
> + `CITATION.cff` added; the former standalone reproducibility bundle
> (`deployment/`, `comparison-scripts/`, `paper/`, `ARTIFACT.md`) has now been
> **merged into `microservices-sim-poc`**, which is dual-licensed —
> GPL-3.0-or-later for the code (`LICENSE`) and CC-BY-4.0 for the data + paper
> (`LICENSE-DATA`). **Still open:** `microservices-sim-poc` is private, and
> 8 tests fail (see `ARTIFACT.md`) — resolve both before minting the DOIs.
>
> **The paper is not published — it is being *submitted* to Wiley *Software:
> Practice and Experience*.** So there is **no journal paper DOI yet**; Wiley
> mints it only on acceptance/publication. Sequence accordingly:
> **now (submission):** mint the two *artifact* DOIs so reviewers can access
> the code and data, and paste them into the manuscript. **later (on
> acceptance):** add the journal paper DOI as a back-link on each Zenodo record
> (a metadata-only edit — see [Part C](#part-c--cross-link-the-two-records)).
> Wherever this guide shows `10.1002/spe.XXXXX`, that is the *future* paper DOI —
> leave it as a placeholder / omit it until you have it.
>
> *Review model:* SPE uses **single-anonymized** review (reviewers see author
> names), so a public, author-named Zenodo deposit at submission is fine. Only if
> a venue required double-blind would you instead share a Zenodo **restricted**
> record via an anonymized access link.

---

## 0. The big picture — two artifacts, two DOIs

There are **two** things to archive. Each gets its own Zenodo record and its own
DOI; they cross-link to each other and to the published paper.

> **What changed.** This used to be three records — the third being a standalone
> "reproducibility bundle" (`microservices-sim-poc-zenodo/`) for the empirical
> data, comparator scripts, and paper sources, uploaded manually as a zip. That
> bundle has been **folded into `microservices-sim-poc`** (under `deployment/`,
> `comparison-scripts/`, `paper/`). It is therefore archived **automatically by
> the same GitHub release** as the code — no separate dataset upload, no zip.

| # | Artifact | What it is | How to deposit | Zenodo "Upload type" |
|---|---|---|---|---|
| 1 | **CloudSim Plus fork** (`Othmane-zarai/cloudsimplus-k8s`) | the `org.cloudsimplus.kubernetes` extension + topology substrate | manual tarball upload (see fork caveat) | **Software** |
| 2 | **`microservices-sim-poc`** repo | 15 worked examples + headless CLI runner **plus** the full reproducibility kit: RQ1–RQ4 + Online Boutique deployment kit and raw real-vs-sim captures (`deployment/`), comparator scripts (`comparison-scripts/`), and LaTeX paper sources (`paper/`) | GitHub release → Zenodo | **Software** |

The combined repo is **Software** (its primary purpose is the examples + runtime
it exercises); the CC-BY-4.0 data and paper ride along inside that same record,
their licensing spelled out by `LICENSE-DATA`. Reviewers get code *and* data from
one DOI.

**Concept DOI vs. Version DOI.** Every Zenodo record has two DOIs:

- a **Concept DOI** — stable, always resolves to the *latest* version;
- a **Version DOI** — points to one specific version (e.g. `v1.0.0`).

**Cite the Version DOI in the paper** (it is what you actually validated
against). Mention the Concept DOI too so readers can find later revisions.

---

## 1. Pre-flight checklist (do this BEFORE you deposit anything)

These come straight from the reproducibility caveats in
[`ARTIFACT.md`](ARTIFACT.md) — the artifact is **not yet in a deposit-ready
state**:

- [ ] **Make `microservices-sim-poc` public.** `ARTIFACT.md` marks it *"NOT
      currently public — must be published/archived before submission."* Zenodo's
      GitHub integration only sees public repos.
- [ ] **Commit + tag a clean snapshot of the fork.** The evaluated build is
      `6f09bc8` **+ ~36 uncommitted local changes** — *no single commit
      reproduces it yet.* Commit the WIP (notably `networking/queueing`), get the
      suite green, then tag (e.g. `git tag -a v1.0.0-k8s -m "…" && git push --tags`).
- [ ] **Get the tests green.** `ARTIFACT.md` records **8 failing tests** (5 in
      the fork, 3 in the wrapper), all in WIP files. The "test-enforced contract"
      claim only holds once these pass. Fix or explicitly document them.
- [ ] **Freeze the toolchain string** you will paste into the metadata:
      JDK 25.0.1 · CloudSim Plus 9.0.0-SNAPSHOT (fork) · k3s v1.35.4+k3s1 ·
      kube-scheduler-simulator v0.4.0 · Online Boutique v0.10.1 · Jaeger 1.57.
- [ ] **Collect ORCIDs** for Othmane Zarai, Widad Ettazi, Riane Driss (optional
      but strongly recommended — it disambiguates authorship on the DOI).
- [ ] **Do a dry run on the Sandbox first:** <https://sandbox.zenodo.org> is a
      throwaway clone of Zenodo. Practice the whole flow there, delete it, then
      repeat on the real site. **Published records are immutable** — you can add
      new *versions* but never edit files of a published version.

---

## Part A — Archive `microservices-sim-poc` (code + data + paper) via GitHub → Zenodo

This is the automated path: a GitHub *Release* triggers a Zenodo webhook that
grabs a source tarball and mints a DOI. Because the reproducibility kit now lives
in this repo, that one tarball carries the code **and** the data + paper. The
fork (repo #1) uses the manual path in [Part B](#part-b--archive-the-cloudsim-plus-fork-manual-upload).

> **Size note.** The release tarball is ~104 MB (dominated by
> `deployment/online-boutique/` Jaeger traces, ~79 MB) — well under Zenodo's
> 50 GB/record cap. The compiled `paper/*.pdf` is intentionally git-ignored, so
> it is **not** in the tarball; the `.tex`/`.bib` sources and figures are. If you
> want the built PDF archived too, `git add -f paper/kubernetes-cloudsimplus.pdf`
> before tagging.

### A.1 Connect Zenodo to GitHub (once)

1. Log in to <https://zenodo.org> **with GitHub** (top-right → "Log in" →
   GitHub) so the two accounts are linked.
2. Go to **Zenodo → (your name) → GitHub**
   (<https://zenodo.org/account/settings/github/>).
3. Find `Othmane-zarai/microservices-sim-poc` in the repository list and flip its
   toggle **ON**. This installs a release webhook on the repo.
   - Not showing up? Click **Sync now**, and confirm the repo is **public** and
     you have admin rights.

### A.2 Add metadata files to the repo (recommended)

These two files sit at the repo root **before** you cut the release so the DOI
metadata is correct and GitHub shows a "Cite this repository" button. They are
**already present** in this repo (Apache→GPL license already fixed); shown here
for reference:

**`.zenodo.json`** — Zenodo reads this to auto-fill the deposit form:

```json
{
  "title": "microservices-sim-poc: Worked examples, CLI runner, and reproducibility kit for CloudSim Plus-K8s",
  "description": "Runnable Kubernetes worked examples (org.cloudsimplus.examples.kubernetes) and a headless CLI runner, plus the RQ1-RQ4 and Online Boutique reproducibility kit (deployment captures, comparator scripts, and LaTeX paper sources), accompanying the paper \"CloudSim Plus-K8s: A Contract-Driven Extension for Kubernetes Container Orchestration Simulation.\"",
  "upload_type": "software",
  "license": "GPL-3.0-or-later",
  "access_right": "open",
  "creators": [
    { "name": "Zarai, Othmane", "orcid": "0000-0000-0000-0000" },
    { "name": "Ettazi, Widad" },
    { "name": "Driss, Riane" }
  ],
  "keywords": [
    "CloudSim Plus", "Kubernetes", "discrete-event simulation",
    "container orchestration", "scheduling", "autoscaling", "reproducibility"
  ],
  "related_identifiers": [
    { "relation": "isSupplementTo", "identifier": "10.1002/spe.XXXXX", "scheme": "doi" }
  ]
}
```

**`CITATION.cff`** — GitHub-native citation metadata:

```yaml
cff-version: 1.2.0
message: "If you use this software, please cite both the paper and this archive."
title: "microservices-sim-poc: Worked examples and CLI runner for CloudSim Plus-K8s"
authors:
  - family-names: Zarai
    given-names: Othmane
    orcid: "https://orcid.org/0000-0000-0000-0000"
  - family-names: Ettazi
    given-names: Widad
  - family-names: Driss
    given-names: Riane
version: 1.0.0
date-released: 2026-07-07
license: GPL-3.0-or-later
repository-code: "https://github.com/Othmane-zarai/microservices-sim-poc"
```

> **Already done for `microservices-sim-poc`:** `.zenodo.json` + `CITATION.cff`
> (both GPL-3.0-or-later, matching the repo's `LICENSE` — the code derives from
> CloudSim Plus, which is GPLv3; the empirical data and paper sources are
> additionally covered by `LICENSE-DATA`, CC-BY-4.0) are in the local checkout —
> they still need to be committed, and the repo made public, before cutting the
> release.

Replace the `0000-…` ORCIDs once known. **At submission, drop the
`related_identifiers` block entirely** (or leave it out) — the paper DOI
`10.1002/spe.XXXXX` does not exist yet. Add it back after acceptance via a
metadata edit (Part C). An invalid/placeholder DOI will fail Zenodo validation.

### A.3 Cut the release

On GitHub: **Releases → Draft a new release** → create tag `v1.0.0` → title
`v1.0.0` → publish. Within a minute Zenodo ingests it and mints a DOI. Find it
under **Zenodo → GitHub** (a DOI badge appears next to the repo). Open the new
record, click **Edit**, verify the metadata, then **Publish**.

> **DOI-in-PDF timing (GitHub path).** Unlike a manual upload, the
> GitHub-integration record's Version DOI is minted *at release time*, so you
> can't reserve it beforehand. Two clean options: (a) cut the release, take the
> DOI, paste it into the paper, and — if you want the *archived sources* to also
> carry it — cut a follow-up `v1.0.1`; or (b) cite the **Concept DOI** (knowable
> once the record exists) in the paper, which always resolves to the latest
> version. Option (b) avoids version churn.

### A.4 Fork is handled separately

Repo #1 (the CloudSim Plus fork) is **not** archived here — Zenodo's GitHub
integration is unreliable for forks. It goes through the manual path in
[Part B](#part-b--archive-the-cloudsim-plus-fork-manual-upload).

---

## Part B — Archive the CloudSim Plus fork (manual upload)

Zenodo's GitHub integration is unreliable for **forks** (the toggle is often
missing or the webhook won't fire), so archive repo #1
(`Othmane-zarai/cloudsimplus-k8s`) by uploading its release tarball to a manual
deposit. This archives the exact tagged commit without depending on fork
webhooks, and keeps the (large) full-fork history out of the record.

> **Alternative — a mirror repo.** Push just the k8s extension to a non-fork
> public repo you own and use the automated A.1–A.3 path instead. Either way,
> **record the exact commit SHA** — the paper pins it.

### B.1 Build the archive

Tag the clean snapshot, create a GitHub Release on the fork, and download the
auto-generated **`Source code (tar.gz)`** for that tag. That single file is what
you upload.

### B.2 Create the deposit

1. Zenodo → **New upload** (<https://zenodo.org/uploads/new>).
2. **Drag in the fork's `Source code (tar.gz)`.**
3. Optionally **also upload `README.md` unzipped** so Zenodo previews it in the
   browser (reviewers see it without downloading the tarball).

### B.3 Fill in the metadata

| Field | Value |
|---|---|
| **Upload type** | Software |
| **Title** | CloudSim Plus-K8s — Kubernetes container-orchestration extension for CloudSim Plus |
| **Authors** | Zarai, Othmane (ORCID) · Ettazi, Widad · Driss, Riane |
| **Description** | The `org.cloudsimplus.kubernetes` extension and topology substrate: contract-driven pod scheduling (filter/score), HPA/VPA/cluster-autoscaler, workload controllers, probes, affinity, and M/M/c per-service latency, layered on CloudSim Plus. Tagged snapshot `v1.0.0-k8s` (commit `<SHA>`). See `ARTIFACT.md` in `microservices-sim-poc` for the measured manifest and toolchain. |
| **License** | GPL-3.0-or-later (derives from CloudSim Plus, GPLv3) |
| **Version** | 1.0.0 |
| **Language** | English |
| **Keywords** | CloudSim Plus; Kubernetes; discrete-event simulation; container orchestration; scheduling; autoscaling |

### B.4 Reserve the DOI (so it can go in the PDF)

Before publishing, in the **DOI** field click **"Reserve DOI"**. Zenodo shows
you the DOI immediately — paste it into the paper (Part D) and build the
camera-ready PDF, *then* come back and **Publish**. This breaks the
chicken-and-egg problem of needing the DOI inside the very document you're
archiving. (For the combined repo, see the GitHub-path timing note in A.3.)

---

## Part C — Cross-link the two records

Do this in **two waves**, because the paper DOI does not exist at submission.

**Wave 1 — at submission** (link the two artifacts to each other):

- **`microservices-sim-poc` record** → `references` the fork DOI (the extension
  it exercises).
- **Fork record** → `isReferencedBy` the `microservices-sim-poc` DOI.

**Wave 2 — after acceptance** (add the journal paper DOI as a back-link):

- **Both records** → add `isSupplementTo` = the paper DOI once Wiley assigns it.

Zenodo lets you **edit metadata of an already-published record** (including
related identifiers) *without* cutting a new version — only the *files* are
frozen. So Wave 2 is just: open each record → **Edit** → add the identifier →
**Save**. This is what makes the artifact "FAIR" and lets a reader jump
paper ⇄ code ⇄ data.

---

## Part D — Wire the DOIs back into the paper

Do this **at submission**, not just at camera-ready: reviewers need to reach the
code and data, so the *artifact* DOIs (the combined `microservices-sim-poc`
record + the fork record) should already be live and cited in the manuscript you
submit. The two `TODO` placeholders below are about the **artifact** DOIs and the
fork commit SHA — all of which you have at submission. (The *journal* paper DOI
is separate and comes later; it does not go in these spots.)

Two `TODO` placeholders in `paper/kubernetes-cloudsimplus.tex` must be replaced.
Search for `TODO` — the exact spots:

1. **~line 2490** (end of the fork/reproducibility paragraph):
   > *"a tagged commit and an archived Zenodo snapshot will be pinned at
   > camera-ready."*
   Replace with the tag (e.g. `v1.0.0-k8s`), the commit SHA, and the **fork
   Version DOI**.

2. **~line 2532, Data Availability Statement** (`\bmsubsection*{Data
   Availability Statement}`):
   > *"the exact tagged commit and an archived Zenodo snapshot (with DOI) will
   > be pinned at camera-ready."*
   Replace with the commit SHA + the **`microservices-sim-poc` DOI** (which now
   carries the data and paper sources) and the **fork DOI**.

Suggested replacement wording for the Data Availability Statement (adapt DOIs):

```latex
The companion artefact for this paper is archived on Zenodo: the worked
examples, headless CLI runner, and full reproducibility kit (the RQ1--RQ4 and
Online Boutique deployment kit, raw real-vs-sim captures, comparator scripts,
and paper sources) at \doi{10.5281/zenodo.XXXXXXX}, and the tagged
\texttt{org.cloudsimplus.kubernetes} extension snapshot
(commit \texttt{<SHA>}, tag \texttt{v1.0.0-k8s}) at
\doi{10.5281/zenodo.YYYYYYY}. The evaluation was produced on JDK~25.0.1
against the fork build.
```

(If `\doi{}` is not defined by the Wiley class, write the full
`\url{https://doi.org/10.5281/zenodo.XXXXXXX}` instead.)

Then recompile (3 pdflatex passes) and re-verify — see the paper build notes.

---

## Part E — Versioning after publication

- **Files are frozen; metadata is not.** You can edit a published record's
  *metadata* (authors, related identifiers, description) any time via **Edit**.
  To change *files*, open the record and click **"New version"** — it keeps the
  Concept DOI and mints a fresh Version DOI.
- **Expect at least one new version.** You will almost certainly cut a `v1.1.0`
  after acceptance (to add the paper DOI everywhere and fold in any
  review-driven artifact fixes). That is normal — cite the version you actually
  validated against.
- Keep `CITATION.cff` / `.zenodo.json` `version:` fields in step with the tags.
- For the `microservices-sim-poc` record, simply cutting a new GitHub Release
  auto-creates the new Zenodo version (integration path) — and, because the data
  now lives in the repo, that same release re-archives any updated captures too.
  For the fork record, upload the new tarball via **"New version"**.

---

## Quick reference — order of operations

```
AT SUBMISSION
1. Make microservices-sim-poc public; commit + tag clean fork snapshot; tests green
2. Connect Zenodo↔GitHub;  add .zenodo.json + CITATION.cff to each repo
3. Release microservices-sim-poc  → Zenodo Software DOI (#2, carries code + data + paper)
4. Archive fork tarball manually  → Zenodo Software DOI (#1)   [fork caveat]
5. Take both DOIs (concept DOI, or reserve for the fork) → paste + commit SHA
   into paper (2 TODO spots) + recompile
6. Cross-link the two records to each other (Wave 1 related identifiers)
7. Publish both records; confirm DOIs resolve; submit to Wiley SPE

AFTER ACCEPTANCE
8. Add the journal paper DOI to both records (metadata edit, Wave 2)
9. Cut v1.1.0 if any review-driven artifact fixes; update paper DOI wording
```
