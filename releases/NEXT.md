# Pending local changes — not published

The next release must include `V006__judge_error_utf8mb4`, `V007__course_chapters`, and new backend/frontend images.
Do not amend the published v0.3.3 manifest: its images and five migrations remain the historical release record.

## Compiler diagnostic encoding

An invalid C++ submission containing four-byte Unicode characters could compile unsuccessfully, then fail to save the compiler diagnostic to the legacy utf8mb3 `judge.error_message` column. The backend displayed the resulting HTTP 500 as a judge connection failure.

V006 changes that column to utf8mb4 without changing submitted source or verdicts. The initial database SQL uses the same encoding. Existing installations apply the migration on startup with the new backend image.

Local verification on 2026-11-04:

- Compiled the actual `EduSchemaMigration` class against the existing application dependencies and ran it against MySQL twice; six migration checksums were recorded exactly once.
- Confirmed every existing submission's source and identity remained unchanged during the schema change.
- Rejudged the two affected original submissions through the normal API: both now return Compile Error, retaining the quoted Unicode characters in their diagnostics.
- Finished all 22,627 historical school submissions with no infrastructure errors. The changed verdict report and database backups are private local artifacts.

## Curriculum data awaiting deployment

The local database has 44 courses: 43 published and one empty advanced homework draft. Ten school courses were added, 33 migration descriptions were cleaned, and all 44 course icons were replaced. C++ and Python school courses use their respective language icons; courses from the first five original teams use the corresponding gauge icons.

These are database and file changes. Git and application images do not contain the course records, class references, uploaded icons, test files or student verdicts. Prepare and validate a private migration package, preserving new server submissions, before deploying this curriculum update.

The local judge's larger heap and parallel settings were chosen for this Windows development machine. Review memory and concurrency separately for the 2-core / 4-GiB server; do not copy that local override as a production default.

Neither the server nor the GitHub branch has been updated with these changes yet.

## Course chapters and practice-based name colors

- Chapters have their own editable number; changing a chapter does not change the global course sorting ID. Lists, class course strips, selectors and detail pages show the same Chinese chapter label.
- Local courses were numbered within each original team or school course series. The separate chapter metadata is included in database backups and supported by the V007 startup migration.
- Growth score currently equals the all-time count of distinct accepted problems, using the same source as the ACM ranking. Repeated accepted submissions do not add points. The server owns the score calculation and color rules so a later assignment/reward system can replace that calculation in one place.
- Levels begin at 0, 10, 30, 60, 100, 160, 240 and 400 solved problems. Colors progress through gray, green, cyan, blue, purple, orange and red; the highest tier uses dark red with a black first character.
- Name text shares one batched lookup/cache across navigation, profiles, rankings, submissions, course ownership, class rosters, discussions, comments, messages and legacy contest/team pages. Ranking chart labels and copied class-progress images also use the same colors.
- Visible name colors refresh every minute and when the page regains focus. The personal profile includes a growth-score badge and a level explanation.

Local validation: 67 Java tests; frontend production compilation; batched-name, recycled-row, first-profile-load and refresh regression checks; independent live SQL/API score comparisons; unchanged student course access and chapter editing restrictions; visible course, rank and profile acceptance. No cloud deployment or registry push has occurred.
